package com.commonbattle.game.profile;

import java.util.List;

/**
 * Profile 投影的字节级存储扩展点。
 */
public interface ProfileProjectionBytesStore {
    void save(String key, byte[] payload);

    List<byte[]> loadAll();
}
