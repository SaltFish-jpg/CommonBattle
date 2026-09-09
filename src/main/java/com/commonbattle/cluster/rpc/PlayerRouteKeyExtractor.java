package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 从 RPC 请求中提取玩家路由键。
 * 默认实现读取 payload 的 playerId() 方法，适合 record 请求参数和普通业务 Bean。
 */
@FunctionalInterface
public interface PlayerRouteKeyExtractor {
    OptionalLong playerId(RpcRequest<?> request);

    static PlayerRouteKeyExtractor payloadPlayerIdMethod() {
        return request -> {
            Objects.requireNonNull(request, "request");
            Object payload = request.payload();
            if (payload == null) {
                return OptionalLong.empty();
            }
            try {
                Method method = payload.getClass().getMethod("playerId");
                Object value = method.invoke(payload);
                if (value instanceof Number number) {
                    return OptionalLong.of(number.longValue());
                }
                return OptionalLong.empty();
            } catch (NoSuchMethodException e) {
                return OptionalLong.empty();
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Failed to extract playerId from " + payload.getClass().getName(), e);
            }
        };
    }
}
