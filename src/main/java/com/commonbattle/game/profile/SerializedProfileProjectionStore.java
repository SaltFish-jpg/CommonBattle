package com.commonbattle.game.profile;

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

/**
 * 使用显式 Protobuf wire 字段保存的 Profile 投影。
 */
public final class SerializedProfileProjectionStore implements ProfileProjectionStore {
    private final ProfileProjectionBytesStore store;
    private final String namespace;

    public SerializedProfileProjectionStore(ProfileProjectionBytesStore store, String namespace) {
        this.store = Objects.requireNonNull(store, "store");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
    }

    @Override
    public void save(CachedProfile profile) {
        Objects.requireNonNull(profile, "profile");
        store.save(key(profile.snapshot().playerId()), encode(profile));
    }

    @Override
    public List<CachedProfile> loadAll() {
        return store.loadAll().stream()
                .map(this::decode)
                .filter(entry -> entry.namespace().equals(namespace))
                .map(StoredProfile::profile)
                .sorted(Comparator.comparingLong(profile -> profile.snapshot().playerId()))
                .toList();
    }

    private byte[] encode(CachedProfile cached) {
        PlayerProfileSnapshot snapshot = cached.snapshot();
        int size = CodedOutputStream.computeStringSize(1, namespace)
                + CodedOutputStream.computeInt64Size(2, snapshot.playerId())
                + CodedOutputStream.computeStringSize(3, snapshot.name())
                + CodedOutputStream.computeInt32Size(4, snapshot.level())
                + CodedOutputStream.computeStringSize(5, snapshot.appearance().avatar())
                + CodedOutputStream.computeStringSize(6, snapshot.appearance().frame())
                + CodedOutputStream.computeStringSize(7, snapshot.appearance().costume())
                + CodedOutputStream.computeInt64Size(8, snapshot.alliance().allianceId())
                + CodedOutputStream.computeStringSize(9, snapshot.alliance().name())
                + CodedOutputStream.computeStringSize(10, snapshot.alliance().badge())
                + CodedOutputStream.computeInt32Size(11, snapshot.friends().friendCount())
                + CodedOutputStream.computeInt64Size(12, snapshot.friends().socialRevision())
                + CodedOutputStream.computeInt64Size(13, snapshot.revision())
                + CodedOutputStream.computeInt64Size(14, snapshot.updatedAt().getEpochSecond())
                + CodedOutputStream.computeInt32Size(15, snapshot.updatedAt().getNano())
                + CodedOutputStream.computeBoolSize(16, cached.stale());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, namespace);
            output.writeInt64(2, snapshot.playerId());
            output.writeString(3, snapshot.name());
            output.writeInt32(4, snapshot.level());
            output.writeString(5, snapshot.appearance().avatar());
            output.writeString(6, snapshot.appearance().frame());
            output.writeString(7, snapshot.appearance().costume());
            output.writeInt64(8, snapshot.alliance().allianceId());
            output.writeString(9, snapshot.alliance().name());
            output.writeString(10, snapshot.alliance().badge());
            output.writeInt32(11, snapshot.friends().friendCount());
            output.writeInt64(12, snapshot.friends().socialRevision());
            output.writeInt64(13, snapshot.revision());
            output.writeInt64(14, snapshot.updatedAt().getEpochSecond());
            output.writeInt32(15, snapshot.updatedAt().getNano());
            output.writeBool(16, cached.stale());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode profile projection", e);
        }
    }

    private StoredProfile decode(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String storedNamespace = "";
        long playerId = 0;
        String name = "";
        int level = 1;
        String avatar = "";
        String frame = "";
        String costume = "";
        long allianceId = 0;
        String allianceName = "";
        String badge = "";
        int friendCount = 0;
        long socialRevision = 0;
        long revision = 0;
        long updatedSecond = 0;
        int updatedNano = 0;
        boolean stale = false;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> storedNamespace = input.readString();
                    case 2 -> playerId = input.readInt64();
                    case 3 -> name = input.readString();
                    case 4 -> level = input.readInt32();
                    case 5 -> avatar = input.readString();
                    case 6 -> frame = input.readString();
                    case 7 -> costume = input.readString();
                    case 8 -> allianceId = input.readInt64();
                    case 9 -> allianceName = input.readString();
                    case 10 -> badge = input.readString();
                    case 11 -> friendCount = input.readInt32();
                    case 12 -> socialRevision = input.readInt64();
                    case 13 -> revision = input.readInt64();
                    case 14 -> updatedSecond = input.readInt64();
                    case 15 -> updatedNano = input.readInt32();
                    case 16 -> stale = input.readBool();
                    default -> input.skipField(tag);
                }
            }
            PlayerProfileSnapshot snapshot = new PlayerProfileSnapshot(
                    playerId,
                    name,
                    level,
                    new AppearanceSummary(avatar, frame, costume),
                    new AllianceBrief(allianceId, allianceName, badge),
                    new FriendBrief(friendCount, socialRevision),
                    revision,
                    Instant.ofEpochSecond(updatedSecond, updatedNano)
            );
            return new StoredProfile(storedNamespace, new CachedProfile(snapshot, stale));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode profile projection", e);
        }
    }

    private String key(long playerId) {
        String identity = namespace.length() + ":" + namespace + ":" + playerId;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record StoredProfile(String namespace, CachedProfile profile) {
    }
}
