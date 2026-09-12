package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final AtomicLong submittedResponses = new AtomicLong();
    private final AtomicLong completedResponses = new AtomicLong();
    private final AtomicLong cancelledResponses = new AtomicLong();
    private final AtomicLong timedOutResponses = new AtomicLong();
    private final AtomicLong fallbackResponses = new AtomicLong();
    private final AtomicLong sharedWaiters = new AtomicLong();
    private final AtomicLong replayedResponses = new AtomicLong();

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
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        remember(Key.from(command), command, response);
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
                oldestPendingAgeMillis()
        );
    }

    @Override
    public void completed(PlayerCommand command, PlayerBusinessResponse response) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(response, "response");
        Key key = Key.from(command);
        remember(key, command, response);
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

    private record Key(long playerId, String sessionId, long sessionEpoch, long sequence) {
        private static Key from(PlayerCommand command) {
            return new Key(command.playerId(), command.sessionId(), command.sessionEpoch(), command.sequence());
        }
    }

    private void remember(Key key, PlayerCommand command, PlayerBusinessResponse response) {
        synchronized (completedOrder) {
            if (!completed.containsKey(key)) {
                completedOrder.addLast(key);
            }
            completed.put(key, new CompletedResponse(command, response));
            while (completedOrder.size() > completedResponseWindow) {
                Key removed = completedOrder.removeFirst();
                completed.remove(removed);
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

    private record CompletedResponse(PlayerCommand command, PlayerBusinessResponse response) {
    }
}
