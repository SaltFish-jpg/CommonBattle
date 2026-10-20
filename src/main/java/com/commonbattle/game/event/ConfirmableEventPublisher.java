package com.commonbattle.game.event;

/**
 * 支持下游接收确认的事件发布端口。
 * 跨进程实现应在目标服务完成持久化或纳入可恢复历史后才调用成功回调。
 */
public interface ConfirmableEventPublisher extends EventPublisher {
    void publish(VersionedEvent event, EventPublishCallback callback);

    @Override
    default void publish(VersionedEvent event) {
        publish(event, IgnoreEventPublishCallback.INSTANCE);
    }

    enum IgnoreEventPublishCallback implements EventPublishCallback {
        INSTANCE;

        @Override
        public void success() {
        }

        @Override
        public void failure(Throwable error) {
        }
    }
}
