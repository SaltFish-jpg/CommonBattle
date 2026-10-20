package com.commonbattle.game.event;

import java.util.List;

/**
 * Actor durable inbox 死信运维入口。
 * redrive 只负责恢复记录并重新投递邮箱，业务仍在目标 Actor 内串行执行。
 */
public interface EventInboxDeadLetterAdmin {
    String target();

    String topic();

    List<DeadLetteredEventInboxEntry> inboxDeadLetters();

    boolean redriveInboxDeadLetter(EventInboxKey key);
}
