package com.commonbattle.actor;

import java.util.Objects;

/**
 * Actor 定时任务业务键。
 * namespace 表达任务归属域，id 表达同域内唯一业务对象，用于防止重复注册同一个玩家、场景或系统任务。
 */
public record ActorScheduleKey(String namespace, String id) {
    public ActorScheduleKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(id, "id");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    public static ActorScheduleKey of(String namespace, long id) {
        return new ActorScheduleKey(namespace, Long.toString(id));
    }
}
