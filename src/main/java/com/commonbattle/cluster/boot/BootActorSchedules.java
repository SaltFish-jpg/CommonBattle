package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorSystem;

import java.util.Objects;

/**
 * Actor 业务定时调度启动装配。
 * Game、Scene 等进程共用同一个调度表类型，方便 health/metrics 统一观测定时消息是否成功进入 mailbox。
 */
final class BootActorSchedules {
    private BootActorSchedules() {
    }

    static ActorScheduleRegistry configure(BootRuntime runtime, ActorSystem actors) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(actors, "actors");
        return runtime.add("actorSchedules", new ActorScheduleRegistry(actors));
    }
}
