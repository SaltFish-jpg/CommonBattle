package com.commonbattle.game.bag;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品配置表。
 * 背包只依赖这个目录查询堆叠规则，具体物品效果交给玩法模块处理。
 */
public final class ItemCatalog {
    private final Map<String, ItemDefinition> definitions = new ConcurrentHashMap<>();

    public void register(ItemDefinition definition) {
        definitions.put(Objects.requireNonNull(definition, "definition").itemId(), definition);
    }

    public ItemDefinition require(String itemId) {
        ItemDefinition definition = definitions.get(itemId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown item " + itemId);
        }
        return definition;
    }
}
