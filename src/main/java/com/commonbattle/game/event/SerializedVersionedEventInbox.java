package com.commonbattle.game.event;

import com.commonbattle.cluster.event.EventPublishRequest;
import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 可恢复的序列化版本事件 inbox。
 * 事件内容、重试时间和死信状态使用显式 Protobuf wire 字段保存，兼容新增字段的滚动升级。
 */
public final class SerializedVersionedEventInbox implements VersionedEventInbox {
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 1_024;

    private final VersionedEventInboxBytesStore store;
    private final PayloadCodecRegistry registry;
    private final String consumerNamespace;

    public SerializedVersionedEventInbox(VersionedEventInboxBytesStore store, PayloadCodecRegistry registry) {
        this(store, registry, "default");
    }

    public SerializedVersionedEventInbox(
            VersionedEventInboxBytesStore store,
            PayloadCodecRegistry registry,
            String consumerNamespace
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.consumerNamespace = Objects.requireNonNull(consumerNamespace, "consumerNamespace");
        if (consumerNamespace.isBlank()) {
            throw new IllegalArgumentException("consumerNamespace must not be blank");
        }
    }

    @Override
    public synchronized EventInboxAppendStatus appendIfAbsent(VersionedEvent event, Instant receivedAt) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(receivedAt, "receivedAt");
        StoredEntry appended = StoredEntry.pending(consumerNamespace, event, receivedAt);
        String storageKey = storageKey(appended.key());
        if (store.insertIfAbsent(storageKey, encode(appended))) {
            return EventInboxAppendStatus.APPENDED;
        }
        StoredEntry existing = store.load(storageKey).map(this::decode)
                .orElseThrow(() -> new IllegalStateException("Event inbox entry disappeared after duplicate insert"));
        requireMatchingKey(appended.key(), existing.key());
        return existing.deadLettered()
                ? EventInboxAppendStatus.DEAD_LETTERED
                : EventInboxAppendStatus.ALREADY_PENDING;
    }

    @Override
    public Optional<PendingEventInboxEntry> nextReady(String topic, String ownerKey, Instant now) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(ownerKey, "ownerKey");
        Objects.requireNonNull(now, "now");
        List<StoredEntry> entries = entries();
        if (blocked(entries, topic, ownerKey)) {
            return Optional.empty();
        }
        return entries.stream()
                .filter(entry -> !entry.deadLettered())
                .filter(entry -> entry.key().topic().equals(topic) && entry.key().ownerKey().equals(ownerKey))
                .min(Comparator.comparingLong(entry -> entry.key().revision()))
                .filter(entry -> !entry.nextAttemptAt().isAfter(now))
                .map(StoredEntry::pending);
    }

    @Override
    public List<PendingEventInboxEntry> ready(String topic, Instant now, int limit) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(now, "now");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<StoredEntry> entries = entries();
        return entries.stream()
                .filter(entry -> !entry.deadLettered() && entry.key().topic().equals(topic))
                .filter(entry -> earliest(entries, entry) && !blocked(entries, topic, entry.key().ownerKey()))
                .filter(entry -> !entry.nextAttemptAt().isAfter(now))
                .map(StoredEntry::pending)
                .sorted(pendingOrder())
                .limit(limit)
                .toList();
    }

    @Override
    public List<PendingEventInboxEntry> pending(String topic) {
        return entries().stream()
                .filter(entry -> !entry.deadLettered() && entry.key().topic().equals(topic))
                .map(StoredEntry::pending)
                .sorted(pendingOrder())
                .toList();
    }

    @Override
    public List<DeadLetteredEventInboxEntry> deadLetters(String topic) {
        return entries().stream()
                .filter(entry -> entry.deadLettered() && entry.key().topic().equals(topic))
                .map(StoredEntry::deadLetter)
                .sorted(Comparator.comparing(DeadLetteredEventInboxEntry::receivedAt)
                        .thenComparing(entry -> entry.key().ownerKey())
                        .thenComparingLong(entry -> entry.key().revision()))
                .toList();
    }

    @Override
    public synchronized void markHandled(EventInboxKey key) {
        store.delete(storageKey(Objects.requireNonNull(key, "key")));
    }

    @Override
    public synchronized EventInboxFailureResult markAttemptFailed(
            EventInboxKey key,
            Throwable failure,
            Instant failedAt,
            Instant nextAttemptAt,
            int maxAttempts
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        String storageKey = storageKey(key);
        StoredEntry current = store.load(storageKey).map(this::decode).orElse(null);
        if (current == null || current.deadLettered()) {
            return EventInboxFailureResult.ENTRY_NOT_FOUND;
        }
        requireMatchingKey(key, current.key());
        int attempts = current.attempts() + 1;
        boolean deadLettered = attempts >= maxAttempts;
        StoredEntry updated = new StoredEntry(
                current.consumerNamespace(),
                current.key(),
                current.event(),
                current.receivedAt(),
                attempts,
                nextAttemptAt,
                failureMessage(failure),
                deadLettered,
                deadLettered ? failedAt : Instant.EPOCH
        );
        store.save(storageKey, encode(updated));
        return deadLettered ? EventInboxFailureResult.DEAD_LETTERED : EventInboxFailureResult.RETRY_SCHEDULED;
    }

    @Override
    public synchronized boolean redriveDeadLetter(EventInboxKey key, Instant nextAttemptAt) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        String storageKey = storageKey(key);
        StoredEntry current = store.load(storageKey).map(this::decode).orElse(null);
        if (current == null || !current.deadLettered()) {
            return false;
        }
        requireMatchingKey(key, current.key());
        StoredEntry pending = new StoredEntry(
                current.consumerNamespace(),
                key, current.event(), current.receivedAt(), 0, nextAttemptAt, "", false, Instant.EPOCH
        );
        store.save(storageKey, encode(pending));
        return true;
    }

    private List<StoredEntry> entries() {
        return store.loadAll().stream()
                .map(this::decode)
                .filter(entry -> entry.consumerNamespace().equals(consumerNamespace))
                .toList();
    }

    private byte[] encode(StoredEntry entry) {
        EncodedPayload payload = registry.encode(new EventPublishRequest(entry.event()));
        int size = CodedOutputStream.computeStringSize(1, entry.key().topic())
                + CodedOutputStream.computeStringSize(2, entry.key().ownerKey())
                + CodedOutputStream.computeInt64Size(3, entry.key().revision())
                + CodedOutputStream.computeInt64Size(4, entry.receivedAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(5, entry.receivedAt().getNano())
                + CodedOutputStream.computeInt32Size(6, entry.attempts())
                + CodedOutputStream.computeInt64Size(7, entry.nextAttemptAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(8, entry.nextAttemptAt().getNano())
                + CodedOutputStream.computeStringSize(9, entry.lastFailure())
                + CodedOutputStream.computeBoolSize(10, entry.deadLettered())
                + CodedOutputStream.computeInt64Size(11, entry.deadLetteredAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(12, entry.deadLetteredAt().getNano())
                + CodedOutputStream.computeStringSize(13, payload.codecName())
                + CodedOutputStream.computeStringSize(14, payload.typeName())
                + CodedOutputStream.computeByteArraySize(15, payload.bytes())
                + CodedOutputStream.computeStringSize(16, entry.consumerNamespace());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, entry.key().topic());
            output.writeString(2, entry.key().ownerKey());
            output.writeInt64(3, entry.key().revision());
            output.writeInt64(4, entry.receivedAt().getEpochSecond());
            output.writeInt32(5, entry.receivedAt().getNano());
            output.writeInt32(6, entry.attempts());
            output.writeInt64(7, entry.nextAttemptAt().getEpochSecond());
            output.writeInt32(8, entry.nextAttemptAt().getNano());
            output.writeString(9, entry.lastFailure());
            output.writeBool(10, entry.deadLettered());
            output.writeInt64(11, entry.deadLetteredAt().getEpochSecond());
            output.writeInt32(12, entry.deadLetteredAt().getNano());
            output.writeString(13, payload.codecName());
            output.writeString(14, payload.typeName());
            output.writeByteArray(15, payload.bytes());
            output.writeString(16, entry.consumerNamespace());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode versioned event inbox entry", e);
        }
    }

    private StoredEntry decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String topic = "";
        String ownerKey = "";
        long revision = 0;
        long receivedSecond = 0;
        int receivedNano = 0;
        int attempts = 0;
        long nextSecond = 0;
        int nextNano = 0;
        String lastFailure = "";
        boolean deadLettered = false;
        long deadSecond = 0;
        int deadNano = 0;
        String codecName = "";
        String typeName = "";
        byte[] payloadBytes = new byte[0];
        String namespace = "default";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> topic = input.readString();
                    case 2 -> ownerKey = input.readString();
                    case 3 -> revision = input.readInt64();
                    case 4 -> receivedSecond = input.readInt64();
                    case 5 -> receivedNano = input.readInt32();
                    case 6 -> attempts = input.readInt32();
                    case 7 -> nextSecond = input.readInt64();
                    case 8 -> nextNano = input.readInt32();
                    case 9 -> lastFailure = input.readString();
                    case 10 -> deadLettered = input.readBool();
                    case 11 -> deadSecond = input.readInt64();
                    case 12 -> deadNano = input.readInt32();
                    case 13 -> codecName = input.readString();
                    case 14 -> typeName = input.readString();
                    case 15 -> payloadBytes = input.readByteArray();
                    case 16 -> namespace = input.readString();
                    default -> input.skipField(tag);
                }
            }
            Object decoded = registry.decode(codecName, typeName, payloadBytes);
            if (!(decoded instanceof EventPublishRequest request) || !(request.event() instanceof VersionedEvent event)) {
                throw new IllegalStateException("Inbox payload is not a versioned event publish request");
            }
            EventInboxKey key = new EventInboxKey(topic, ownerKey, revision);
            requireMatchingKey(key, EventInboxKey.of(event));
            return new StoredEntry(
                    namespace,
                    key,
                    event,
                    Instant.ofEpochSecond(receivedSecond, receivedNano),
                    attempts,
                    Instant.ofEpochSecond(nextSecond, nextNano),
                    lastFailure,
                    deadLettered,
                    Instant.ofEpochSecond(deadSecond, deadNano)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode versioned event inbox entry", e);
        }
    }

    private String storageKey(EventInboxKey key) {
        String identity = consumerNamespace.length() + ":" + consumerNamespace
                + key.topic().length() + ":" + key.topic()
                + key.ownerKey().length() + ":" + key.ownerKey()
                + ":" + key.revision();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void requireMatchingKey(EventInboxKey expected, EventInboxKey actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Event inbox identity hash collision or corrupted payload");
        }
    }

    private static boolean earliest(List<StoredEntry> entries, StoredEntry candidate) {
        return entries.stream()
                .filter(entry -> !entry.deadLettered())
                .filter(entry -> entry.key().topic().equals(candidate.key().topic()))
                .filter(entry -> entry.key().ownerKey().equals(candidate.key().ownerKey()))
                .noneMatch(entry -> entry.key().revision() < candidate.key().revision());
    }

    private static boolean blocked(List<StoredEntry> entries, String topic, String ownerKey) {
        return entries.stream().anyMatch(entry -> entry.deadLettered()
                && entry.key().topic().equals(topic)
                && entry.key().ownerKey().equals(ownerKey));
    }

    private static Comparator<PendingEventInboxEntry> pendingOrder() {
        return Comparator.comparing(PendingEventInboxEntry::receivedAt)
                .thenComparing(entry -> entry.key().ownerKey())
                .thenComparingLong(entry -> entry.key().revision());
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        String value = message == null || message.isBlank() ? failure.getClass().getName() : message;
        return value.length() <= MAX_FAILURE_MESSAGE_LENGTH ? value : value.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
    }

    private record StoredEntry(
            String consumerNamespace,
            EventInboxKey key,
            VersionedEvent event,
            Instant receivedAt,
            int attempts,
            Instant nextAttemptAt,
            String lastFailure,
            boolean deadLettered,
            Instant deadLetteredAt
    ) {
        private static StoredEntry pending(String consumerNamespace, VersionedEvent event, Instant receivedAt) {
            return new StoredEntry(
                    consumerNamespace,
                    EventInboxKey.of(event), event, receivedAt, 0, receivedAt, "", false, Instant.EPOCH
            );
        }

        private PendingEventInboxEntry pending() {
            return new PendingEventInboxEntry(key, event, receivedAt, attempts, nextAttemptAt, lastFailure);
        }

        private DeadLetteredEventInboxEntry deadLetter() {
            return new DeadLetteredEventInboxEntry(
                    key, event, receivedAt, attempts, deadLetteredAt, lastFailure
            );
        }
    }
}
