package com.commonbattle.actor;

/**
 * ActorSystem 已关闭，不再接受外部消息。
 */
public final class ActorSystemClosedException extends RuntimeException {
    public ActorSystemClosedException(ActorRef target) {
        super("Actor system is closed, target=" + target.id());
    }
}
