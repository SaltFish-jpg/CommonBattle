package com.commonbattle.game.event;

import com.commonbattle.cluster.event.EventPublishRequest;
import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 序列化事件 outbox。
 * 事件、重试时间和死信状态都写入字节存储，进程重启后仍可继续补发或人工重放。
 */
public final class SerializedVersionedEventOutbox implements VersionedEventOutbox {
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 1_024;

    private final VersionedEventOutboxBytesStore store;
    private final PayloadCodecRegistry registry;
    private final Clock clock;

    public SerializedVersionedEventOutbox(
            VersionedEventOutboxBytesStore store,
            PayloadCodecRegistry registry,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PendingVersionedEvent append(VersionedEvent event) {
        Objects.requireNonNull(event, "event");
        Instant now = clock.instant();
        StoredEvent stored = StoredEvent.pending(store.nextId(), event, now);
        store.save(stored.id(), encode(stored));
        return stored.pending();
    }

    @Override
    public List<PendingVersionedEvent> pending() {
        return storedEvents().stream()
                .filter(entry -> !entry.deadLettered())
                .map(StoredEvent::pending)
                .sorted(Comparator.comparingLong(PendingVersionedEvent::id))
                .toList();
    }

    @Override
    public List<PendingVersionedEvent> ready(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return storedEvents().stream()
                .filter(entry -> !entry.deadLettered() && !entry.nextAttemptAt().isAfter(now))
                .map(StoredEvent::pending)
                .sorted(Comparator.comparingLong(PendingVersionedEvent::id))
                .limit(limit)
                .toList();
    }

    @Override
    public List<DeadLetteredVersionedEvent> deadLetters() {
        return storedEvents().stream()
                .filter(StoredEvent::deadLettered)
                .map(StoredEvent::deadLetter)
                .sorted(Comparator.comparingLong(DeadLetteredVersionedEvent::id))
                .toList();
    }

    @Override
    public void markPublished(long outboxId) {
        store.delete(outboxId);
    }

    @Override
    public void markAttemptFailed(long outboxId) {
        Instant now = clock.instant();
        markAttemptFailed(
                outboxId,
                new IllegalStateException("event publish failed"),
                now,
                now,
                Integer.MAX_VALUE
        );
    }

    @Override
    public synchronized OutboxFailureResult markAttemptFailed(
            long outboxId,
            Throwable failure,
            Instant failedAt,
            Instant nextAttemptAt,
            int maxAttempts
    ) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        StoredEvent current = store.load(outboxId).map(this::decode).orElse(null);
        if (current == null || current.deadLettered()) {
            return OutboxFailureResult.ENTRY_NOT_FOUND;
        }
        int attempts = current.attempts() + 1;
        boolean deadLettered = attempts >= maxAttempts;
        StoredEvent updated = new StoredEvent(
                current.id(),
                current.event(),
                current.createdAt(),
                attempts,
                nextAttemptAt,
                failureMessage(failure),
                deadLettered,
                deadLettered ? failedAt : Instant.EPOCH
        );
        store.save(outboxId, encode(updated));
        return deadLettered ? OutboxFailureResult.DEAD_LETTERED : OutboxFailureResult.RETRY_SCHEDULED;
    }

    @Override
    public synchronized boolean redriveDeadLetter(long outboxId, Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        StoredEvent current = store.load(outboxId).map(this::decode).orElse(null);
        if (current == null || !current.deadLettered()) {
            return false;
        }
        StoredEvent pending = new StoredEvent(
                current.id(), current.event(), current.createdAt(), 0, nextAttemptAt, "", false, Instant.EPOCH
        );
        store.save(outboxId, encode(pending));
        return true;
    }

    private List<StoredEvent> storedEvents() {
        return store.loadAll().stream().map(this::decode).toList();
    }

    private byte[] encode(StoredEvent stored) {
        EncodedPayload payload = registry.encode(new EventPublishRequest(stored.event()));
        int size = CodedOutputStream.computeInt64Size(1, stored.id())
                + CodedOutputStream.computeInt64Size(2, stored.createdAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(3, stored.createdAt().getNano())
                + CodedOutputStream.computeInt32Size(4, stored.attempts())
                + CodedOutputStream.computeStringSize(5, payload.codecName())
                + CodedOutputStream.computeStringSize(6, payload.typeName())
                + CodedOutputStream.computeByteArraySize(7, payload.bytes())
                + CodedOutputStream.computeInt64Size(8, stored.nextAttemptAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(9, stored.nextAttemptAt().getNano())
                + CodedOutputStream.computeStringSize(10, stored.lastFailure())
                + CodedOutputStream.computeBoolSize(11, stored.deadLettered())
                + CodedOutputStream.computeInt64Size(12, stored.deadLetteredAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(13, stored.deadLetteredAt().getNano());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, stored.id());
            output.writeInt64(2, stored.createdAt().getEpochSecond());
            output.writeInt32(3, stored.createdAt().getNano());
            output.writeInt32(4, stored.attempts());
            output.writeString(5, payload.codecName());
            output.writeString(6, payload.typeName());
            output.writeByteArray(7, payload.bytes());
            output.writeInt64(8, stored.nextAttemptAt().getEpochSecond());
            output.writeInt32(9, stored.nextAttemptAt().getNano());
            output.writeString(10, stored.lastFailure());
            output.writeBool(11, stored.deadLettered());
            output.writeInt64(12, stored.deadLetteredAt().getEpochSecond());
            output.writeInt32(13, stored.deadLetteredAt().getNano());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode versioned event outbox entry", e);
        }
    }

    private StoredEvent decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long id = 0;
        long createdEpochSecond = 0;
        int createdNano = 0;
        int attempts = 0;
        String codecName = "";
        String typeName = "";
        byte[] payloadBytes = new byte[0];
        long nextAttemptEpochSecond = 0;
        int nextAttemptNano = 0;
        boolean nextAttemptPresent = false;
        String lastFailure = "";
        boolean deadLettered = false;
        long deadLetteredEpochSecond = 0;
        int deadLetteredNano = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> id = input.readInt64();
                    case 2 -> createdEpochSecond = input.readInt64();
                    case 3 -> createdNano = input.readInt32();
                    case 4 -> attempts = input.readInt32();
                    case 5 -> codecName = input.readString();
                    case 6 -> typeName = input.readString();
                    case 7 -> payloadBytes = input.readByteArray();
                    case 8 -> {
                        nextAttemptEpochSecond = input.readInt64();
                        nextAttemptPresent = true;
                    }
                    case 9 -> nextAttemptNano = input.readInt32();
                    case 10 -> lastFailure = input.readString();
                    case 11 -> deadLettered = input.readBool();
                    case 12 -> deadLetteredEpochSecond = input.readInt64();
                    case 13 -> deadLetteredNano = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            Object decoded = registry.decode(codecName, typeName, payloadBytes);
            if (!(decoded instanceof EventPublishRequest request) || !(request.event() instanceof VersionedEvent event)) {
                throw new IllegalStateException("Outbox payload is not a versioned event publish request");
            }
            Instant createdAt = Instant.ofEpochSecond(createdEpochSecond, createdNano);
            Instant nextAttemptAt = nextAttemptPresent
                    ? Instant.ofEpochSecond(nextAttemptEpochSecond, nextAttemptNano)
                    : createdAt;
            Instant deadLetteredAt = Instant.ofEpochSecond(deadLetteredEpochSecond, deadLetteredNano);
            return new StoredEvent(
                    id, event, createdAt, attempts, nextAttemptAt, lastFailure, deadLettered, deadLetteredAt
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode versioned event outbox entry", e);
        }
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        String value = message == null || message.isBlank() ? failure.getClass().getName() : message;
        return value.length() <= MAX_FAILURE_MESSAGE_LENGTH ? value : value.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
    }

    private record StoredEvent(
            long id,
            VersionedEvent event,
            Instant createdAt,
            int attempts,
            Instant nextAttemptAt,
            String lastFailure,
            boolean deadLettered,
            Instant deadLetteredAt
    ) {
        private static StoredEvent pending(long id, VersionedEvent event, Instant createdAt) {
            return new StoredEvent(id, event, createdAt, 0, createdAt, "", false, Instant.EPOCH);
        }

        private PendingVersionedEvent pending() {
            return new PendingVersionedEvent(id, event, createdAt, attempts, nextAttemptAt, lastFailure);
        }

        private DeadLetteredVersionedEvent deadLetter() {
            return new DeadLetteredVersionedEvent(id, event, createdAt, attempts, deadLetteredAt, lastFailure);
        }
    }
}
