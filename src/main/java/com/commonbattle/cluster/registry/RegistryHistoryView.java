package com.commonbattle.cluster.registry;

/**
 * 暴露注册中心事件历史水位。
 * 中心服用它观察订阅端是否可能因为历史压缩而回退到全量快照修复。
 */
public interface RegistryHistoryView {
    RegistryHistoryStats stats();
}
