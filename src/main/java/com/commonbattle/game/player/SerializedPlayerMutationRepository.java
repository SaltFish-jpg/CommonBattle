package com.commonbattle.game.player;

import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.persistence.AtomicBytesStore;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 基于单 key CAS 的玩家命令提交仓库。
 * 原子记录是恢复真相，旧状态仓库仅作为升级回退和读模型投影使用。
 */
public final class SerializedPlayerMutationRepository implements PlayerMutationRepository, PlayerMutationRepositoryView {
    private static final String PREFIX = "player:mutation:";

    private final AtomicBytesStore store;
    private final PlayerStateRepository projection;
    private final PlayerStateSnapshotSerializer snapshotSerializer;
    private final PlayerBusinessResponseSnapshotCodec responseCodec;
    private final PlayerBusinessResponseStore responseProjection;
    private final LongAdder commits = new LongAdder();
    private final LongAdder commitFailures = new LongAdder();
    private final LongAdder committedEvents = new LongAdder();
    private final LongAdder enqueuedEvents = new LongAdder();
    private final LongAdder casRetries = new LongAdder();
    private final LongAdder projectionFailures = new LongAdder();
    private final LongAdder statsFailures = new LongAdder();

    public SerializedPlayerMutationRepository(
            AtomicBytesStore store,
            PlayerStateRepository projection,
            PlayerStateSnapshotSerializer snapshotSerializer
    ) {
        this(
                store,
                projection,
                snapshotSerializer,
                PlayerBusinessResponseStore.noop(),
                new PlayerBusinessResponseSnapshotCodec(
                        PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults()))
        );
    }

    public SerializedPlayerMutationRepository(
            AtomicBytesStore store,
            PlayerStateRepository projection,
            PlayerStateSnapshotSerializer snapshotSerializer,
            PlayerBusinessResponseStore responseProjection,
            PlayerBusinessResponseSnapshotCodec responseCodec
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.snapshotSerializer = Objects.requireNonNull(snapshotSerializer, "snapshotSerializer");
        this.responseProjection = Objects.requireNonNull(responseProjection, "responseProjection");
        this.responseCodec = Objects.requireNonNull(responseCodec, "responseCodec");
    }

    @Override
    public Optional<PlayerStateSnapshot> load(Long playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return loadRecord(playerId).map(PlayerMutationRecord::snapshot)
                .or(() -> projection.load(playerId));
    }

    @Override
    public void save(Long playerId, PlayerStateSnapshot snapshot) {
        requireMatchingPlayer(playerId, snapshot);
        update(snapshot, List.of(), null, false, null);
    }

    @Override
    public Optional<PlayerMutationRecord> acquireOwner(long playerId, String ownerToken) {
        requirePlayerId(playerId);
        requireOwnerToken(ownerToken);
        String key = key(playerId);
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                return Optional.empty();
            }
            byte[] currentBytes = loaded.orElseThrow();
            PlayerMutationRecord current = decode(currentBytes);
            if (ownerToken.equals(current.ownerToken())) {
                return Optional.of(current);
            }
            validateOwnerTransition(current, ownerToken);
            PlayerMutationRecord owned = new PlayerMutationRecord(
                    current.snapshot(), current.pendingEvents(), current.responses(), ownerToken
            );
            if (store.compareAndSet(key, currentBytes, encode(owned))) {
                return Optional.of(owned);
            }
            casRetries.increment();
        }
    }

    @Override
    public PlayerMutationRecord acquireOwner(PlayerStateSnapshot initialSnapshot, String ownerToken) {
        Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        requireOwnerToken(ownerToken);
        String key = key(initialSnapshot.playerId());
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                PlayerMutationRecord initial = new PlayerMutationRecord(
                        initialSnapshot,
                        List.of(),
                        responseProjection.load(initialSnapshot.playerId()).orElseGet(() ->
                                PlayerBusinessResponseSnapshot.empty(initialSnapshot.playerId())),
                        ownerToken
                );
                if (store.putIfAbsent(key, encode(initial))) {
                    return initial;
                }
                casRetries.increment();
                continue;
            }
            byte[] currentBytes = loaded.orElseThrow();
            PlayerMutationRecord current = decode(currentBytes);
            if (ownerToken.equals(current.ownerToken())) {
                return current;
            }
            validateOwnerTransition(current, ownerToken);
            PlayerMutationRecord owned = new PlayerMutationRecord(
                    current.snapshot(), current.pendingEvents(), current.responses(), ownerToken
            );
            if (store.compareAndSet(key, currentBytes, encode(owned))) {
                return owned;
            }
            casRetries.increment();
        }
    }

    @Override
    public PlayerMutationRecord commit(
            PlayerStateSnapshot snapshot,
            List<PlayerDomainVersionedEvent> events
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(events, "events");
        try {
            PlayerMutationRecord committed = update(snapshot, events, null, true, null);
            commits.increment();
            committedEvents.add(events.size());
            return committed;
        } catch (RuntimeException | Error error) {
            commitFailures.increment();
            throw error;
        }
    }

    @Override
    public PlayerMutationRecord commitOwned(
            String ownerToken,
            PlayerStateSnapshot snapshot,
            List<PlayerDomainVersionedEvent> events
    ) {
        requireOwnerToken(ownerToken);
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(events, "events");
        try {
            PlayerMutationRecord committed = update(snapshot, events, null, true, ownerToken);
            commits.increment();
            committedEvents.add(events.size());
            return committed;
        } catch (RuntimeException | Error error) {
            commitFailures.increment();
            throw error;
        }
    }

    @Override
    public PlayerMutationRecord commit(
            PlayerStateSnapshot snapshot,
            List<PlayerDomainVersionedEvent> events,
            PlayerBusinessResponseSnapshot responses
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(responses, "responses");
        try {
            PlayerMutationRecord committed = update(snapshot, events, responses, true, null);
            commits.increment();
            committedEvents.add(events.size());
            return committed;
        } catch (RuntimeException | Error error) {
            commitFailures.increment();
            throw error;
        }
    }

    @Override
    public PlayerMutationRecord commitOwned(
            String ownerToken,
            PlayerStateSnapshot snapshot,
            List<PlayerDomainVersionedEvent> events,
            PlayerBusinessResponseSnapshot responses
    ) {
        requireOwnerToken(ownerToken);
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(responses, "responses");
        try {
            PlayerMutationRecord committed = update(snapshot, events, responses, true, ownerToken);
            commits.increment();
            committedEvents.add(events.size());
            return committed;
        } catch (RuntimeException | Error error) {
            commitFailures.increment();
            throw error;
        }
    }

    @Override
    public Optional<PlayerBusinessResponseSnapshot> loadResponses(long playerId) {
        return loadRecord(playerId).map(PlayerMutationRecord::responses)
                .filter(snapshot -> !snapshot.responses().isEmpty())
                .or(() -> responseProjection.load(playerId));
    }

    @Override
    public void saveResponses(PlayerBusinessResponseSnapshot responses) {
        Objects.requireNonNull(responses, "responses");
        String key = key(responses.playerId());
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                responseProjection.save(responses);
                return;
            }
            byte[] currentBytes = loaded.orElseThrow();
            PlayerMutationRecord current = decode(currentBytes);
            PlayerMutationRecord updated = new PlayerMutationRecord(
                    current.snapshot(), current.pendingEvents(), responses, current.ownerToken()
            );
            if (store.compareAndSet(key, currentBytes, encode(updated))) {
                try {
                    responseProjection.save(responses);
                } catch (RuntimeException ignored) {
                    projectionFailures.increment();
                }
                return;
            }
            casRetries.increment();
        }
    }

    @Override
    public Optional<PlayerMutationRecord> loadRecord(long playerId) {
        requirePlayerId(playerId);
        return store.load(key(playerId)).map(this::decode);
    }

    @Override
    public PlayerMutationRecord markEventEnqueued(long playerId, long eventRevision) {
        requirePlayerId(playerId);
        if (eventRevision <= 0) {
            throw new IllegalArgumentException("eventRevision must be positive");
        }
        String key = key(playerId);
        while (true) {
            byte[] currentBytes = store.load(key)
                    .orElseThrow(() -> new IllegalStateException("player mutation record not found: " + playerId));
            PlayerMutationRecord current = decode(currentBytes);
            List<PlayerDomainVersionedEvent> pending = current.pendingEvents().stream()
                    .filter(event -> event.revision() != eventRevision)
                    .toList();
            if (pending.size() == current.pendingEvents().size()) {
                return current;
            }
            PlayerMutationRecord updated = new PlayerMutationRecord(
                    current.snapshot(), pending, current.responses(), current.ownerToken()
            );
            if (store.compareAndSet(key, currentBytes, encode(updated))) {
                enqueuedEvents.increment();
                return updated;
            }
            casRetries.increment();
        }
    }

    @Override
    public PlayerMutationRepositoryStats mutationRepositoryStats() {
        try {
            List<PlayerMutationRecord> records = store.scanPrefix(PREFIX).stream()
                    .map(AtomicBytesStore.Entry::bytes)
                    .map(this::decode)
                    .toList();
            return new PlayerMutationRepositoryStats(
                    records.size(),
                    records.stream().mapToInt(record -> record.pendingEvents().size()).sum(),
                    commits.sum(),
                    commitFailures.sum(),
                    committedEvents.sum(),
                    enqueuedEvents.sum(),
                    casRetries.sum(),
                    projectionFailures.sum(),
                    statsFailures.sum()
            );
        } catch (RuntimeException error) {
            statsFailures.increment();
            return new PlayerMutationRepositoryStats(
                    0, 0, commits.sum(), commitFailures.sum(), committedEvents.sum(), enqueuedEvents.sum(),
                    casRetries.sum(), projectionFailures.sum(), statsFailures.sum()
            );
        }
    }

    @Override
    public List<PlayerMutationPendingRecord> pendingMutationRecords(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return store.scanPrefix(PREFIX).stream()
                .map(AtomicBytesStore.Entry::bytes)
                .map(this::decode)
                .filter(record -> !record.pendingEvents().isEmpty())
                .sorted(Comparator.comparingLong(record -> record.snapshot().playerId()))
                .limit(limit)
                .map(SerializedPlayerMutationRepository::pendingSummary)
                .toList();
    }

    private PlayerMutationRecord update(
            PlayerStateSnapshot snapshot,
            List<PlayerDomainVersionedEvent> newEvents,
            PlayerBusinessResponseSnapshot responses,
            boolean requireNextRevision,
            String expectedOwnerToken
    ) {
        validateEvents(snapshot, newEvents);
        String key = key(snapshot.playerId());
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            PlayerMutationRecord current = loaded.map(this::decode).orElse(null);
            if (current != null) {
                long currentRevision = current.snapshot().revision();
                validateOwner(snapshot, current, expectedOwnerToken);
                if (snapshot.revision() < currentRevision
                        || (requireNextRevision && snapshot.revision() <= currentRevision)) {
                    throw conflict(snapshot, currentRevision, PlayerMutationConflictReason.STALE_REVISION);
                }
                if (requireNextRevision && snapshot.revision() != currentRevision + 1) {
                    throw conflict(snapshot, currentRevision, PlayerMutationConflictReason.REVISION_GAP);
                }
            }
            PlayerMutationRecord updated = new PlayerMutationRecord(
                    snapshot,
                    mergePending(current, newEvents),
                    responses == null
                            ? current == null
                                    ? responseProjection.load(snapshot.playerId())
                                            .orElseGet(() -> PlayerBusinessResponseSnapshot.empty(snapshot.playerId()))
                                    : current.responses()
                            : responses,
                    expectedOwnerToken == null ? current == null ? "" : current.ownerToken() : expectedOwnerToken
            );
            byte[] updatedBytes = encode(updated);
            boolean stored = loaded.isPresent()
                    ? store.compareAndSet(key, loaded.orElseThrow(), updatedBytes)
                    : store.putIfAbsent(key, updatedBytes);
            if (!stored) {
                casRetries.increment();
                continue;
            }
            // 投影失败不影响已提交真相；下次 load 仍从 mutation 记录恢复。
            try {
                projection.save(snapshot.playerId(), snapshot);
                responseProjection.save(updated.responses());
            } catch (RuntimeException ignored) {
                projectionFailures.increment();
            }
            return updated;
        }
    }

    private static List<PlayerDomainVersionedEvent> mergePending(
            PlayerMutationRecord current,
            List<PlayerDomainVersionedEvent> newEvents
    ) {
        Map<Long, PlayerDomainVersionedEvent> merged = new TreeMap<>();
        if (current != null) {
            current.pendingEvents().forEach(event -> merged.put(event.revision(), event));
        }
        for (PlayerDomainVersionedEvent event : newEvents) {
            PlayerDomainVersionedEvent previous = merged.putIfAbsent(event.revision(), event);
            if (previous != null && !previous.equals(event)) {
                throw new IllegalStateException("conflicting player event revision " + event.revision());
            }
        }
        return List.copyOf(merged.values());
    }

    private static PlayerMutationPendingRecord pendingSummary(PlayerMutationRecord record) {
        List<PlayerDomainVersionedEvent> pending = record.pendingEvents();
        return new PlayerMutationPendingRecord(
                record.snapshot().playerId(),
                record.snapshot().revision(),
                record.snapshot().eventRevision(),
                pending.size(),
                pending.getFirst().revision(),
                pending.getLast().revision(),
                pending.stream().map(PlayerDomainVersionedEvent::eventType).distinct().toList()
        );
    }

    private static void validateEvents(
            PlayerStateSnapshot snapshot,
            List<PlayerDomainVersionedEvent> events
    ) {
        long previous = 0;
        for (PlayerDomainVersionedEvent event : events) {
            Objects.requireNonNull(event, "event");
            if (event.playerId() != snapshot.playerId()) {
                throw new IllegalArgumentException("event playerId mismatch");
            }
            if (event.revision() <= previous || event.revision() > snapshot.eventRevision()) {
                throw new IllegalArgumentException("events must be ordered within snapshot event watermark");
            }
            previous = event.revision();
        }
    }

    private byte[] encode(PlayerMutationRecord record) {
        byte[] snapshot = snapshotSerializer.serialize(record.snapshot());
        byte[] responses = responseCodec.encode(record.responses());
        List<byte[]> events = record.pendingEvents().stream().map(this::encodeEvent).toList();
        int size = CodedOutputStream.computeByteArraySize(1, snapshot);
        for (byte[] event : events) {
            size += CodedOutputStream.computeByteArraySize(2, event);
        }
        size += CodedOutputStream.computeByteArraySize(3, responses);
        if (!record.ownerToken().isEmpty()) {
            size += CodedOutputStream.computeStringSize(4, record.ownerToken());
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeByteArray(1, snapshot);
            for (byte[] event : events) {
                output.writeByteArray(2, event);
            }
            output.writeByteArray(3, responses);
            if (!record.ownerToken().isEmpty()) {
                output.writeString(4, record.ownerToken());
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player mutation record", e);
        }
    }

    private PlayerMutationRecord decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        byte[] snapshot = null;
        byte[] responses = null;
        String ownerToken = "";
        List<PlayerDomainVersionedEvent> events = new ArrayList<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> snapshot = input.readByteArray();
                    case 2 -> events.add(decodeEvent(input.readByteArray()));
                    case 3 -> responses = input.readByteArray();
                    case 4 -> ownerToken = input.readString();
                    default -> input.skipField(tag);
                }
            }
            if (snapshot == null) {
                throw new IllegalStateException("Player mutation record has no snapshot");
            }
            events.sort(Comparator.comparingLong(PlayerDomainVersionedEvent::revision));
            PlayerStateSnapshot decodedSnapshot = snapshotSerializer.deserialize(snapshot);
            PlayerBusinessResponseSnapshot decodedResponses = responses == null
                    ? PlayerBusinessResponseSnapshot.empty(decodedSnapshot.playerId())
                    : responseCodec.decode(responses);
            return new PlayerMutationRecord(decodedSnapshot, events, decodedResponses, ownerToken);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player mutation record", e);
        }
    }

    private byte[] encodeEvent(PlayerDomainVersionedEvent event) {
        List<byte[]> attributes = event.attributes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encodeAttribute(entry.getKey(), entry.getValue()))
                .toList();
        int size = CodedOutputStream.computeInt64Size(1, event.playerId())
                + CodedOutputStream.computeStringSize(2, event.eventType())
                + CodedOutputStream.computeStringSize(3, event.subject())
                + CodedOutputStream.computeInt32Size(4, event.delta())
                + CodedOutputStream.computeInt64Size(5, event.revision())
                + CodedOutputStream.computeInt64Size(6, event.occurredAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(7, event.occurredAt().getNano());
        for (byte[] attribute : attributes) {
            size += CodedOutputStream.computeByteArraySize(8, attribute);
        }
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, event.playerId());
            output.writeString(2, event.eventType());
            output.writeString(3, event.subject());
            output.writeInt32(4, event.delta());
            output.writeInt64(5, event.revision());
            output.writeInt64(6, event.occurredAt().getEpochSecond());
            output.writeInt32(7, event.occurredAt().getNano());
            for (byte[] attribute : attributes) {
                output.writeByteArray(8, attribute);
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player mutation event", e);
        }
    }

    private PlayerDomainVersionedEvent decodeEvent(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long playerId = 0;
        String eventType = "";
        String subject = "";
        int delta = 0;
        long revision = 0;
        long epochSecond = 0;
        int nano = 0;
        Map<String, String> attributes = new LinkedHashMap<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> playerId = input.readInt64();
                    case 2 -> eventType = input.readString();
                    case 3 -> subject = input.readString();
                    case 4 -> delta = input.readInt32();
                    case 5 -> revision = input.readInt64();
                    case 6 -> epochSecond = input.readInt64();
                    case 7 -> nano = input.readInt32();
                    case 8 -> decodeAttribute(input.readByteArray(), attributes);
                    default -> input.skipField(tag);
                }
            }
            return new PlayerDomainVersionedEvent(
                    playerId, eventType, subject, delta, revision,
                    Instant.ofEpochSecond(epochSecond, nano), attributes
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player mutation event", e);
        }
    }

    private static byte[] encodeAttribute(String key, String value) {
        int size = CodedOutputStream.computeStringSize(1, key)
                + CodedOutputStream.computeStringSize(2, value);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, key);
            output.writeString(2, value);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player event attribute", e);
        }
    }

    private static void decodeAttribute(byte[] bytes, Map<String, String> attributes) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String key = "";
        String value = "";
        int tag;
        while ((tag = input.readTag()) != 0) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> key = input.readString();
                case 2 -> value = input.readString();
                default -> input.skipField(tag);
            }
        }
        if (!key.isBlank()) {
            attributes.put(key, value);
        }
    }

    private static void requireMatchingPlayer(Long playerId, PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(snapshot, "snapshot");
        if (playerId.longValue() != snapshot.playerId()) {
            throw new IllegalArgumentException("repository key must match snapshot playerId");
        }
    }

    private static long requirePlayerId(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        return playerId;
    }

    private static void requireOwnerToken(String ownerToken) {
        Objects.requireNonNull(ownerToken, "ownerToken");
        if (ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken must not be blank");
        }
    }

    private static void validateOwner(
            PlayerStateSnapshot snapshot,
            PlayerMutationRecord current,
            String expectedOwnerToken
    ) {
        String currentOwner = current.ownerToken();
        if (expectedOwnerToken == null) {
            if (!currentOwner.isEmpty()) {
                throw conflict(snapshot, current.snapshot().revision(), PlayerMutationConflictReason.OWNER_FENCED);
            }
            return;
        }
        if (!expectedOwnerToken.equals(currentOwner)) {
            throw conflict(snapshot, current.snapshot().revision(), PlayerMutationConflictReason.OWNER_FENCED);
        }
    }

    private static void validateOwnerTransition(PlayerMutationRecord current, String requestedOwnerToken) {
        Optional<PlayerMutationOwnerToken.Decoded> requested = PlayerMutationOwnerToken.decode(requestedOwnerToken);
        Optional<PlayerMutationOwnerToken.Decoded> existing = PlayerMutationOwnerToken.decode(current.ownerToken());
        if (requested.isEmpty()) {
            if (existing.isPresent()) {
                throw conflict(
                        current.snapshot(),
                        current.snapshot().revision(),
                        PlayerMutationConflictReason.OWNER_FENCED
                );
            }
            return;
        }
        if (existing.isPresent()
                && requested.orElseThrow().fencingToken() <= existing.orElseThrow().fencingToken()) {
            throw conflict(
                    current.snapshot(),
                    current.snapshot().revision(),
                    PlayerMutationConflictReason.OWNER_FENCED
            );
        }
    }

    private static PlayerMutationConflictException conflict(
            PlayerStateSnapshot snapshot,
            long currentRevision,
            PlayerMutationConflictReason reason
    ) {
        return new PlayerMutationConflictException(
                snapshot.playerId(), snapshot.revision(), currentRevision, reason
        );
    }

    private static String key(long playerId) {
        return PREFIX + requirePlayerId(playerId);
    }
}
