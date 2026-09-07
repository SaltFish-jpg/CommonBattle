package com.commonbattle.game.profile;

/**
 * ProfileRuntime 的只读观测视图。
 * 运维探针通过该接口聚合本地命中、回源刷新和降级读取情况。
 */
@FunctionalInterface
public interface ProfileRuntimeView {
    ProfileRuntimeStats stats();
}
