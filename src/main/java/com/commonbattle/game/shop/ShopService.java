package com.commonbattle.game.shop;

import com.commonbattle.game.bag.BagResult;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.bag.Reward;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用商店结算服务。
 * 调用方负责在玩家 Agent 邮箱内调用本服务；服务内部只对全服库存使用独立原子存储。
 */
public final class ShopService implements ShopRuntimeView {
    private final ShopCatalog catalog;
    private final BagService bags;
    private final ShopStockRepository stocks;
    private final ShopOrderRepository orders;
    private final Clock clock;
    private final ZoneId resetZone;
    private final AtomicLong purchaseRequests = new AtomicLong();
    private final AtomicLong successfulPurchases = new AtomicLong();
    private final AtomicLong idempotentReplays = new AtomicLong();
    private final AtomicLong unknownItems = new AtomicLong();
    private final AtomicLong lifetimeLimitRejected = new AtomicLong();
    private final AtomicLong dailyLimitRejected = new AtomicLong();
    private final AtomicLong notEnoughCurrency = new AtomicLong();
    private final AtomicLong outOfStock = new AtomicLong();
    private final AtomicLong orderConflicts = new AtomicLong();
    private final AtomicLong recordedOrders = new AtomicLong();

    public ShopService(ShopCatalog catalog, BagService bags, Clock clock, ZoneId resetZone) {
        this(catalog, bags, ShopStockRepository.unlimited(), clock, resetZone);
    }

    public ShopService(
            ShopCatalog catalog,
            BagService bags,
            ShopStockRepository stocks,
            Clock clock,
            ZoneId resetZone
    ) {
        this(catalog, bags, stocks, ShopOrderRepository.none(), clock, resetZone);
    }

    public ShopService(
            ShopCatalog catalog,
            BagService bags,
            ShopStockRepository stocks,
            ShopOrderRepository orders,
            Clock clock,
            ZoneId resetZone
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.bags = Objects.requireNonNull(bags, "bags");
        this.stocks = Objects.requireNonNull(stocks, "stocks");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.resetZone = Objects.requireNonNull(resetZone, "resetZone");
    }

    public ShopPurchaseResult purchase(PlayerBag bag, PlayerShopState state, String sku) {
        return purchase(bag, state, sku, 1);
    }

    public ShopPurchaseResult purchase(PlayerBag bag, PlayerShopState state, String sku, int quantity) {
        return purchaseAt(bag, state, sku, quantity, clock.instant());
    }

    public ShopPurchaseResult purchaseAt(
            PlayerBag bag,
            PlayerShopState state,
            String sku,
            int quantity,
            Instant now
    ) {
        return purchaseAt(bag, state, "", sku, quantity, now);
    }

    public ShopPurchaseResult purchaseAt(
            PlayerBag bag,
            PlayerShopState state,
            String orderId,
            String sku,
            int quantity,
            Instant now
    ) {
        Objects.requireNonNull(bag, "bag");
        Objects.requireNonNull(state, "state");
        orderId = Objects.requireNonNullElse(orderId, "");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(now, "now");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        purchaseRequests.incrementAndGet();
        if (!orderId.isBlank()) {
            var recorded = orders.find(orderId);
            if (recorded.isPresent()) {
                ShopPurchaseOrder order = recorded.orElseThrow();
                if (!order.matches(sku, quantity)) {
                    return rejected(ShopPurchaseStatus.ORDER_CONFLICT, sku, quantity,
                            state.lifetimePurchased(sku), state.dailyPurchased(sku, LocalDate.ofInstant(now, resetZone)));
                }
                idempotentReplays.incrementAndGet();
                return order.result().asReplayed();
            }
        }
        var definition = catalog.find(sku);
        if (definition.isEmpty()) {
            return rejected(ShopPurchaseStatus.UNKNOWN_ITEM, sku, quantity, 0, 0);
        }
        return purchaseKnown(bag, state, orderId, definition.orElseThrow(), quantity, now);
    }

    public boolean requiresStockReservation(String sku) {
        Objects.requireNonNull(sku, "sku");
        return catalog.find(sku).map(ShopItemDefinition::limitedStock).orElse(false);
    }

    public ShopPurchaseResult outOfStock(PlayerShopState state, String sku, int quantity, Instant now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(now, "now");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        return rejected(ShopPurchaseStatus.OUT_OF_STOCK, sku, quantity,
                state.lifetimePurchased(sku),
                state.dailyPurchased(sku, LocalDate.ofInstant(now, resetZone)));
    }

    public ShopPurchaseResult purchaseReservedAt(
            PlayerBag bag,
            PlayerShopState state,
            String orderId,
            String sku,
            int quantity,
            Instant now
    ) {
        Objects.requireNonNull(bag, "bag");
        Objects.requireNonNull(state, "state");
        orderId = Objects.requireNonNullElse(orderId, "");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(now, "now");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        purchaseRequests.incrementAndGet();
        if (!orderId.isBlank()) {
            var recorded = orders.find(orderId);
            if (recorded.isPresent()) {
                ShopPurchaseOrder order = recorded.orElseThrow();
                if (!order.matches(sku, quantity)) {
                    return rejected(ShopPurchaseStatus.ORDER_CONFLICT, sku, quantity,
                            state.lifetimePurchased(sku), state.dailyPurchased(sku, LocalDate.ofInstant(now, resetZone)));
                }
                idempotentReplays.incrementAndGet();
                return order.result().asReplayed();
            }
        }
        var definition = catalog.find(sku);
        if (definition.isEmpty()) {
            return rejected(ShopPurchaseStatus.UNKNOWN_ITEM, sku, quantity, 0, 0);
        }
        return purchaseKnownAfterStockReserved(bag, state, orderId, definition.orElseThrow(), quantity, now);
    }

    private ShopPurchaseResult purchaseKnown(
            PlayerBag bag,
            PlayerShopState state,
            String orderId,
            ShopItemDefinition definition,
            int quantity,
            Instant now
    ) {
        LocalDate today = LocalDate.ofInstant(now, resetZone);
        int lifetimePurchased = state.lifetimePurchased(definition.sku());
        int dailyPurchased = state.dailyPurchased(definition.sku(), today);
        if (exceeds(definition.lifetimeLimit(), lifetimePurchased, quantity)) {
            return rejected(ShopPurchaseStatus.LIFETIME_LIMIT_REACHED, definition.sku(), quantity,
                    lifetimePurchased, dailyPurchased);
        }
        if (exceeds(definition.dailyLimit(), dailyPurchased, quantity)) {
            return rejected(ShopPurchaseStatus.DAILY_LIMIT_REACHED, definition.sku(), quantity,
                    lifetimePurchased, dailyPurchased);
        }
        ItemStack cost = multiply(definition.price(), quantity);
        Reward reward = multiply(definition.reward(), quantity);
        bags.validate(cost);
        bags.validate(reward);
        if (!bag.has(cost.itemId(), cost.count())) {
            return rejected(ShopPurchaseStatus.NOT_ENOUGH_CURRENCY, definition.sku(), quantity,
                    lifetimePurchased, dailyPurchased);
        }
        boolean reserved = reserveStock(orderId, definition, quantity);
        if (!reserved) {
            return rejected(ShopPurchaseStatus.OUT_OF_STOCK, definition.sku(), quantity,
                    lifetimePurchased, dailyPurchased);
        }
        return completePurchase(bag, state, orderId, definition, quantity, now, lifetimePurchased, dailyPurchased, true);
    }

    private ShopPurchaseResult purchaseKnownAfterStockReserved(
            PlayerBag bag,
            PlayerShopState state,
            String orderId,
            ShopItemDefinition definition,
            int quantity,
            Instant now
    ) {
        LocalDate today = LocalDate.ofInstant(now, resetZone);
        int lifetimePurchased = state.lifetimePurchased(definition.sku());
        int dailyPurchased = state.dailyPurchased(definition.sku(), today);
        if (exceeds(definition.lifetimeLimit(), lifetimePurchased, quantity)) {
            return rejected(ShopPurchaseStatus.LIFETIME_LIMIT_REACHED, definition.sku(), quantity,
                    lifetimePurchased, dailyPurchased);
        }
        if (exceeds(definition.dailyLimit(), dailyPurchased, quantity)) {
            return rejected(ShopPurchaseStatus.DAILY_LIMIT_REACHED, definition.sku(), quantity,
                    lifetimePurchased, dailyPurchased);
        }
        ItemStack cost = multiply(definition.price(), quantity);
        Reward reward = multiply(definition.reward(), quantity);
        bags.validate(cost);
        bags.validate(reward);
        if (!bag.has(cost.itemId(), cost.count())) {
            return rejected(ShopPurchaseStatus.NOT_ENOUGH_CURRENCY, definition.sku(), quantity,
                    lifetimePurchased, dailyPurchased);
        }
        return completePurchase(bag, state, orderId, definition, quantity, now, lifetimePurchased, dailyPurchased, false);
    }

    private ShopPurchaseResult completePurchase(
            PlayerBag bag,
            PlayerShopState state,
            String orderId,
            ShopItemDefinition definition,
            int quantity,
            Instant now,
            int lifetimePurchased,
            int dailyPurchased,
            boolean releaseStockOnFailure
    ) {
        LocalDate today = LocalDate.ofInstant(now, resetZone);
        try {
            BagResult costResult = bags.consume(bag, multiply(definition.price(), quantity));
            BagResult rewardResult = bags.grant(bag, multiply(definition.reward(), quantity));
            state.record(definition.sku(), quantity, today);
            ShopPurchaseResult result = new ShopPurchaseResult(
                    ShopPurchaseStatus.SUCCESS,
                    definition.sku(),
                    quantity,
                    costResult,
                    rewardResult,
                    lifetimePurchased + quantity,
                    dailyPurchased + quantity
            );
            if (!orderId.isBlank()) {
                if (orders.saveIfAbsent(new ShopPurchaseOrder(orderId, definition.sku(), quantity, result, now))) {
                    recordedOrders.incrementAndGet();
                }
            }
            successfulPurchases.incrementAndGet();
            return result;
        } catch (RuntimeException e) {
            if (releaseStockOnFailure) {
                releaseStock(orderId, definition, quantity);
            }
            throw e;
        }
    }

    @Override
    public ShopRuntimeStats stats() {
        return new ShopRuntimeStats(
                purchaseRequests.get(),
                successfulPurchases.get(),
                idempotentReplays.get(),
                unknownItems.get(),
                lifetimeLimitRejected.get(),
                dailyLimitRejected.get(),
                notEnoughCurrency.get(),
                outOfStock.get(),
                orderConflicts.get(),
                recordedOrders.get()
        );
    }

    private ShopPurchaseResult rejected(
            ShopPurchaseStatus status,
            String sku,
            int quantity,
            int lifetimePurchased,
            int dailyPurchased
    ) {
        switch (status) {
            case UNKNOWN_ITEM -> unknownItems.incrementAndGet();
            case LIFETIME_LIMIT_REACHED -> lifetimeLimitRejected.incrementAndGet();
            case DAILY_LIMIT_REACHED -> dailyLimitRejected.incrementAndGet();
            case NOT_ENOUGH_CURRENCY -> notEnoughCurrency.incrementAndGet();
            case OUT_OF_STOCK -> outOfStock.incrementAndGet();
            case ORDER_CONFLICT -> orderConflicts.incrementAndGet();
            case SUCCESS -> {
            }
        }
        return ShopPurchaseResult.rejected(status, sku, quantity, lifetimePurchased, dailyPurchased);
    }

    private boolean reserveStock(String orderId, ShopItemDefinition definition, int quantity) {
        return !definition.limitedStock() || stocks.reserve(orderId, definition.sku(), quantity);
    }

    private void releaseStock(String orderId, ShopItemDefinition definition, int quantity) {
        if (definition.limitedStock()) {
            stocks.release(orderId, definition.sku(), quantity);
        }
    }

    private static boolean exceeds(int limit, int current, int adding) {
        return limit > 0 && current + adding > limit;
    }

    private static ItemStack multiply(ItemStack item, int quantity) {
        return new ItemStack(item.itemId(), Math.multiplyExact(item.count(), quantity));
    }

    private static Reward multiply(Reward reward, int quantity) {
        return new Reward(reward.items().stream()
                .map(item -> multiply(item, quantity))
                .toList());
    }
}
