package com.commonbattle.game.shop;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商店配置表。
 * 商品配置可来自静态表、热更配置或活动生成，本目录只提供运行时查询入口。
 */
public final class ShopCatalog {
    private final Map<String, ShopItemDefinition> definitions = new ConcurrentHashMap<>();

    public void register(ShopItemDefinition definition) {
        definitions.put(Objects.requireNonNull(definition, "definition").sku(), definition);
    }

    public Optional<ShopItemDefinition> find(String sku) {
        return Optional.ofNullable(definitions.get(sku));
    }

    public ShopItemDefinition require(String sku) {
        return find(sku).orElseThrow(() -> new IllegalArgumentException("Unknown shop item " + sku));
    }

    public List<ShopItemDefinition> definitions() {
        return List.copyOf(definitions.values());
    }
}
