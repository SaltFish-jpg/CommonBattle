package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.agent.AgentIdentity;

/**
 * 大场景 shard Agent 身份 key。
 * 新协议统一使用 {@code AgentIdentity.sceneShard(sceneId, shardIndex)}；旧的 scene 类型 key 仅用于迁移任务兼容读取。
 */
public record LargeSceneShardAgentKey(String sceneId, int shardIndex) {
    private static final String LEGACY_DELIMITER = ":shard-";

    public LargeSceneShardAgentKey {
        if (sceneId == null || sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        if (sceneId.contains(LEGACY_DELIMITER)) {
            throw new IllegalArgumentException("sceneId must not contain " + LEGACY_DELIMITER);
        }
        if (shardIndex < 0) {
            throw new IllegalArgumentException("shardIndex must not be negative");
        }
    }

    public String wireName() {
        return sceneId + LEGACY_DELIMITER + shardIndex;
    }

    public AgentIdentity identity() {
        return AgentIdentity.sceneShard(sceneId, shardIndex);
    }

    public static LargeSceneShardAgentKey fromIdentity(AgentIdentity identity) {
        if (AgentIdentity.SCENE_SHARD.equals(identity.type())) {
            return parseCanonical(identity.key());
        }
        if (AgentIdentity.SCENE.equals(identity.type())) {
            return parse(identity.key());
        }
        throw new IllegalArgumentException("unsupported migration identity: " + identity.type());
    }

    public static LargeSceneShardAgentKey parse(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        int delimiter = key.lastIndexOf(LEGACY_DELIMITER);
        if (delimiter <= 0 || delimiter + LEGACY_DELIMITER.length() >= key.length()) {
            throw new IllegalArgumentException("invalid large scene shard key: " + key);
        }
        String sceneId = key.substring(0, delimiter);
        try {
            int shardIndex = Integer.parseInt(key.substring(delimiter + LEGACY_DELIMITER.length()));
            return new LargeSceneShardAgentKey(sceneId, shardIndex);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid large scene shard index: " + key, e);
        }
    }

    private static LargeSceneShardAgentKey parseCanonical(String key) {
        int delimiter = key.lastIndexOf(':');
        if (delimiter <= 0 || delimiter == key.length() - 1) {
            throw new IllegalArgumentException("invalid canonical large scene shard key: " + key);
        }
        try {
            return new LargeSceneShardAgentKey(
                    key.substring(0, delimiter),
                    Integer.parseInt(key.substring(delimiter + 1))
            );
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid canonical large scene shard index: " + key, e);
        }
    }
}
