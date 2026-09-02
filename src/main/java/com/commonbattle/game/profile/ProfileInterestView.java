package com.commonbattle.game.profile;

/**
 * Profile 动态关注订阅的只读观测视图。
 * 运维探针通过该接口读取关注数量、replay 和 repair 结果，不参与订阅生命周期控制。
 */
@FunctionalInterface
public interface ProfileInterestView {
    ProfileInterestStats stats();
}
