package com.commonbattle.cluster.event;

import java.util.Set;

/**
 * replay 历史窗口不足时的回源修补入口。
 * 业务侧可实现为拉 ProfileSnapshot、配置快照或联盟成员快照，然后原子刷新本地 cache。
 */
@FunctionalInterface
public interface EventReplayRepairer {
    void repair(String topic, Set<String> ownerKeys);

    static EventReplayRepairer noop() {
        return (topic, ownerKeys) -> {
        };
    }
}
