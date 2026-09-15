package com.commonbattle.actor;

/**
 * Actor 业务定时调度观测视图。
 * 健康探针通过该接口采集活动刷新、场景 tick、自动存盘等定时投递状态。
 */
public interface ActorScheduleView {
    ActorScheduleStats scheduleStats();
}
