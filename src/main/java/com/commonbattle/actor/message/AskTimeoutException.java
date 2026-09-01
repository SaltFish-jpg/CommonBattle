package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorRef;

import java.time.Duration;

/**
 * 本服 ask 超时。
 */
public final class AskTimeoutException extends RuntimeException {
    public AskTimeoutException(ActorRef target, Duration timeout) {
        super("Local ask timeout, target=" + target.id() + ", timeout=" + timeout);
    }
}
