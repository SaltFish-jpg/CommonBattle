package com.commonbattle.actor;

/**
 * 目标 Actor 邮箱已满。
 */
public final class MailboxFullException extends RuntimeException {
    public MailboxFullException(ActorRef target) {
        super("Actor mailbox is full: " + target.id());
    }
}
