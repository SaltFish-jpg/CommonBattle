package com.commonbattle.actor.agent;

import java.util.Objects;

/**
 * 业务 Agent 的逻辑身份。
 * 同一个身份在集群内只能有一个真实 owner，其他服务只能缓存位置或持有远程代理。
 */
public record AgentIdentity(String type, String key) {
    public static final String PLAYER = "player";
    public static final String PROFILE = "profile";
    public static final String FRIEND = "friend";
    public static final String SCENE = "scene";
    public static final String ALLIANCE = "alliance";

    public AgentIdentity {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        if (type.isBlank()) {
            throw new IllegalArgumentException("agent type must not be blank");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("agent key must not be blank");
        }
    }

    public static AgentIdentity player(long playerId) {
        return new AgentIdentity(PLAYER, Long.toString(playerId));
    }

    public static AgentIdentity profile(long playerId) {
        return new AgentIdentity(PROFILE, Long.toString(playerId));
    }

    public static AgentIdentity friend(long playerId) {
        return new AgentIdentity(FRIEND, Long.toString(playerId));
    }

    public static AgentIdentity scene(String sceneId) {
        return new AgentIdentity(SCENE, sceneId);
    }

    public static AgentIdentity alliance(long allianceId) {
        return new AgentIdentity(ALLIANCE, Long.toString(allianceId));
    }

    public String wireName() {
        return type + ":" + key;
    }
}
