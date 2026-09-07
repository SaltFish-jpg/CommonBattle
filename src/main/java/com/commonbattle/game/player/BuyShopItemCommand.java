package com.commonbattle.game.player;

import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.player.event.ShopItemPurchasedEvent;

import java.util.Objects;

/**
 * 商店购买示例业务命令。
 */
public record BuyShopItemCommand(String orderId, String sku, int quantity) implements PlayerBusinessCommand<ShopPurchaseResult> {
    public BuyShopItemCommand(String sku, int quantity) {
        this("", sku, quantity);
    }

    public BuyShopItemCommand {
        orderId = Objects.requireNonNullElse(orderId, "");
        Objects.requireNonNull(sku, "sku");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    @Override
    public String operation() {
        return PlayerBusinessOperations.SHOP_BUY;
    }

    @Override
    public ShopPurchaseResult execute(PlayerGameExecution execution) {
        ShopPurchaseResult result = execution.runtime().requireShopService().purchaseAt(
                execution.profile().bag(),
                execution.profile().shop(),
                orderId,
                sku,
                quantity,
                execution.activityAccess().now()
        );
        if (result.success()) {
            execution.publish(ShopItemPurchasedEvent.from(execution.profile().playerId(), result));
        }
        return result;
    }
}
