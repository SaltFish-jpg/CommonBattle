package com.commonbattle.game.player;

/**
 * 玩家状态快照序列化器。
 * 生产实现应保持字段号只追加，允许灰度期间旧版本跳过未知字段。
 */
public interface PlayerStateSnapshotSerializer {
    byte[] serialize(PlayerStateSnapshot snapshot);

    PlayerStateSnapshot deserialize(byte[] bytes);
}
