package com.commonbattle.game.chat;

/**
 * Chat 消息投递出口。
 * 实现方应避免阻塞 Actor 线程；网络发送、离线持久化等慢操作应继续转成异步投递。
 */
@FunctionalInterface
public interface ChatDeliverySink {
    ChatDeliveryResult deliver(ChatDeliveryEnvelope envelope);

    static ChatDeliverySink noop() {
        return envelope -> new ChatDeliveryResult(envelope.recipients().size(), 0, 0);
    }
}
