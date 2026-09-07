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
 * 它把 VersionedEvent 包装为 EventPublishRequest 后交给 PayloadCodecRegistry 编码，便于重启后恢复补发。
 */
public final class SerializedVersionedEventOutbox implements VersionedEventOutbox {
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
        PendingVersionedEvent pending = new PendingVersionedEvent(store.nextId(), event, clock.instant(), 0);
        store.save(pending.id(), encodePending(pending));
        return pending;
    }

    @Override
    public List<PendingVersionedEvent> pending() {
        return store.loadAll().stream()
                .map(this::decodePending)
                .sorted(Comparator.comparingLong(PendingVersionedEvent::id))
                .toList();
    }

    @Override
    public void markPublished(long outboxId) {
        store.delete(outboxId);
    }

    @Override
    public void markAttemptFailed(long outboxId) {
        store.load(outboxId)
                .map(this::decodePending)
                .ifPresent(entry -> store.save(outboxId, encodePending(new PendingVersionedEvent(
                        entry.id(),
                        entry.event(),
                        entry.createdAt(),
                        entry.attempts() + 1
                ))));
    }

    private byte[] encodePending(PendingVersionedEvent pending) {
        EncodedPayload payload = registry.encode(new EventPublishRequest(pending.event()));
        int size = CodedOutputStream.computeInt64Size(1, pending.id())
                + CodedOutputStream.computeInt64Size(2, pending.createdAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(3, pending.createdAt().getNano())
                + CodedOutputStream.computeInt32Size(4, pending.attempts())
                + CodedOutputStream.computeStringSize(5, payload.codecName())
                + CodedOutputStream.computeStringSize(6, payload.typeName())
                + CodedOutputStream.computeByteArraySize(7, payload.bytes());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, pending.id());
            output.writeInt64(2, pending.createdAt().getEpochSecond());
            output.writeInt32(3, pending.createdAt().getNano());
            output.writeInt32(4, pending.attempts());
            output.writeString(5, payload.codecName());
            output.writeString(6, payload.typeName());
            output.writeByteArray(7, payload.bytes());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode pending versioned event", e);
        }
    }

    private PendingVersionedEvent decodePending(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long id = 0;
        long epochSecond = 0;
        int nano = 0;
        int attempts = 0;
        String codecName = "";
        String typeName = "";
        byte[] payloadBytes = new byte[0];
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> id = input.readInt64();
                    case 2 -> epochSecond = input.readInt64();
                    case 3 -> nano = input.readInt32();
                    case 4 -> attempts = input.readInt32();
                    case 5 -> codecName = input.readString();
                    case 6 -> typeName = input.readString();
                    case 7 -> payloadBytes = input.readByteArray();
                    default -> input.skipField(tag);
                }
            }
            Object decoded = registry.decode(codecName, typeName, payloadBytes);
            if (!(decoded instanceof EventPublishRequest request) || !(request.event() instanceof VersionedEvent event)) {
                throw new IllegalStateException("Outbox payload is not a versioned event publish request");
            }
            return new PendingVersionedEvent(id, event, Instant.ofEpochSecond(epochSecond, nano), attempts);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode pending versioned event", e);
        }
    }
}
