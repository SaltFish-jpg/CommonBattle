package com.commonbattle.game.snapshot;

import java.util.OptionalLong;

/**
 * 将版本事件 ownerKey 解析为业务快照 owner id。
 * 不同业务可以使用 profile:10001、alliance:100 这类稳定前缀隔离命名空间。
 */
@FunctionalInterface
public interface SnapshotOwnerKeyParser {
    OptionalLong parse(String ownerKey);
}
