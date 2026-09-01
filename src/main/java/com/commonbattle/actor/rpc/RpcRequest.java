package com.commonbattle.actor.rpc;

import java.util.Objects;

/**
 * 一次异步 RPC 调用的描述。
 * 上层可用 target 和 operation 对接网关路由，payload 保持为业务自定义请求对象。
 */
public record RpcRequest<T>(String target, String operation, Object payload, Class<T> responseType) {
    public RpcRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(responseType, "responseType");
        if (target.isBlank()) {
            throw new IllegalArgumentException("RPC target must not be blank");
        }
        if (operation.isBlank()) {
            throw new IllegalArgumentException("RPC operation must not be blank");
        }
    }
}
