package com.commonbattle.game;

/**
 * 成功业务信封内可观测的领域结果状态。
 * 适用于商店购买、任务领取、抽卡等用结果对象表达业务拒绝的玩法。
 */
public interface GameBusinessResultStatus {
    boolean businessResultRejected();

    String businessResultCode();
}
