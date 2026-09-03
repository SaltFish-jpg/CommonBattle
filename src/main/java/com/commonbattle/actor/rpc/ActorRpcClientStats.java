package com.commonbattle.actor.rpc;

import com.commonbattle.actor.message.AgentDeliveryStatus;

import java.util.Map;

/**
 * Actor 绑定 RPC 客户端运行统计。
 * 用于观察业务 RPC 模板层的成功、失败分类，以及回包重新投递 owner 邮箱失败的情况。
 */
public record ActorRpcClientStats(
        long calls,
        long succeededResponses,
        long failedResponses,
        long callbackDeliveryFailures,
        Map<AgentDeliveryStatus, Long> failedResponsesByStatus
) {
    public static ActorRpcClientStats empty() {
        return new ActorRpcClientStats(0, 0, 0, 0, Map.of());
    }
}
