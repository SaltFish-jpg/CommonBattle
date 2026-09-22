package com.commonbattle.example.cross.scene;

/**
 * 大场景 shard Agent 身份 key。
 * 格式固定为 sceneId:shard-index，供 AgentIdentity.scene(key) 复用。
 */
public record LargeSceneShardAgentKey(String sceneId, int shardIndex) {
    private static final String DELIMITER = ":shard-";

    public LargeSceneShardAgentKey {
        if (sceneId == null || sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        if (sceneId.contains(DELIMITER)) {
            throw new IllegalArgumentException("sceneId must not contain " + DELIMITER);
        }
        if (shardIndex < 0) {
            throw new IllegalArgumentException("shardIndex must not be negative");
        }
    }

    public String wireName() {
        return sceneId + DELIMITER + shardIndex;
    }

    public static LargeSceneShardAgentKey parse(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        int delimiter = key.lastIndexOf(DELIMITER);
        if (delimiter <= 0 || delimiter + DELIMITER.length() >= key.length()) {
            throw new IllegalArgumentException("invalid large scene shard key: " + key);
        }
        String sceneId = key.substring(0, delimiter);
        try {
            int shardIndex = Integer.parseInt(key.substring(delimiter + DELIMITER.length()));
            return new LargeSceneShardAgentKey(sceneId, shardIndex);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid large scene shard index: " + key, e);
        }
    }
}
