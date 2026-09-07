package com.commonbattle.game.achievement;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家成就配置目录。
 */
public final class AchievementCatalog {
    private final Map<String, AchievementDefinition> achievements = new LinkedHashMap<>();

    public void register(AchievementDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (achievements.putIfAbsent(definition.achievementId(), definition) != null) {
            throw new IllegalArgumentException("Duplicate achievement: " + definition.achievementId());
        }
    }

    public AchievementDefinition require(String achievementId) {
        Objects.requireNonNull(achievementId, "achievementId");
        AchievementDefinition definition = achievements.get(achievementId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown achievement: " + achievementId);
        }
        return definition;
    }

    public Collection<AchievementDefinition> definitions() {
        return java.util.List.copyOf(achievements.values());
    }
}
