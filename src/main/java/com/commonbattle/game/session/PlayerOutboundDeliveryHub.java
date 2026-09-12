package com.commonbattle.game.session;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家客户端出站投递中心。
 * 它不拥有玩家业务状态，只维护当前连接写出器和短期离线缓冲，适合 Game、Chat、Scene 等服务复用。
 */
public final class PlayerOutboundDeliveryHub implements PlayerOutboundDeliveryView {
    private final PlayerSessionRegistry sessions;
    private final Clock clock;
    private final int maxOfflineMessagesPerPlayer;
    private final int maxPendingAckMessagesPerPlayer;
    private final PlayerDeliveryOverflowStrategy overflowStrategy;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<Long, ArrayDeque<PlayerOutboundMessage>> offline = new ConcurrentHashMap<>();
    private final Map<Long, ArrayDeque<PlayerOutboundMessage>> pendingAcks = new ConcurrentHashMap<>();
    private final AtomicLong sequences = new AtomicLong();
    private final AtomicLong onlineDeliveries = new AtomicLong();
    private final AtomicLong offlineQueuedDeliveries = new AtomicLong();
    private final AtomicLong droppedDeliveries = new AtomicLong();
    private final AtomicLong coalescedDeliveries = new AtomicLong();
    private final AtomicLong failedOnlineDeliveries = new AtomicLong();
    private final AtomicLong ackedDeliveries = new AtomicLong();

    public PlayerOutboundDeliveryHub(
            PlayerSessionRegistry sessions,
            Clock clock,
            int maxOfflineMessagesPerPlayer,
            PlayerDeliveryOverflowStrategy overflowStrategy
    ) {
        this(sessions, clock, maxOfflineMessagesPerPlayer, maxOfflineMessagesPerPlayer, overflowStrategy);
    }

    public PlayerOutboundDeliveryHub(
            PlayerSessionRegistry sessions,
            Clock clock,
            int maxOfflineMessagesPerPlayer,
            int maxPendingAckMessagesPerPlayer,
            PlayerDeliveryOverflowStrategy overflowStrategy
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxOfflineMessagesPerPlayer <= 0) {
            throw new IllegalArgumentException("maxOfflineMessagesPerPlayer must be positive");
        }
        if (maxPendingAckMessagesPerPlayer <= 0) {
            throw new IllegalArgumentException("maxPendingAckMessagesPerPlayer must be positive");
        }
        this.maxOfflineMessagesPerPlayer = maxOfflineMessagesPerPlayer;
        this.maxPendingAckMessagesPerPlayer = maxPendingAckMessagesPerPlayer;
        this.overflowStrategy = Objects.requireNonNull(overflowStrategy, "overflowStrategy");
    }

    public boolean connect(PlayerSession session, PlayerOutboundWriter writer) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(writer, "writer");
        if (!sessions.isCurrent(session.playerId(), session.sessionId(), session.epoch())) {
            return false;
        }
        removePlayerConnections(session.playerId());
        connections.put(sessionKey(session), new Connection(session, writer));
        return true;
    }

    public void disconnect(PlayerSession session) {
        Objects.requireNonNull(session, "session");
        connections.remove(sessionKey(session));
    }

    public PlayerOutboundDeliveryResult deliver(PlayerOutboundEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        PlayerOutboundDeliveryResult result = PlayerOutboundDeliveryResult.empty();
        for (long recipient : envelope.recipients()) {
            result = result.plus(deliverOne(
                    recipient,
                    envelope.topic(),
                    envelope.payload(),
                    envelope.mode(),
                    envelope.coalesceKey()
            ));
        }
        onlineDeliveries.addAndGet(result.onlineDeliveries());
        offlineQueuedDeliveries.addAndGet(result.offlineQueuedDeliveries());
        droppedDeliveries.addAndGet(result.droppedDeliveries());
        failedOnlineDeliveries.addAndGet(result.failedOnlineDeliveries());
        return result;
    }

    public List<PlayerOutboundMessage> pendingOffline(long playerId) {
        ArrayDeque<PlayerOutboundMessage> messages = offline.get(playerId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    public List<PlayerOutboundMessage> pendingAck(long playerId) {
        ArrayDeque<PlayerOutboundMessage> messages = pendingAcks.get(playerId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    public long acknowledge(PlayerSession session, long acknowledgedSequence) {
        Objects.requireNonNull(session, "session");
        if (acknowledgedSequence <= 0) {
            throw new IllegalArgumentException("acknowledgedSequence must be positive");
        }
        if (!sessions.isCurrent(session.playerId(), session.sessionId(), session.epoch())) {
            return 0;
        }
        ArrayDeque<PlayerOutboundMessage> messages = pendingAcks.get(session.playerId());
        if (messages == null) {
            return 0;
        }
        long acknowledged = 0;
        synchronized (messages) {
            while (!messages.isEmpty() && messages.peekFirst().sequence() <= acknowledgedSequence) {
                messages.removeFirst();
                acknowledged++;
            }
        }
        ackedDeliveries.addAndGet(acknowledged);
        return acknowledged;
    }

    public PlayerOutboundAckStatus ackStatus(
            PlayerSession session,
            int maxPendingAckMessages,
            Duration maxPendingAckAge
    ) {
        Objects.requireNonNull(session, "session");
        maxPendingAckAge = Objects.requireNonNull(maxPendingAckAge, "maxPendingAckAge");
        if (maxPendingAckMessages <= 0) {
            throw new IllegalArgumentException("maxPendingAckMessages must be positive");
        }
        if (maxPendingAckAge.isNegative()) {
            throw new IllegalArgumentException("maxPendingAckAge must not be negative");
        }
        if (!sessions.isCurrent(session.playerId(), session.sessionId(), session.epoch())) {
            return new PlayerOutboundAckStatus(false, 0, 0, false, false);
        }
        ArrayDeque<PlayerOutboundMessage> messages = pendingAcks.get(session.playerId());
        if (messages == null) {
            return new PlayerOutboundAckStatus(true, 0, 0, false, false);
        }
        synchronized (messages) {
            long pending = messages.size();
            long oldestAge = messages.isEmpty()
                    ? 0
                    : Math.max(0, Duration.between(messages.peekFirst().createdAt(), clock.instant()).toMillis());
            boolean overLimit = pending >= maxPendingAckMessages;
            boolean timedOut = !maxPendingAckAge.isZero() && oldestAge > maxPendingAckAge.toMillis();
            return new PlayerOutboundAckStatus(true, pending, oldestAge, overLimit, timedOut);
        }
    }

    public List<PlayerOutboundMessage> drainOffline(long playerId, int maxCount) {
        if (maxCount <= 0) {
            throw new IllegalArgumentException("maxCount must be positive");
        }
        ArrayDeque<PlayerOutboundMessage> messages = offline.get(playerId);
        if (messages == null) {
            return List.of();
        }
        List<PlayerOutboundMessage> drained = new ArrayList<>();
        synchronized (messages) {
            while (!messages.isEmpty() && drained.size() < maxCount) {
                drained.add(messages.removeFirst());
            }
        }
        return List.copyOf(drained);
    }

    public PlayerOutboundDeliveryResult flushPendingAck(PlayerSession session, int maxCount) {
        Objects.requireNonNull(session, "session");
        if (maxCount <= 0) {
            throw new IllegalArgumentException("maxCount must be positive");
        }
        if (!sessions.isCurrent(session.playerId(), session.sessionId(), session.epoch())) {
            return PlayerOutboundDeliveryResult.empty();
        }
        Connection connection = connections.get(sessionKey(session));
        if (connection == null) {
            return PlayerOutboundDeliveryResult.empty();
        }
        List<PlayerOutboundMessage> messages = pendingAck(session.playerId()).stream()
                .limit(maxCount)
                .toList();
        long delivered = 0;
        long failed = 0;
        for (PlayerOutboundMessage message : messages) {
            try {
                if (connection.writer().write(message)) {
                    delivered++;
                } else {
                    failed++;
                }
            } catch (RuntimeException e) {
                failed++;
            }
        }
        onlineDeliveries.addAndGet(delivered);
        failedOnlineDeliveries.addAndGet(failed);
        return new PlayerOutboundDeliveryResult(delivered, 0, 0, failed);
    }

    public PlayerOutboundDeliveryResult flushOffline(PlayerSession session, int maxCount) {
        Objects.requireNonNull(session, "session");
        if (maxCount <= 0) {
            throw new IllegalArgumentException("maxCount must be positive");
        }
        if (!sessions.isCurrent(session.playerId(), session.sessionId(), session.epoch())) {
            return PlayerOutboundDeliveryResult.empty();
        }
        Connection connection = connections.get(sessionKey(session));
        if (connection == null) {
            return PlayerOutboundDeliveryResult.empty();
        }
        List<PlayerOutboundMessage> messages = drainOffline(session.playerId(), maxCount);
        long delivered = 0;
        long requeued = 0;
        long dropped = 0;
        long failed = 0;
        for (PlayerOutboundMessage message : messages) {
            try {
                if (connection.writer().write(message)) {
                    delivered++;
                    PendingAckOfferResult pending = queuePendingAck(message);
                    dropped += pending.dropped() ? 1 : 0;
                } else {
                    failed++;
                    OfflineOfferResult offer = queueOffline(message);
                    requeued += offer.queued() ? 1 : 0;
                    dropped += offer.dropped() ? 1 : 0;
                }
            } catch (RuntimeException e) {
                failed++;
                OfflineOfferResult offer = queueOffline(message);
                requeued += offer.queued() ? 1 : 0;
                dropped += offer.dropped() ? 1 : 0;
            }
        }
        onlineDeliveries.addAndGet(delivered);
        offlineQueuedDeliveries.addAndGet(requeued);
        droppedDeliveries.addAndGet(dropped);
        failedOnlineDeliveries.addAndGet(failed);
        return new PlayerOutboundDeliveryResult(delivered, requeued, dropped, failed);
    }

    @Override
    public PlayerOutboundDeliveryStats deliveryStats() {
        long offlinePlayers = 0;
        long pendingMessages = 0;
        long pendingAckPlayers = 0;
        long pendingAckMessages = 0;
        long oldestPendingAckAgeMillis = 0;
        for (ArrayDeque<PlayerOutboundMessage> messages : offline.values()) {
            synchronized (messages) {
                if (!messages.isEmpty()) {
                    offlinePlayers++;
                    pendingMessages += messages.size();
                }
            }
        }
        for (ArrayDeque<PlayerOutboundMessage> messages : pendingAcks.values()) {
            synchronized (messages) {
                if (!messages.isEmpty()) {
                    pendingAckPlayers++;
                    oldestPendingAckAgeMillis = Math.max(
                            oldestPendingAckAgeMillis,
                            Math.max(0, Duration.between(messages.peekFirst().createdAt(), clock.instant()).toMillis())
                    );
                }
                pendingAckMessages += messages.size();
            }
        }
        return new PlayerOutboundDeliveryStats(
                connections.size(),
                offlinePlayers,
                pendingMessages,
                pendingAckPlayers,
                pendingAckMessages,
                oldestPendingAckAgeMillis,
                onlineDeliveries.get(),
                offlineQueuedDeliveries.get(),
                droppedDeliveries.get(),
                coalescedDeliveries.get(),
                failedOnlineDeliveries.get(),
                ackedDeliveries.get()
        );
    }

    private PlayerOutboundDeliveryResult deliverOne(
            long playerId,
            String topic,
            Object payload,
            PlayerOutboundDeliveryMode mode,
            String coalesceKey
    ) {
        PlayerOutboundMessage message = new PlayerOutboundMessage(
                playerId,
                topic,
                payload,
                sequences.incrementAndGet(),
                clock.instant(),
                mode,
                coalesceKey
        );
        Connection connection = currentConnection(playerId);
        if (connection == null) {
            if (mode == PlayerOutboundDeliveryMode.BEST_EFFORT) {
                return new PlayerOutboundDeliveryResult(0, 0, 1, 0);
            }
            OfflineOfferResult offer = queueOffline(message);
            return new PlayerOutboundDeliveryResult(0, offer.queued() ? 1 : 0, offer.dropped() ? 1 : 0, 0);
        }
        try {
            if (connection.writer().write(message)) {
                if (mode == PlayerOutboundDeliveryMode.BEST_EFFORT) {
                    return new PlayerOutboundDeliveryResult(1, 0, 0, 0);
                }
                PendingAckOfferResult pending = queuePendingAck(message);
                return new PlayerOutboundDeliveryResult(1, 0, pending.dropped() ? 1 : 0, 0);
            }
            if (mode == PlayerOutboundDeliveryMode.BEST_EFFORT) {
                return new PlayerOutboundDeliveryResult(0, 0, 1, 1);
            }
            OfflineOfferResult offer = queueOffline(message);
            return new PlayerOutboundDeliveryResult(0, offer.queued() ? 1 : 0, offer.dropped() ? 1 : 0, 1);
        } catch (RuntimeException e) {
            if (mode == PlayerOutboundDeliveryMode.BEST_EFFORT) {
                return new PlayerOutboundDeliveryResult(0, 0, 1, 1);
            }
            OfflineOfferResult offer = queueOffline(message);
            return new PlayerOutboundDeliveryResult(0, offer.queued() ? 1 : 0, offer.dropped() ? 1 : 0, 1);
        }
    }

    private Connection currentConnection(long playerId) {
        return sessions.current(playerId)
                .filter(session -> connections.containsKey(sessionKey(session)))
                .map(session -> connections.get(sessionKey(session)))
                .filter(connection -> sessions.isCurrent(
                        connection.session().playerId(),
                        connection.session().sessionId(),
                        connection.session().epoch()
                ))
                .orElse(null);
    }

    private OfflineOfferResult queueOffline(PlayerOutboundMessage message) {
        ArrayDeque<PlayerOutboundMessage> messages =
                offline.computeIfAbsent(message.playerId(), ignored -> new ArrayDeque<>());
        synchronized (messages) {
            if (message.mode() == PlayerOutboundDeliveryMode.COALESCING && replaceCoalesced(messages, message)) {
                coalescedDeliveries.incrementAndGet();
                return new OfflineOfferResult(true, false);
            }
            if (messages.size() < maxOfflineMessagesPerPlayer) {
                messages.addLast(message);
                return new OfflineOfferResult(true, false);
            }
            if (overflowStrategy == PlayerDeliveryOverflowStrategy.DROP_NEWEST) {
                return new OfflineOfferResult(false, true);
            }
            messages.removeFirst();
            messages.addLast(message);
            return new OfflineOfferResult(true, true);
        }
    }

    private PendingAckOfferResult queuePendingAck(PlayerOutboundMessage message) {
        ArrayDeque<PlayerOutboundMessage> messages =
                pendingAcks.computeIfAbsent(message.playerId(), ignored -> new ArrayDeque<>());
        synchronized (messages) {
            if (message.mode() == PlayerOutboundDeliveryMode.COALESCING && replaceCoalesced(messages, message)) {
                coalescedDeliveries.incrementAndGet();
                return new PendingAckOfferResult(false);
            }
            if (messages.size() < maxPendingAckMessagesPerPlayer) {
                messages.addLast(message);
                return new PendingAckOfferResult(false);
            }
            if (overflowStrategy == PlayerDeliveryOverflowStrategy.DROP_NEWEST) {
                return new PendingAckOfferResult(true);
            }
            messages.removeFirst();
            messages.addLast(message);
            return new PendingAckOfferResult(true);
        }
    }

    private static boolean replaceCoalesced(ArrayDeque<PlayerOutboundMessage> messages, PlayerOutboundMessage latest) {
        if (messages.isEmpty()) {
            return false;
        }
        List<PlayerOutboundMessage> replaced = new ArrayList<>(messages.size());
        boolean found = false;
        while (!messages.isEmpty()) {
            PlayerOutboundMessage current = messages.removeFirst();
            if (!found && sameCoalesceSlot(current, latest)) {
                replaced.add(latest);
                found = true;
            } else {
                replaced.add(current);
            }
        }
        messages.addAll(replaced);
        return found;
    }

    private static boolean sameCoalesceSlot(PlayerOutboundMessage current, PlayerOutboundMessage latest) {
        return current.mode() == PlayerOutboundDeliveryMode.COALESCING
                && current.playerId() == latest.playerId()
                && current.topic().equals(latest.topic())
                && current.coalesceKey().equals(latest.coalesceKey());
    }

    private static String sessionKey(PlayerSession session) {
        return session.playerId() + ":" + session.sessionId() + ":" + session.epoch();
    }

    private void removePlayerConnections(long playerId) {
        connections.entrySet().removeIf(entry -> entry.getValue().session().playerId() == playerId);
    }

    private record Connection(PlayerSession session, PlayerOutboundWriter writer) {
    }

    private record OfflineOfferResult(boolean queued, boolean dropped) {
    }

    private record PendingAckOfferResult(boolean dropped) {
    }
}
