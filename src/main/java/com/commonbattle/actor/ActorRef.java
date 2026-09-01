package com.commonbattle.actor;

import java.util.Objects;

/**
 * Actor 的稳定引用。
 * 上层业务通常把一个玩家、场景或跨服服务映射为一个 ActorRef，再通过引用投递消息。
 */
public record ActorRef(String id) {
    public ActorRef {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Actor id must not be blank");
        }
    }
}
