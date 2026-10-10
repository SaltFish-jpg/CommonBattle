package com.commonbattle.actor;

import java.util.Objects;
import java.util.Optional;

/**
 * Actor 消息执行期间可访问的运行时上下文。
 * 任务可通过它获取当前 Actor 标识，或继续向任意 Actor 投递后续消息。
 */
public final class ActorContext {
    private static final ThreadLocal<ActorContext> CURRENT = new ThreadLocal<>();

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

    /**
     * 返回当前线程正在执行的 Actor 上下文。
     * 只有在 ActorSystem 正在 drain 某个 mailbox 的消息时才会存在，RPC/Netty 回调线程应为空。
     */
    public static Optional<ActorContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 要求当前线程必须处于 Actor 消息执行中。
     * 用于保护只能在 mailbox 内读取或修改聚合状态的内部入口。
     */
    public static ActorContext requireCurrent() {
        ActorContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("operation must run inside an actor mailbox");
        }
        return context;
    }

    /**
     * 要求当前线程正在指定 Actor 的 mailbox 内执行。
     */
    public static ActorContext requireCurrent(ActorRef expected) {
        Objects.requireNonNull(expected, "expected");
        ActorContext context = requireCurrent();
        if (!context.self().equals(expected)) {
            throw new IllegalStateException("operation must run inside actor mailbox: " + expected.id());
        }
        return context;
    }

    static ActorContext installCurrent(ActorContext context) {
        ActorContext previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(context, "context"));
        return previous;
    }

    static void restoreCurrent(ActorContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public void send(ActorRef target, ActorTask task) {
        system.send(target, task);
    }
}
