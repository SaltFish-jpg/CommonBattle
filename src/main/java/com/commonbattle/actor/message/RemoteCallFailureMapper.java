package com.commonbattle.actor.message;

import java.util.Locale;

/**
 * 远程调用失败到 AgentDeliveryResult 的映射器。
 * 框架默认只做通用兜底；具体 RPC 实现可注入更精确的异常分类。
 */
@FunctionalInterface
public interface RemoteCallFailureMapper {
    AgentDeliveryResult map(Throwable error);

    static RemoteCallFailureMapper defaults() {
        return error -> {
            String message = error == null ? "" : String.valueOf(error.getMessage());
            if (message.toLowerCase(Locale.ROOT).contains("closed")) {
                return AgentDeliveryResult.systemClosed();
            }
            return AgentDeliveryResult.remoteUnavailable(message);
        };
    }
}
