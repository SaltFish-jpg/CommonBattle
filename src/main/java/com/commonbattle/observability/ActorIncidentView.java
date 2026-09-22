package com.commonbattle.observability;

import java.util.List;

/**
 * Actor 运行时异常事件视图。
 * 健康探针通过该接口聚合死信、毒消息数量和最近样本。
 */
public interface ActorIncidentView {
    ActorIncidentStats actorIncidentStats();

    List<ActorIncidentRecord> recentActorIncidents();
}
