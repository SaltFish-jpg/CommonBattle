package com.commonbattle.game.config;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.persistence.AtomicBytesStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于原子字节存储的 Center 配置状态仓储。
 * 历史版本使用不可变唯一键，当前快照使用 CAS 防止较旧 event revision 覆盖较新状态。
 */
public final class SerializedGameConfigCenterStateStore implements GameConfigCenterStateStore {
    private static final String SNAPSHOT_KEY = "game:config:center:snapshot";
    private static final String VERSION_PREFIX = "game:config:center:version:";
    private static final int MAGIC = 0x47434647;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_CAS_ATTEMPTS = 32;

    private final AtomicBytesStore store;
    private final PayloadCodecRegistry payloadCodecs;

    public SerializedGameConfigCenterStateStore(
            AtomicBytesStore store,
            PayloadCodecRegistry payloadCodecs
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.payloadCodecs = Objects.requireNonNull(payloadCodecs, "payloadCodecs");
    }

    @Override
    public Optional<GameConfigSnapshot> loadSnapshot() {
        return store.load(SNAPSHOT_KEY).map(this::decodeSnapshot);
    }

    @Override
    public List<GameConfigPackage> loadVersions() {
        return store.scanPrefix(VERSION_PREFIX).stream()
                .map(entry -> decodeSnapshot(entry.bytes()).activeConfig())
                .sorted(Comparator.comparingLong(GameConfigPackage::version))
                .toList();
    }

    @Override
    public void saveVersion(GameConfigPackage config) {
        Objects.requireNonNull(config, "config");
        String key = VERSION_PREFIX + config.version();
        byte[] encoded = encode(GameConfigSnapshot.activeOnly(0, config));
        if (store.putIfAbsent(key, encoded)) {
            return;
        }
        GameConfigPackage existing = store.load(key)
                .map(this::decodeSnapshot)
                .map(GameConfigSnapshot::activeConfig)
                .orElseThrow(() -> new IllegalStateException("Game config version disappeared: " + config.version()));
        if (!existing.equals(config)) {
            throw new IllegalStateException("Game config version payload conflict: " + config.version());
        }
    }

    @Override
    public void saveSnapshot(GameConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        byte[] updated = encode(snapshot);
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            byte[] current = store.load(SNAPSHOT_KEY).orElse(null);
            if (current == null) {
                if (store.putIfAbsent(SNAPSHOT_KEY, updated)) {
                    return;
                }
                continue;
            }
            GameConfigSnapshot existing = decodeSnapshot(current);
            if (existing.eventRevision() > snapshot.eventRevision()) {
                throw new IllegalStateException("Game config event revision regressed from "
                        + existing.eventRevision() + " to " + snapshot.eventRevision());
            }
            if (existing.eventRevision() == snapshot.eventRevision()) {
                if (existing.equals(snapshot)) {
                    return;
                }
                throw new IllegalStateException("Game config event revision conflict: " + snapshot.eventRevision());
            }
            if (store.compareAndSet(SNAPSHOT_KEY, current, updated)) {
                return;
            }
        }
        throw new IllegalStateException("Game config snapshot contention exceeded retry limit");
    }

    private byte[] encode(GameConfigSnapshot snapshot) {
        EncodedPayload payload = payloadCodecs.encode(snapshot);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, payload.codecName());
            writeString(output, payload.typeName());
            output.writeInt(payload.bytes().length);
            output.write(payload.bytes());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode game config center state", e);
        }
    }

    private GameConfigSnapshot decodeSnapshot(byte[] bytes) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalStateException("Invalid game config center state magic");
            }
            int version = input.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException("Unsupported game config center state version " + version);
            }
            String codecName = readString(input);
            String typeName = readString(input);
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength > input.available()) {
                throw new IllegalStateException("Invalid game config center payload length " + payloadLength);
            }
            Object decoded = payloadCodecs.decode(codecName, typeName, input.readNBytes(payloadLength));
            if (!(decoded instanceof GameConfigSnapshot snapshot)) {
                throw new IllegalStateException("Unexpected game config center payload " + decoded);
            }
            return snapshot;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode game config center state", e);
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
            throw new IllegalStateException("Invalid game config center string length " + length);
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }
}
