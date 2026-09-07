package com.commonbattle.game.player.event;

import com.commonbattle.game.shop.ShopPurchaseResult;

import java.util.Objects;

/**
 * 玩家购买商品成功后产生的业务事件。
 */
public record ShopItemPurchasedEvent(long playerId, String sku, int quantity, boolean replayed)
        implements PlayerDomainEvent {
    public static final String TYPE = "shop.item.purchased";

    public ShopItemPurchasedEvent {
        Objects.requireNonNull(sku, "sku");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public static ShopItemPurchasedEvent from(long playerId, ShopPurchaseResult result) {
        Objects.requireNonNull(result, "result");
        return new ShopItemPurchasedEvent(playerId, result.sku(), result.quantity(), result.replayed());
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String subject() {
        return sku;
    }

    @Override
    public int delta() {
        return quantity;
    }
}
