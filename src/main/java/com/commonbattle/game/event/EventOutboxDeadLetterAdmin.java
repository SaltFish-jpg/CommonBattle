package com.commonbattle.game.event;

import java.util.List;

/**
 * 事件 outbox 死信运维入口。
 * 实现应在 redrive 后通过原可靠发布链投递，不能绕过中心 ACK。
 */
public interface EventOutboxDeadLetterAdmin {
    String target();

    List<DeadLetteredVersionedEvent> outboxDeadLetters();

    boolean redriveOutboxDeadLetter(long outboxId);
}
