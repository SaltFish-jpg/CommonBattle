package com.commonbattle.game.chat;

/**
 * Chat 版本化快照序列化边界。
 */
public interface ChatStateSerializer {
    byte[] serialize(VersionedChatState state);

    VersionedChatState deserialize(byte[] bytes);
}
