package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAgentPersistenceTest {
    @Test
    void recoveryAndSavesAreSerializedBackThroughMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        try (ActorSystem actors = new ActorSystem(executor, 64)) {
            ActorRef actor = actors.actor("chat-channel-world-1");
            RecordingAsyncStore store = new RecordingAsyncStore();
            AtomicReference<ChatChannelSnapshot> state = new AtomicReference<>(snapshot(0));
            AtomicInteger commits = new AtomicInteger();
            ChatAgentPersistence persistence = new ChatAgentPersistence(
                    ChatActorIds.channelIdentity("world:1"),
                    actor,
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    store,
                    7,
                    8,
                    state::get,
                    state::set,
                    () -> {
                    }
            );
            persistence.start();
            actors.send(actor, ignored -> persistence.submit(
                    () -> state.set(snapshot(1)),
                    commits::incrementAndGet,
                    error -> {
                        throw new AssertionError(error);
                    }
            ));

            executor.runNext();
            assertFalse(persistence.ready());
            assertEquals(1, persistence.pendingCommands());
            assertEquals(1, persistence.stats().recoveringAgents());
            assertEquals(1, persistence.stats().pendingCommands());

            store.completeLoad(Optional.of(new VersionedChatState(4, 6, snapshot(0))));
            executor.runNext();
            assertEquals(1, store.saves.size());
            assertEquals(4, store.saves.getFirst().expectedVersion);
            assertEquals(7, store.saves.getFirst().ownerToken);
            assertEquals(0, commits.get());
            assertEquals(1, persistence.stats().completedLoads());
            assertEquals(1, persistence.stats().savingAgents());

            store.completeSave(ChatStateSaveResult.applied(new VersionedChatState(5, 7, snapshot(1))));
            executor.runNext();

            assertTrue(persistence.ready());
            assertEquals(5, persistence.persistenceVersion());
            assertEquals(1, commits.get());
            assertEquals(1, state.get().revision());
            assertEquals(1, persistence.stats().completedSaves());
        }
    }

    @Test
    void staleOwnerConflictRollsBackAndFencesAgent() {
        RecordingExecutor executor = new RecordingExecutor();
        try (ActorSystem actors = new ActorSystem(executor, 64)) {
            ActorRef actor = actors.actor("chat-direct-1-2");
            RecordingAsyncStore store = new RecordingAsyncStore();
            AtomicReference<ChatChannelSnapshot> state = new AtomicReference<>(snapshot(0));
            AtomicInteger failures = new AtomicInteger();
            AtomicInteger fences = new AtomicInteger();
            ChatAgentPersistence persistence = new ChatAgentPersistence(
                    ChatActorIds.directIdentity(1, 2),
                    actor,
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    store,
                    3,
                    8,
                    state::get,
                    state::set,
                    fences::incrementAndGet
            );
            persistence.start();
            store.completeLoad(Optional.empty());
            executor.runNext();
            actors.send(actor, ignored -> persistence.submit(
                    () -> state.set(snapshot(1)),
                    () -> {
                    },
                    error -> failures.incrementAndGet()
            ));
            executor.runNext();

            store.completeSave(ChatStateSaveResult.staleOwner(new VersionedChatState(2, 4, snapshot(2))));
            executor.runNext();

            assertEquals(0, state.get().revision());
            assertEquals(1, failures.get());
            assertEquals(1, fences.get());
            assertFalse(persistence.ready());
            assertEquals(1, persistence.stats().conflicts());
            assertEquals(1, persistence.stats().failedAgents());
        }
    }

    @Test
    void countsCallbackThatCannotReturnToRetiredMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorRef actor = actors.actor("chat-channel-retired");
        RecordingAsyncStore store = new RecordingAsyncStore();
        ChatAgentPersistence persistence = new ChatAgentPersistence(
                ChatActorIds.channelIdentity("world:retired"),
                actor,
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                store,
                1,
                1,
                () -> snapshot(0),
                ignored -> {
                },
                () -> {
                }
        );
        persistence.start();
        actors.close();

        store.completeLoad(Optional.empty());

        assertEquals(1, persistence.stats().callbackDeliveryFailures());
        assertEquals(1, persistence.stats().recoveringAgents());
    }

    private static ChatChannelSnapshot snapshot(long revision) {
        return new ChatChannelSnapshot("world:1", Set.of(), List.of(), revision);
    }

    private static final class RecordingAsyncStore implements AsyncChatStateStore {
        private Consumer<Optional<VersionedChatState>> loadSuccess;
        private Consumer<ChatStateSaveResult> saveSuccess;
        private final List<SaveCall> saves = new ArrayList<>();

        @Override
        public void load(
                AgentIdentity identity,
                Consumer<Optional<VersionedChatState>> success,
                Consumer<Throwable> failure
        ) {
            loadSuccess = success;
        }

        @Override
        public void save(
                AgentIdentity identity,
                long expectedVersion,
                long ownerToken,
                ChatChannelSnapshot snapshot,
                Consumer<ChatStateSaveResult> success,
                Consumer<Throwable> failure
        ) {
            saves.add(new SaveCall(expectedVersion, ownerToken));
            saveSuccess = success;
        }

        void completeLoad(Optional<VersionedChatState> state) {
            loadSuccess.accept(state);
        }

        void completeSave(ChatStateSaveResult result) {
            saveSuccess.accept(result);
        }
    }

    private record SaveCall(long expectedVersion, long ownerToken) {
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
