package com.commonbattle.game.event;

/**
 * 事件发布结果回调。
 * 可靠发布器只在下游明确确认接收后提交 outbox，失败或超时则保留事件等待重试。
 */
public interface EventPublishCallback {
    void success();

    void failure(Throwable error);
}
