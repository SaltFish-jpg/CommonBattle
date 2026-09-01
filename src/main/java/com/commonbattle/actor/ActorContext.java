package com.commonbattle.actor;

/**
 * Actor 消息执行期间可访问的运行时上下文。
 * 任务可通过它获取当前 Actor 标识，或继续向任意 Actor 投递后续消息。
 */
public final class ActorContext {
    private final ActorSystem system;
    private final ActorRef self;

    ActorContext(ActorSystem system, ActorRef self) {
        this.system = system;
        this.self = self;
    }

    public ActorSystem system() {
        return system;
    }

    public ActorRef self() {
        return self;
    }

    public void send(ActorRef target, ActorTask task) {
        system.send(target, task);
    }
}
