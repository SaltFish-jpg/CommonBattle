package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.observability.InMemoryActorIncidentLog;
import com.commonbattle.observability.InMemoryActorSlowTaskLog;

import java.time.Clock;

/**
 * 启动 ActorSystem 的统一入口。
 * 所有独立进程都在这里挂接死信和毒消息日志，保证运维指标口径一致。
 */
final class BootActors {
    private BootActors() {
    }

    static ActorSystem configure(BootRuntime runtime, ClusterNodeConfig config, Clock clock) {
        InMemoryActorIncidentLog incidents = new InMemoryActorIncidentLog(
                config.actorIncidentLogCapacity(),
                clock
        );
        InMemoryActorSlowTaskLog slowTasks = new InMemoryActorSlowTaskLog(
                config.actorSlowTaskLogCapacity(),
                clock
        );
        runtime.observe("actorIncidents", incidents);
        runtime.observe("actorSlowTasks", slowTasks);
        return runtime.add("actors", new ActorSystem(config.actorSystemConfig(), incidents, incidents, slowTasks));
    }
}
