package com.commonbattle.cluster.event;

import com.commonbattle.game.event.VersionedEvent;

import java.util.List;
import java.util.Set;

/**
 * 中心服持久化事件日志。
 * 实现必须以 topic、ownerKey、revision 幂等，并原子完成追加与 topic 历史窗口裁剪。
 */
public interface ClusterEventJournal {
    ClusterEventJournalAppendResult appendAndTrim(VersionedEvent event, int historyLimit);

    List<VersionedEvent> retained(String topic);

    long highWaterMark(String topic, String ownerKey);

    Set<String> topics();
}
