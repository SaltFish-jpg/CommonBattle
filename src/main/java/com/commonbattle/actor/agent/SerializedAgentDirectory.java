package com.commonbattle.actor.agent;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.persistence.AtomicBytesStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于单键原子存储的全局 Agent 目录。
 * 多 Center 实例共享同一底层存储时，永久 owner 和租约 owner 都保持唯一性与 CAS 语义。
 */
public final class SerializedAgentDirectory implements LeasedAgentDirectory {
    private static final String KEY_PREFIX = "agent:directory:";
    private static final int MAGIC = 0x41474452;
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int FORMAT_VERSION = 2;

    private final AtomicBytesStore store;
    private final Clock clock;

    public SerializedAgentDirectory(AtomicBytesStore store) {
        this(store, Clock.systemUTC());
    }

    public SerializedAgentDirectory(AtomicBytesStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean claim(AgentIdentity identity, AgentLocation location) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        String key = storageKey(identity);
        StoredRecord next = StoredRecord.permanent(location);
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                if (store.putIfAbsent(key, encode(next))) {
                    return true;
                }
                continue;
            }
            byte[] currentBytes = loaded.orElseThrow();
            StoredRecord current = decode(currentBytes);
            if (current.active()) {
                return !current.leased() && current.location().equals(location);
            }
            StoredRecord replacement = next.withFencingToken(current.fencingToken());
            if (store.compareAndSet(key, currentBytes, encode(replacement))) {
                return true;
            }
        }
    }

    @Override
    public boolean move(AgentIdentity identity, AgentLocation expectedCurrent, AgentLocation next) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        Objects.requireNonNull(next, "next");
        String key = storageKey(identity);
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                return false;
            }
            byte[] currentBytes = loaded.orElseThrow();
            StoredRecord current = decode(currentBytes);
            if (!current.active() || current.leased() || !current.location().equals(expectedCurrent)) {
                return false;
            }
            if (expectedCurrent.equals(next)) {
                return true;
            }
            if (store.compareAndSet(key, currentBytes, encode(current.withLocation(next)))) {
                return true;
            }
        }
    }

    @Override
    public void unbind(AgentIdentity identity, AgentLocation location) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        String key = storageKey(identity);
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                return;
            }
            byte[] currentBytes = loaded.orElseThrow();
            StoredRecord current = decode(currentBytes);
            if (!current.active() || current.leased() || !current.location().equals(location)) {
                return;
            }
            if (store.compareAndDelete(key, currentBytes)) {
                return;
            }
        }
    }

    @Override
    public Optional<AgentLocation> locate(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return store.load(storageKey(identity))
                .map(this::decode)
                .filter(StoredRecord::active)
                .filter(record -> !record.leased() || record.expiresAt().isAfter(clock.instant()))
                .map(StoredRecord::location);
    }

    @Override
    public Optional<AgentDirectoryLease> acquire(
            AgentIdentity identity,
            AgentLocation location,
            String ownerSession,
            Duration leaseTtl
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        requireOwnerSession(ownerSession);
        requirePositive(leaseTtl, "leaseTtl");
        String key = storageKey(identity);
        while (true) {
            Instant now = clock.instant();
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                StoredRecord created = StoredRecord.lease(location, ownerSession, 1, now.plus(leaseTtl));
                if (store.putIfAbsent(key, encode(created))) {
                    return Optional.of(toLease(identity, created));
                }
                continue;
            }
            byte[] currentBytes = loaded.orElseThrow();
            StoredRecord current = decode(currentBytes);
            if (current.active() && !current.leased()) {
                return Optional.empty();
            }
            if (current.active() && current.expiresAt().isAfter(now)) {
                if (current.location().equals(location) && current.ownerSession().equals(ownerSession)) {
                    return Optional.of(toLease(identity, current));
                }
                return Optional.empty();
            }
            StoredRecord replacement = StoredRecord.lease(
                    location,
                    ownerSession,
                    Math.addExact(current.fencingToken(), 1),
                    now.plus(leaseTtl)
            );
            if (store.compareAndSet(key, currentBytes, encode(replacement))) {
                return Optional.of(toLease(identity, replacement));
            }
        }
    }

    @Override
    public Optional<AgentDirectoryLease> renew(AgentDirectoryLease lease, Duration leaseTtl) {
        Objects.requireNonNull(lease, "lease");
        requirePositive(leaseTtl, "leaseTtl");
        String key = storageKey(lease.identity());
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            byte[] currentBytes = loaded.orElseThrow();
            StoredRecord current = decode(currentBytes);
            if (!matches(current, lease) || !current.active() || !current.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            StoredRecord renewed = current.withExpiresAt(now.plus(leaseTtl));
            if (store.compareAndSet(key, currentBytes, encode(renewed))) {
                return Optional.of(toLease(lease.identity(), renewed));
            }
        }
    }

    @Override
    public Optional<AgentDirectoryLease> transfer(
            AgentDirectoryLease lease,
            AgentLocation nextLocation,
            String nextOwnerSession,
            Duration leaseTtl
    ) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(nextLocation, "nextLocation");
        requireOwnerSession(nextOwnerSession);
        requirePositive(leaseTtl, "leaseTtl");
        String key = storageKey(lease.identity());
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            byte[] currentBytes = loaded.orElseThrow();
            StoredRecord current = decode(currentBytes);
            if (!current.active() || !matches(current, lease) || !current.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            StoredRecord transferred = StoredRecord.lease(
                    nextLocation,
                    nextOwnerSession,
                    Math.addExact(current.fencingToken(), 1),
                    now.plus(leaseTtl)
            );
            if (store.compareAndSet(key, currentBytes, encode(transferred))) {
                return Optional.of(toLease(lease.identity(), transferred));
            }
        }
    }

    @Override
    public boolean release(AgentDirectoryLease lease) {
        Objects.requireNonNull(lease, "lease");
        String key = storageKey(lease.identity());
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                return false;
            }
            byte[] currentBytes = loaded.orElseThrow();
            StoredRecord current = decode(currentBytes);
            if (!current.active() || !matches(current, lease)) {
                return false;
            }
            if (store.compareAndSet(key, currentBytes, encode(current.inactive()))) {
                return true;
            }
        }
    }

    @Override
    public Optional<AgentDirectoryLease> locateLease(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Instant now = clock.instant();
        return store.load(storageKey(identity))
                .map(this::decode)
                .filter(StoredRecord::active)
                .filter(StoredRecord::leased)
                .filter(record -> record.expiresAt().isAfter(now))
                .map(record -> toLease(identity, record));
    }

    private boolean matches(StoredRecord record, AgentDirectoryLease lease) {
        return record.leased()
                && record.location().equals(lease.location())
                && record.ownerSession().equals(lease.ownerSession())
                && record.fencingToken() == lease.fencingToken()
                && record.expiresAt().equals(lease.expiresAt());
    }

    private AgentDirectoryLease toLease(AgentIdentity identity, StoredRecord record) {
        return new AgentDirectoryLease(
                identity,
                record.location(),
                record.ownerSession(),
                record.fencingToken(),
                record.expiresAt()
        );
    }

    private byte[] encode(StoredRecord record) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeBoolean(record.active());
            output.writeBoolean(record.leased());
            writeString(output, record.location().serviceId().wireName());
            writeString(output, record.location().actorRef().id());
            writeString(output, record.ownerSession());
            output.writeLong(record.fencingToken());
            output.writeLong(record.expiresAt().toEpochMilli());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode agent directory record", e);
        }
    }

    private StoredRecord decode(byte[] bytes) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalStateException("Invalid agent directory record magic");
            }
            int version = input.readInt();
            if (version == LEGACY_FORMAT_VERSION) {
                AgentLocation location = readLocation(input);
                return StoredRecord.permanent(location);
            }
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException("Unsupported agent directory record version " + version);
            }
            boolean active = input.readBoolean();
            boolean leased = input.readBoolean();
            AgentLocation location = readLocation(input);
            String ownerSession = readString(input);
            long fencingToken = input.readLong();
            Instant expiresAt = Instant.ofEpochMilli(input.readLong());
            return new StoredRecord(active, leased, location, ownerSession, fencingToken, expiresAt);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode agent directory record", e);
        }
    }

    private static AgentLocation readLocation(DataInputStream input) throws IOException {
        return new AgentLocation(ServiceId.parse(readString(input)), new ActorRef(readString(input)));
    }

    private static String storageKey(AgentIdentity identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, identity.type());
            updateDigest(digest, identity.key());
            return KEY_PREFIX + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > input.available()) {
            throw new IllegalStateException("Invalid agent directory string length " + length);
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void requireOwnerSession(String ownerSession) {
        Objects.requireNonNull(ownerSession, "ownerSession");
        if (ownerSession.isBlank()) {
            throw new IllegalArgumentException("ownerSession must not be blank");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private record StoredRecord(
            boolean active,
            boolean leased,
            AgentLocation location,
            String ownerSession,
            long fencingToken,
            Instant expiresAt
    ) {
        private StoredRecord {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(ownerSession, "ownerSession");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        static StoredRecord permanent(AgentLocation location) {
            return new StoredRecord(true, false, location, "", 0, Instant.EPOCH);
        }

        static StoredRecord lease(
                AgentLocation location,
                String ownerSession,
                long fencingToken,
                Instant expiresAt
        ) {
            return new StoredRecord(true, true, location, ownerSession, fencingToken, expiresAt);
        }

        StoredRecord withLocation(AgentLocation next) {
            return new StoredRecord(active, leased, next, ownerSession, fencingToken, expiresAt);
        }

        StoredRecord withExpiresAt(Instant next) {
            return new StoredRecord(active, leased, location, ownerSession, fencingToken, next);
        }

        StoredRecord withFencingToken(long next) {
            return new StoredRecord(active, leased, location, ownerSession, next, expiresAt);
        }

        StoredRecord inactive() {
            return new StoredRecord(false, leased, location, ownerSession, fencingToken, expiresAt);
        }
    }
}
