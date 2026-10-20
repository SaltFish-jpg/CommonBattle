package com.commonbattle.cluster.rpc;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
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
import java.util.HexFormat;
import java.util.Objects;

/**
 * 基于原子字节存储的分布式 RPC 幂等状态机。
 * 可使用 JDBC、Redis 或其它支持单键 CAS 的 AtomicBytesStore，在多进程间协调执行租约并保留完成结果。
 */
public final class SerializedRpcIdempotencyStore implements CoordinatedRpcIdempotencyStore {
    private static final String KEY_PREFIX = "rpc:idempotency:";
    private static final int MAGIC = 0x52504944;
    private static final int FORMAT_VERSION = 1;
    private static final byte PENDING = 1;
    private static final byte COMPLETED = 2;
    private static final int MAX_CAS_ATTEMPTS = 32;

    private final AtomicBytesStore store;
    private final PayloadCodecRegistry payloadCodecs;
    private final Duration resultTtl;
    private final Duration leaseDuration;
    private final Clock clock;

    public SerializedRpcIdempotencyStore(
            AtomicBytesStore store,
            PayloadCodecRegistry payloadCodecs,
            Duration resultTtl,
            Duration leaseDuration,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.payloadCodecs = Objects.requireNonNull(payloadCodecs, "payloadCodecs");
        this.resultTtl = requirePositive(resultTtl, "resultTtl");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RpcIdempotencyResult get(RpcIdempotencyKey key) {
        String storageKey = storageKey(key);
        byte[] bytes = store.load(storageKey).orElse(null);
        if (bytes == null) {
            return null;
        }
        StoredState state = decode(bytes);
        if (state.status() != COMPLETED) {
            return null;
        }
        if (expired(state.expiresAtMillis())) {
            store.compareAndDelete(storageKey, bytes);
            return null;
        }
        return state.result();
    }

    @Override
    public void put(RpcIdempotencyKey key, RpcIdempotencyResult result) {
        Objects.requireNonNull(result, "result");
        store.put(storageKey(key), encodeCompleted(result, resultExpiresAt()));
    }

    @Override
    public RpcIdempotencyClaim claim(RpcIdempotencyKey key, String ownerToken) {
        requireOwner(ownerToken);
        String storageKey = storageKey(key);
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            long now = clock.millis();
            byte[] pending = encodePending(ownerToken, saturatedAdd(now, leaseDuration.toMillis()));
            byte[] current = store.load(storageKey).orElse(null);
            if (current == null) {
                if (store.putIfAbsent(storageKey, pending)) {
                    return RpcIdempotencyClaim.acquired();
                }
                continue;
            }
            StoredState state = decode(current);
            if (state.status() == COMPLETED && !expired(state.expiresAtMillis())) {
                return RpcIdempotencyClaim.completed(state.result());
            }
            if (state.status() == PENDING
                    && !state.ownerToken().equals(ownerToken)
                    && state.expiresAtMillis() > now) {
                return RpcIdempotencyClaim.inProgress();
            }
            if (store.compareAndSet(storageKey, current, pending)) {
                return RpcIdempotencyClaim.acquired();
            }
        }
        throw new IllegalStateException("RPC idempotency claim contention exceeded retry limit");
    }

    @Override
    public boolean renew(RpcIdempotencyKey key, String ownerToken) {
        requireOwner(ownerToken);
        String storageKey = storageKey(key);
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            byte[] current = store.load(storageKey).orElse(null);
            if (current == null) {
                return false;
            }
            StoredState state = decode(current);
            if (state.status() != PENDING || !state.ownerToken().equals(ownerToken)) {
                return false;
            }
            byte[] renewed = encodePending(
                    ownerToken,
                    saturatedAdd(clock.millis(), leaseDuration.toMillis())
            );
            if (store.compareAndSet(storageKey, current, renewed)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean complete(RpcIdempotencyKey key, String ownerToken, RpcIdempotencyResult result) {
        requireOwner(ownerToken);
        Objects.requireNonNull(result, "result");
        String storageKey = storageKey(key);
        byte[] completed = encodeCompleted(result, resultExpiresAt());
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            byte[] current = store.load(storageKey).orElse(null);
            if (current == null) {
                return false;
            }
            StoredState state = decode(current);
            if (state.status() != PENDING || !state.ownerToken().equals(ownerToken)) {
                return false;
            }
            if (store.compareAndSet(storageKey, current, completed)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean release(RpcIdempotencyKey key, String ownerToken) {
        requireOwner(ownerToken);
        String storageKey = storageKey(key);
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            byte[] current = store.load(storageKey).orElse(null);
            if (current == null) {
                return false;
            }
            StoredState state = decode(current);
            if (state.status() != PENDING || !state.ownerToken().equals(ownerToken)) {
                return false;
            }
            if (store.compareAndDelete(storageKey, current)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int size() {
        int activeResults = 0;
        long now = clock.millis();
        for (AtomicBytesStore.Entry entry : store.scanPrefix(KEY_PREFIX)) {
            StoredState state = decode(entry.bytes());
            if (state.expiresAtMillis() <= now) {
                store.compareAndDelete(entry.key(), entry.bytes());
            } else if (state.status() == COMPLETED) {
                activeResults++;
            }
        }
        return activeResults;
    }

    @Override
    public Duration leaseDuration() {
        return leaseDuration;
    }

    private byte[] encodePending(String ownerToken, long leaseExpiresAtMillis) {
        return write(output -> {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeByte(PENDING);
            writeString(output, ownerToken);
            output.writeLong(leaseExpiresAtMillis);
        });
    }

    private byte[] encodeCompleted(RpcIdempotencyResult result, long expiresAtMillis) {
        EncodedPayload payload = payloadCodecs.encode(result.payload());
        return write(output -> {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeByte(COMPLETED);
            output.writeLong(expiresAtMillis);
            output.writeBoolean(result.success());
            writeString(output, payload.codecName());
            writeString(output, payload.typeName());
            output.writeInt(payload.bytes().length);
            output.write(payload.bytes());
        });
    }

    private StoredState decode(byte[] bytes) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalStateException("Invalid RPC idempotency record magic");
            }
            int version = input.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException("Unsupported RPC idempotency record version " + version);
            }
            byte status = input.readByte();
            if (status == PENDING) {
                return new StoredState(status, readString(input), input.readLong(), null);
            }
            if (status == COMPLETED) {
                long expiresAtMillis = input.readLong();
                boolean success = input.readBoolean();
                String codecName = readString(input);
                String typeName = readString(input);
                int payloadLength = input.readInt();
                if (payloadLength < 0 || payloadLength > input.available()) {
                    throw new IllegalStateException("Invalid RPC idempotency payload length " + payloadLength);
                }
                Object payload = payloadCodecs.decode(codecName, typeName, input.readNBytes(payloadLength));
                return new StoredState(
                        status,
                        "",
                        expiresAtMillis,
                        new RpcIdempotencyResult(success, payload)
                );
            }
            throw new IllegalStateException("Unknown RPC idempotency status " + status);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode RPC idempotency record", e);
        }
    }

    private long resultExpiresAt() {
        return saturatedAdd(clock.millis(), resultTtl.toMillis());
    }

    private boolean expired(long expiresAtMillis) {
        return expiresAtMillis <= clock.millis();
    }

    private static String storageKey(RpcIdempotencyKey key) {
        Objects.requireNonNull(key, "key");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, key.source().wireName());
            updateDigest(digest, key.operation());
            updateDigest(digest, key.key());
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

    private static byte[] write(IoWriter writer) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode RPC idempotency record", e);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > input.available()) {
            throw new IllegalStateException("Invalid RPC idempotency string length " + length);
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static void requireOwner(String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken required");
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private record StoredState(
            byte status,
            String ownerToken,
            long expiresAtMillis,
            RpcIdempotencyResult result
    ) {
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream output) throws IOException;
    }
}
