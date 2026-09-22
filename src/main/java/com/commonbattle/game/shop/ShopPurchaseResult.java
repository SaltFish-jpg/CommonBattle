package com.commonbattle.game.shop;

import com.commonbattle.game.GameBusinessResultStatus;
import com.commonbattle.game.bag.BagResult;

import java.util.List;
import java.util.Objects;

/**
 * 商店购买结果。
 * 成功时包含扣款和发奖的背包变更；失败时背包变更为空。
 */
public record ShopPurchaseResult(
        ShopPurchaseStatus status,
        String sku,
        int quantity,
        BagResult cost,
        BagResult reward,
        int lifetimePurchased,
        int dailyPurchased,
        boolean replayed
) implements GameBusinessResultStatus {
    private static final BagResult EMPTY_BAG_RESULT = new BagResult(List.of());

    public ShopPurchaseResult(
            ShopPurchaseStatus status,
            String sku,
            int quantity,
            BagResult cost,
            BagResult reward,
            int lifetimePurchased,
            int dailyPurchased
    ) {
        this(status, sku, quantity, cost, reward, lifetimePurchased, dailyPurchased, false);
    }

    public ShopPurchaseResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(reward, "reward");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public boolean success() {
        return status == ShopPurchaseStatus.SUCCESS;
    }

    @Override
    public boolean businessResultRejected() {
        return !success();
    }

    @Override
    public String businessResultCode() {
        return "SHOP_" + status.name();
    }

    public static ShopPurchaseResult rejected(ShopPurchaseStatus status, String sku, int quantity,
                                              int lifetimePurchased, int dailyPurchased) {
        return new ShopPurchaseResult(status, sku, quantity, EMPTY_BAG_RESULT, EMPTY_BAG_RESULT,
                lifetimePurchased, dailyPurchased, false);
    }

    public ShopPurchaseResult asReplayed() {
        return new ShopPurchaseResult(status, sku, quantity, cost, reward, lifetimePurchased, dailyPurchased, true);
    }
}
