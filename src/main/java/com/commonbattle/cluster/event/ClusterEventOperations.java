package com.commonbattle.cluster.event;

/**
 * 跨服事件通道操作名。
 */
public final class ClusterEventOperations {
    public static final String SUBSCRIBE = "event.subscribe";
    public static final String UNSUBSCRIBE = "event.unsubscribe";
    public static final String PUBLISH = "event.publish";
    public static final String DELIVER = "event.deliver";

    private ClusterEventOperations() {
    }
}
