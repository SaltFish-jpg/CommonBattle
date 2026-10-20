package com.commonbattle.game.chat;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.persistence.AtomicBytesStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 AtomicBytesStore 的跨进程 Chat 状态仓储。
 * version 保证同 owner 写入有序，ownerToken 保证租约丢失后的延迟任务无法覆盖新 owner。
 */
public final class SerializedChatStateStore implements ChatStateStore {
    private static final String KEY_PREFIX = "chat:state:";

    private final AtomicBytesStore store;
    private final ChatStateSerializer serializer;

    public SerializedChatStateStore(AtomicBytesStore store, ChatStateSerializer serializer) {
        this.store = Objects.requireNonNull(store, "store");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public Optional<VersionedChatState> load(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return store.load(storageKey(identity)).map(serializer::deserialize);
    }

    @Override
    public ChatStateSaveResult save(
            AgentIdentity identity,
            long expectedVersion,
            long ownerToken,
            ChatChannelSnapshot snapshot
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(snapshot, "snapshot");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (ownerToken < 0) {
            throw new IllegalArgumentException("ownerToken must not be negative");
        }
        String key = storageKey(identity);
        while (true) {
            Optional<byte[]> loaded = store.load(key);
            if (loaded.isEmpty()) {
                if (expectedVersion != 0) {
                    return new ChatStateSaveResult(ChatStateSaveStatus.VERSION_CONFLICT, Optional.empty());
                }
                VersionedChatState created = new VersionedChatState(1, ownerToken, snapshot);
                if (store.putIfAbsent(key, serializer.serialize(created))) {
                    return ChatStateSaveResult.applied(created);
                }
                continue;
            }
            byte[] currentBytes = loaded.orElseThrow();
            VersionedChatState current = serializer.deserialize(currentBytes);
            if (ownerToken < current.ownerToken()) {
                return ChatStateSaveResult.staleOwner(current);
            }
            if (expectedVersion != current.version()) {
                return ChatStateSaveResult.conflict(current);
            }
            VersionedChatState updated = new VersionedChatState(
                    Math.addExact(current.version(), 1), ownerToken, snapshot
            );
            if (store.compareAndSet(key, currentBytes, serializer.serialize(updated))) {
                return ChatStateSaveResult.applied(updated);
            }
        }
    }

    private static String storageKey(AgentIdentity identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, identity.type());
            update(digest, identity.key());
            return KEY_PREFIX + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
