package com.commonbattle.observability;

import java.util.List;

/**
 * Actor 慢任务只读视图。
 */
public interface ActorSlowTaskView {
    ActorSlowTaskStats actorSlowTaskStats();

    List<ActorSlowTaskRecord> recentActorSlowTasks();
}
