package com.commonbattle.game.battle;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PVE 战斗关卡目录。
 * 配置热更后每个 GameConfigRuntime 持有独立目录，避免同一条玩家命令读取到跨版本关卡参数。
 */
public final class BattleStageCatalog {
    private final Map<String, BattleStageDefinition> stages = new LinkedHashMap<>();

    public void register(BattleStageDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (stages.putIfAbsent(definition.stageId(), definition) != null) {
            throw new IllegalArgumentException("Duplicate battle stage: " + definition.stageId());
        }
    }

    public BattleStageDefinition require(String stageId) {
        Objects.requireNonNull(stageId, "stageId");
        BattleStageDefinition definition = stages.get(stageId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown battle stage: " + stageId);
        }
        return definition;
    }

    public Collection<BattleStageDefinition> definitions() {
        return java.util.List.copyOf(stages.values());
    }
}
