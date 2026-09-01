package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorRef;

/**
 * 本服 ask 无法投递到目标 Actor。
 */
public final class LocalAskDeliveryException extends RuntimeException {
    public LocalAskDeliveryException(ActorRef target) {
        super("Local ask delivery failed, target=" + target.id());
    }
}
