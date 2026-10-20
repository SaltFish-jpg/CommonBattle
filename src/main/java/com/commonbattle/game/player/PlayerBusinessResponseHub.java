package com.commonbattle.game.player;

import com.commonbattle.game.GameBusinessResultStatus;
import com.commonbattle.game.session.PlayerCommand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家业务响应等待中心。
 * 它把玩家邮箱内产生的结果路由给等待中的本服调用方或跨服 RPC responder。
 */
public final class PlayerBusinessResponseHub implements PlayerBusinessResultSink, PlayerBusinessResponseView {
    public static final int DEFAULT_COMPLETED_RESPONSE_WINDOW = 10_000;

    private final Map<Key, PendingResponse> callbacks = new ConcurrentHashMap<>();
    private final Map<Key, CompletedResponse> completed = new ConcurrentHashMap<>();
    private final ArrayDeque<Key> completedOrder = new ArrayDeque<>();
    private final PlayerBusinessResultSink fallback;
    private final Clock clock;
    private final int completedResponseWindow;
    private final PlayerBusinessResponseStore store;
    private final Map<Long, Object> playerLoadLocks = new ConcurrentHashMap<>();
    private final java.util.Set<Long> loadedPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicLong submittedResponses = new AtomicLong();
    private final AtomicLong completedResponses = new AtomicLong();
    private final AtomicLong cancelledResponses = new AtomicLong();
    private final AtomicLong timedOutResponses = new AtomicLong();
    private final AtomicLong fallbackResponses = new AtomicLong();
    private final AtomicLong sharedWaiters = new AtomicLong();
    private final AtomicLong replayedResponses = new AtomicLong();
    private final AtomicLong failedResponses = new AtomicLong();
    private final Map<String, AtomicLong> failedResponsesByCode = new ConcurrentHashMap<>();
    private final AtomicLong rejectedBusinessResults = new AtomicLong();
    private final Map<String, AtomicLong> rejectedBusinessResultsByCode = new ConcurrentHashMap<>();
    private final AtomicLong checkpointAttempts = new AtomicLong();
    private final AtomicLong checkpointSucceeded = new AtomicLong();
    private final AtomicLong checkpointFailures = new AtomicLong();
    private final AtomicLong storeLoadAttempts = new AtomicLong();
    private final AtomicLong storeLoadHits = new AtomicLong();
    private final AtomicLong storeLoadMisses = new AtomicLong();
    private final AtomicLong storeLoadFailures = new AtomicLong();

    public PlayerBusinessResponseHub() {
        this(PlayerBusinessResultSink.NOOP, Clock.systemUTC());
    }

    public PlayerBusinessResponseHub(PlayerBusinessResultSink fallback) {
        this(fallback, Clock.systemUTC());
    }

    public PlayerBusinessResponseHub(PlayerBusinessResultSink fallback, Clock clock) {
        this(fallback, clock, DEFAULT_COMPLETED_RESPONSE_WINDOW);
    }

    public PlayerBusinessResponseHub(PlayerBusinessResultSink fallback, Clock clock, int completedResponseWindow) {
        this(fallback, clock, completedResponseWindow, PlayerBusinessResponseStore.noop());
    }

    public PlayerBusinessResponseHub(
            PlayerBusinessResultSink fallback,
            Clock clock,
            int completedResponseWindow,
            PlayerBusinessResponseStore store
    ) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = Objects.requireNonNull(store, "store");
        if (completedResponseWindow <= 0) {
            throw new IllegalArgumentException("completedResponseWindow must be positive");
        }
        this.completedResponseWindow = completedResponseWindow;
    }

    public PlayerBusinessResponseRegistration expect(
            PlayerCommand command,
            PlayerBusinessResponseCallback callback
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(callback, "callback");
        ensureLoaded(command.playerId());
        Key key = Key.from(command);
        CompletedResponse cached = completed.get(key);
        if (cached != null) {
            replayedResponses.incrementAndGet();
            callback.completed(cached.response(), true);
            return PlayerBusinessResponseRegistration.NOOP;
        }
        RegistrationState state = new RegistrationState();
        PendingResponse waiter = callbacks.compute(key, (ignored, existing) -> {
            if (existing != null) {
                existing.add(callback);
                state.shared = true;
                return existing;
            }
            state.created = true;
            return new PendingResponse(command, callback, clock.instant());
        });
        if (state.shared) {
            sharedWaiters.incrementAndGet();
            return () -> {
                if (waiter.remove(callback)) {
                    callbacks.remove(key, waiter);
                    cancelledResponses.incrementAndGet();
                }
            };
        }
        if (state.created) {
            cached = completed.get(key);
            if (cached != null && callbacks.remove(key, waiter)) {
                replayedResponses.incrementAndGet();
                callback.completed(cached.response(), true);
                return PlayerBusinessResponseRegistration.NOOP;
            }
            submittedResponses.incrementAndGet();
        }
        return () -> {
            if (callbacks.remove(key, waiter)) {
                cancelledResponses.incrementAndGet();
            }
        };
    }

    public int pendingResponses() {
        return callbacks.size();
    }

    /**
     * 返回指定玩家仍由当前进程持有的等待响应数。
     */
    public int pendingResponses(long playerId) {
        return (int) callbacks.keySet().stream()
                .filter(key -> key.playerId() == playerId)
                .count();
    }

    /**
     * 按完成顺序导出单个玩家的响应缓存；等待中的回调属于当前进程，不允许进入迁移快照。
     */
    public PlayerBusinessResponseSnapshot snapshotForPlayer(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        ensureLoaded(playerId);
        synchronized (completedOrder) {
            List<PlayerBusinessResponse> responses = completedOrder.stream()
                    .filter(key -> key.playerId() == playerId)
                    .map(completed::get)
                    .filter(Objects::nonNull)
                    .map(CompletedResponse::response)
                    .toList();
            return new PlayerBusinessResponseSnapshot(playerId, responses);
        }
    }

    /**
     * 恢复迁移携带的完成响应，已有更新结果保持不变。
     */
    public void restore(PlayerBusinessResponseSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Object loadLock = playerLoadLocks.computeIfAbsent(snapshot.playerId(), ignored -> new Object());
        synchronized (loadLock) {
            restoreLoaded(snapshot);
            loadedPlayers.add(snapshot.playerId());
        }
    }

    /**
     * 在玩家状态与命令水位保存成功后持久化完成响应检查点。
     */
    public void checkpoint(long playerId) {
        checkpointAttempts.incrementAndGet();
        try {
            store.save(snapshotForPlayer(playerId));
            checkpointSucceeded.incrementAndGet();
        } catch (RuntimeException e) {
            checkpointFailures.incrementAndGet();
            throw e;
        }
    }

    private void restoreLoaded(PlayerBusinessResponseSnapshot snapshot) {
        synchronized (completedOrder) {
            for (PlayerBusinessResponse response : snapshot.responses()) {
                Key key = Key.from(response);
                if (!completed.containsKey(key)) {
                    completedOrder.addLast(key);
                    completed.put(key, new CompletedResponse(response));
                }
            }
            trimCompleted();
        }
    }

    /**
     * 目标 owner 确认接管后清理源进程的完成响应缓存。
     */
    public void forgetPlayer(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        synchronized (completedOrder) {
            completedOrder.removeIf(key -> key.playerId() == playerId);
            completed.keySet().removeIf(key -> key.playerId() == playerId);
        }
        loadedPlayers.add(playerId);
    }

    /**
     * 玩家正常钝化后释放内存缓存；下次激活时允许从已保存检查点重新加载。
     */
    public void releasePlayer(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        synchronized (completedOrder) {
            completedOrder.removeIf(key -> key.playerId() == playerId);
            completed.keySet().removeIf(key -> key.playerId() == playerId);
        }
        loadedPlayers.remove(playerId);
        playerLoadLocks.remove(playerId);
    }

    public Optional<PlayerBusinessResponse> cachedResponse(PlayerBusinessResponseQuery query) {
        Objects.requireNonNull(query, "query");
        ensureLoaded(query.playerId());
        CompletedResponse cached = completed.get(Key.from(query));
        return cached == null ? Optional.empty() : Optional.of(cached.response());
    }

    @Override
    public boolean canComplete(PlayerCommand command) {
        Objects.requireNonNull(command, "command");
        return callbacks.containsKey(Key.from(command));
    }

    public boolean timeout(PlayerCommand command, Duration timeout) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        PendingResponse waiter = callbacks.remove(Key.from(command));
        if (waiter == null) {
            return false;
        }
        timedOutResponses.incrementAndGet();
        PlayerBusinessResponse response = PlayerBusinessResponse.failure(
                command,
                new PlayerBusinessResponseTimeoutException(command, timeout)
        );
        recordFailure(response);
        remember(Key.from(command), response);
        waiter.complete(response);
        return true;
    }

    @Override
    public PlayerBusinessResponseStats stats() {
        return new PlayerBusinessResponseStats(
                callbacks.size(),
                submittedResponses.get(),
                completedResponses.get(),
                cancelledResponses.get(),
                timedOutResponses.get(),
                fallbackResponses.get(),
                sharedWaiters.get(),
                replayedResponses.get(),
                completed.size(),
                oldestPendingAgeMillis(),
                failedResponses.get(),
                failedResponsesByCode(),
                rejectedBusinessResults.get(),
                rejectedBusinessResultsByCode(),
                loadedPlayers.size(),
                checkpointAttempts.get(),
                checkpointSucceeded.get(),
                checkpointFailures.get(),
                storeLoadAttempts.get(),
                storeLoadHits.get(),
                storeLoadMisses.get(),
                storeLoadFailures.get()
        );
    }

    @Override
    public void completed(PlayerCommand command, PlayerBusinessResponse response) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(response, "response");
        ensureLoaded(command.playerId());
        recordFailure(response);
        recordBusinessResult(response);
        complete(command, response, true);
    }

    /**
     * 完成等待方但不写入幂等缓存；用于远程 owner 未确认接收的临时失败。
     */
    public void completedUncached(PlayerCommand command, PlayerBusinessResponse response) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(response, "response");
        recordFailure(response);
        recordBusinessResult(response);
        complete(command, response, false);
    }

    private void complete(PlayerCommand command, PlayerBusinessResponse response, boolean cache) {
        Key key = Key.from(command);
        if (cache) {
            remember(key, response);
        }
        PendingResponse waiter = callbacks.remove(key);
        if (waiter != null) {
            completedResponses.incrementAndGet();
            waiter.complete(response);
            return;
        }
        fallbackResponses.incrementAndGet();
        fallback.completed(command, response);
    }

    @Override
    public void succeeded(PlayerCommand command, Object response) {
        completed(command, PlayerBusinessResponse.success(command, response));
    }

    @Override
    public void failed(PlayerCommand command, Throwable error) {
        completed(command, PlayerBusinessResponse.failure(command, error));
    }

    private long oldestPendingAgeMillis() {
        Instant oldest = callbacks.values().stream()
                .map(PendingResponse::createdAt)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (oldest == null) {
            return 0;
        }
        long age = Duration.between(oldest, clock.instant()).toMillis();
        return Math.max(age, 0);
    }

    private void recordFailure(PlayerBusinessResponse response) {
        if (response.status() != PlayerBusinessResponseStatus.FAILED) {
            return;
        }
        failedResponses.incrementAndGet();
        failedResponsesByCode
                .computeIfAbsent(response.code(), ignored -> new AtomicLong())
                .incrementAndGet();
    }

    private void recordBusinessResult(PlayerBusinessResponse response) {
        if (response.status() != PlayerBusinessResponseStatus.SUCCESS) {
            return;
        }
        if (!(response.payload() instanceof GameBusinessResultStatus result) || !result.businessResultRejected()) {
            return;
        }
        rejectedBusinessResults.incrementAndGet();
        rejectedBusinessResultsByCode
                .computeIfAbsent(result.businessResultCode(), ignored -> new AtomicLong())
                .incrementAndGet();
    }

    private Map<String, Long> failedResponsesByCode() {
        Map<String, Long> result = new HashMap<>();
        failedResponsesByCode.forEach((code, count) -> result.put(code, count.get()));
        return result;
    }

    private Map<String, Long> rejectedBusinessResultsByCode() {
        Map<String, Long> result = new HashMap<>();
        rejectedBusinessResultsByCode.forEach((code, count) -> result.put(code, count.get()));
        return result;
    }

    private record Key(long playerId, String sessionId, long sessionEpoch, long sequence) {
        private static Key from(PlayerCommand command) {
            return new Key(command.playerId(), command.sessionId(), command.sessionEpoch(), command.sequence());
        }

        private static Key from(PlayerBusinessResponseQuery query) {
            return new Key(query.playerId(), query.sessionId(), query.sessionEpoch(), query.sequence());
        }

        private static Key from(PlayerBusinessResponse response) {
            return new Key(response.playerId(), response.sessionId(), response.sessionEpoch(), response.sequence());
        }
    }

    private void remember(Key key, PlayerBusinessResponse response) {
        synchronized (completedOrder) {
            if (!completed.containsKey(key)) {
                completedOrder.addLast(key);
            }
            completed.put(key, new CompletedResponse(response));
            trimCompleted();
        }
    }

    private void trimCompleted() {
        while (completedOrder.size() > completedResponseWindow) {
            Key removed = completedOrder.removeFirst();
            completed.remove(removed);
        }
    }

    private void ensureLoaded(long playerId) {
        if (loadedPlayers.contains(playerId)) {
            return;
        }
        Object loadLock = playerLoadLocks.computeIfAbsent(playerId, ignored -> new Object());
        synchronized (loadLock) {
            if (loadedPlayers.contains(playerId)) {
                return;
            }
            storeLoadAttempts.incrementAndGet();
            try {
                Optional<PlayerBusinessResponseSnapshot> stored = store.load(playerId);
                if (stored.isPresent()) {
                    restoreLoaded(stored.orElseThrow());
                    storeLoadHits.incrementAndGet();
                } else {
                    storeLoadMisses.incrementAndGet();
                }
                loadedPlayers.add(playerId);
            } catch (RuntimeException e) {
                storeLoadFailures.incrementAndGet();
                throw e;
            }
        }
    }

    private static final class RegistrationState {
        private boolean created;
        private boolean shared;
    }

    private static final class PendingResponse {
        private final PlayerCommand command;
        private final CopyOnWriteArrayList<PlayerBusinessResponseCallback> callbacks = new CopyOnWriteArrayList<>();
        private final Instant createdAt;

        private PendingResponse(PlayerCommand command, PlayerBusinessResponseCallback callback, Instant createdAt) {
            this.command = command;
            this.createdAt = createdAt;
            callbacks.add(callback);
        }

        private PlayerCommand command() {
            return command;
        }

        private Instant createdAt() {
            return createdAt;
        }

        private void add(PlayerBusinessResponseCallback callback) {
            callbacks.add(callback);
        }

        private boolean remove(PlayerBusinessResponseCallback callback) {
            callbacks.remove(callback);
            return callbacks.isEmpty();
        }

        private void complete(PlayerBusinessResponse response) {
            List<PlayerBusinessResponseCallback> snapshot = new ArrayList<>(callbacks);
            for (PlayerBusinessResponseCallback callback : snapshot) {
                callback.completed(response, false);
            }
        }
    }

    private record CompletedResponse(PlayerBusinessResponse response) {
    }
}
