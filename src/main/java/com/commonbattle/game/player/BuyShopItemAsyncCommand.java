package com.commonbattle.game.player;

import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.session.PlayerCommand;

import java.util.Objects;

/**
 * 异步商店购买命令。
 * 它使用与同步购买相同的 shop.buy 操作名，区别是有限库存预占通过异步 RPC 回包回投玩家 mailbox 后再结算。
 */
public record BuyShopItemAsyncCommand(String orderId, String sku, int quantity)
        implements AsyncPlayerBusinessCommand<ShopPurchaseResult> {
    public BuyShopItemAsyncCommand(String sku, int quantity) {
        this("", sku, quantity);
    }

    public BuyShopItemAsyncCommand {
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
    public void executeAsync(PlayerGameAgent agent, PlayerCommand command, PlayerBusinessResultSink results) {
        agent.buyShopItemAsync(command, orderId, sku, quantity, results);
    }
}
