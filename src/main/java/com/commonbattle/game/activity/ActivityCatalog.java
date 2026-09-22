package com.commonbattle.game.activity;

import com.commonbattle.game.GameBusinessErrorCodes;
import com.commonbattle.game.GameBusinessIllegalArgumentException;

import java.util.Map;
import java.util.Objects;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动配置目录。
 * 后续可替换为配置中心或热更表，业务模块只依赖查询接口。
 */
public final class ActivityCatalog {
    private final Map<String, ActivityDefinition> definitions = new ConcurrentHashMap<>();

    public void register(ActivityDefinition definition) {
        definitions.put(Objects.requireNonNull(definition, "definition").activityId(), definition);
    }

    public ActivityDefinition require(String activityId) {
        ActivityDefinition definition = definitions.get(activityId);
        if (definition == null) {
            throw new GameBusinessIllegalArgumentException(
                    GameBusinessErrorCodes.ACTIVITY_UNKNOWN,
                    "Unknown activity " + activityId
            );
        }
        return definition;
    }

    public Collection<ActivityDefinition> definitions() {
        return java.util.List.copyOf(definitions.values());
    }
}
