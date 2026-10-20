package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.message.AgentMessagePort;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个 Chat Agent 的异步恢复与保存状态机。
 * 所有状态转换、快照安装、业务 mutation 和业务回调都在所属 mailbox 内执行；I/O 回调只负责回投消息。
 */
public final class ChatAgentPersistence {
    private final AgentIdentity identity;
    private final ActorRef self;
    private final AgentMessagePort messages;
    private final AsyncChatStateStore store;
    private final long ownerToken;
    private final int pendingCapacity;
    private final Supplier<ChatChannelSnapshot> snapshot;
    private final Consumer<ChatChannelSnapshot> restore;
    private final Runnable fence;
    private final Deque<PendingMutation> pending = new ArrayDeque<>();
    private volatile State state = State.RECOVERING;
    private volatile int pendingCount;
    private long persistenceVersion;
    private Throwable terminalFailure;
    private final AtomicLong completedLoads = new AtomicLong();
    private final AtomicLong completedSaves = new AtomicLong();
    private final AtomicLong loadFailures = new AtomicLong();
    private final AtomicLong saveFailures = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong backpressuredCommands = new AtomicLong();
    private final AtomicLong callbackDeliveryFailures = new AtomicLong();

    public ChatAgentPersistence(
            AgentIdentity identity,
            ActorRef self,
            AgentMessagePort messages,
            AsyncChatStateStore store,
            long ownerToken,
            int pendingCapacity,
            Supplier<ChatChannelSnapshot> snapshot,
            Consumer<ChatChannelSnapshot> restore,
            Runnable fence
    ) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.self = Objects.requireNonNull(self, "self");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.store = Objects.requireNonNull(store, "store");
        if (ownerToken < 0) {
            throw new IllegalArgumentException("ownerToken must not be negative");
        }
        if (pendingCapacity <= 0) {
            throw new IllegalArgumentException("pendingCapacity must be positive");
        }
        this.ownerToken = ownerToken;
        this.pendingCapacity = pendingCapacity;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.fence = Objects.requireNonNull(fence, "fence");
    }

    /**
     * 启动异步恢复。必须在 Agent 对外发布后立即调用，恢复完成前提交的业务会进入有界等待队列。
     */
    public void start() {
        store.load(identity, this::loadedFromIo, this::loadFailedFromIo);
    }

    /**
     * 在当前 Agent mailbox 内提交一次需要持久化确认的状态变更。
     */
    public void submit(Runnable mutation, Runnable committed, Consumer<Throwable> failed) {
        ActorContext.requireCurrent(self);
        PendingMutation command = new PendingMutation(mutation, committed, failed);
        if (state == State.FAILED) {
            failed.accept(terminalFailure);
            return;
        }
        if (state != State.READY) {
            enqueue(command);
            return;
        }
        persist(command);
    }

    public boolean ready() {
        return state == State.READY;
    }

    public int pendingCommands() {
        return pending.size();
    }

    public long persistenceVersion() {
        return persistenceVersion;
    }

    public ChatPersistenceStats stats() {
        State current = state;
        return new ChatPersistenceStats(
                1,
                current == State.RECOVERING ? 1 : 0,
                current == State.SAVING ? 1 : 0,
                current == State.FAILED ? 1 : 0,
                pendingCount,
                completedLoads.get(),
                completedSaves.get(),
                loadFailures.get(),
                saveFailures.get(),
                conflicts.get(),
                backpressuredCommands.get(),
                callbackDeliveryFailures.get()
        );
    }

    private void loadedFromIo(Optional<VersionedChatState> loaded) {
        postToMailbox(() -> completeLoad(loaded));
    }

    private void loadFailedFromIo(Throwable error) {
        loadFailures.incrementAndGet();
        postToMailbox(() -> failTerminal(error));
    }

    private void completeLoad(Optional<VersionedChatState> loaded) {
        ActorContext.requireCurrent(self);
        if (state != State.RECOVERING) {
            return;
        }
        if (loaded.isPresent()) {
            VersionedChatState persisted = loaded.orElseThrow();
            if (persisted.ownerToken() > ownerToken) {
                conflicts.incrementAndGet();
                fence.run();
                failTerminal(new ChatPersistenceConflictException(ChatStateSaveStatus.STALE_OWNER));
                return;
            }
            restore.accept(persisted.snapshot());
            persistenceVersion = persisted.version();
        }
        completedLoads.incrementAndGet();
        state = State.READY;
        drainNext();
    }

    private void persist(PendingMutation command) {
        ChatChannelSnapshot before = snapshot.get();
        try {
            command.mutation.run();
        } catch (Throwable error) {
            try {
                command.failed.accept(error);
            } finally {
                drainNext();
            }
            return;
        }
        ChatChannelSnapshot candidate = snapshot.get();
        if (before.equals(candidate)) {
            try {
                command.committed.run();
            } finally {
                drainNext();
            }
            return;
        }
        state = State.SAVING;
        store.save(
                identity,
                persistenceVersion,
                ownerToken,
                candidate,
                result -> postToMailbox(() -> completeSave(command, before, result)),
                error -> postToMailbox(() -> failSave(command, before, error))
        );
    }

    private void completeSave(
            PendingMutation command,
            ChatChannelSnapshot before,
            ChatStateSaveResult result
    ) {
        ActorContext.requireCurrent(self);
        if (state != State.SAVING) {
            return;
        }
        if (result.status() != ChatStateSaveStatus.APPLIED) {
            conflicts.incrementAndGet();
            restore.accept(before);
            ChatPersistenceConflictException conflict = new ChatPersistenceConflictException(result.status());
            command.failed.accept(conflict);
            fence.run();
            failTerminal(conflict);
            return;
        }
        persistenceVersion = result.current().orElseThrow().version();
        completedSaves.incrementAndGet();
        state = State.READY;
        try {
            command.committed.run();
        } finally {
            drainNext();
        }
    }

    private void failSave(PendingMutation command, ChatChannelSnapshot before, Throwable error) {
        ActorContext.requireCurrent(self);
        if (state != State.SAVING) {
            return;
        }
        restore.accept(before);
        saveFailures.incrementAndGet();
        state = State.READY;
        try {
            command.failed.accept(error);
        } finally {
            drainNext();
        }
    }

    private void enqueue(PendingMutation command) {
        if (pending.size() >= pendingCapacity) {
            backpressuredCommands.incrementAndGet();
            command.failed.accept(new ChatPersistenceBackpressureException(pendingCapacity));
            return;
        }
        pending.addLast(command);
        pendingCount = pending.size();
    }

    private void drainNext() {
        if (state != State.READY) {
            return;
        }
        PendingMutation next = pending.pollFirst();
        pendingCount = pending.size();
        if (next != null) {
            persist(next);
        }
    }

    private void failTerminal(Throwable error) {
        terminalFailure = Objects.requireNonNull(error, "error");
        state = State.FAILED;
        PendingMutation command;
        while ((command = pending.pollFirst()) != null) {
            try {
                command.failed.accept(error);
            } catch (RuntimeException ignored) {
            }
        }
        pendingCount = 0;
    }

    private void postToMailbox(Runnable task) {
        if (!messages.tryTellLocal(self, ActorTaskCategory.SYSTEM, ignored -> task.run()).accepted()) {
            callbackDeliveryFailures.incrementAndGet();
        }
    }

    private enum State {
        RECOVERING,
        READY,
        SAVING,
        FAILED
    }

    private record PendingMutation(Runnable mutation, Runnable committed, Consumer<Throwable> failed) {
        private PendingMutation {
            Objects.requireNonNull(mutation, "mutation");
            Objects.requireNonNull(committed, "committed");
            Objects.requireNonNull(failed, "failed");
        }
    }
}
