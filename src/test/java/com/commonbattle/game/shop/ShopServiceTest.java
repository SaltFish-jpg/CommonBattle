package com.commonbattle.game.shop;

import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.bag.Reward;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final ZoneId RESET_ZONE = ZoneOffset.UTC;

    @Test
    void purchaseConsumesCurrencyAndGrantsReward() {
        Fixture fixture = Fixture.create();
        fixture.bags.grant(fixture.bag, Reward.of(new ItemStack("gold", 500)));

        ShopPurchaseResult result = fixture.service.purchase(fixture.bag, fixture.playerShop, "growth_pack");

        assertTrue(result.success());
        assertEquals(ShopPurchaseStatus.SUCCESS, result.status());
        assertEquals(400, fixture.bag.count("gold"));
        assertEquals(5, fixture.bag.count("stone"));
        assertEquals(1, fixture.playerShop.lifetimePurchased("growth_pack"));
        assertEquals(1, fixture.playerShop.dailyPurchased("growth_pack", java.time.LocalDate.of(2026, 9, 1)));
        assertEquals(1, result.cost().changes().size());
        assertEquals(1, result.reward().changes().size());
    }

    @Test
    void purchaseCanBuyMultipleCopiesWhenLimitsAllow() {
        Fixture fixture = Fixture.create();
        fixture.bags.grant(fixture.bag, Reward.of(new ItemStack("gold", 500)));

        ShopPurchaseResult result = fixture.service.purchase(fixture.bag, fixture.playerShop, "growth_pack", 2);

        assertTrue(result.success());
        assertEquals(300, fixture.bag.count("gold"));
        assertEquals(10, fixture.bag.count("stone"));
        assertEquals(2, result.dailyPurchased());
    }

    @Test
    void rejectsWhenCurrencyIsNotEnough() {
        Fixture fixture = Fixture.create();
        fixture.bags.grant(fixture.bag, Reward.of(new ItemStack("gold", 50)));

        ShopPurchaseResult result = fixture.service.purchase(fixture.bag, fixture.playerShop, "growth_pack");

        assertEquals(ShopPurchaseStatus.NOT_ENOUGH_CURRENCY, result.status());
        assertFalse(result.success());
        assertEquals(50, fixture.bag.count("gold"));
        assertEquals(0, fixture.bag.count("stone"));
        assertEquals(0, fixture.playerShop.lifetimePurchased("growth_pack"));
    }

    @Test
    void rejectsWhenLifetimeLimitIsReached() {
        Fixture fixture = Fixture.create();
        fixture.bags.grant(fixture.bag, Reward.of(new ItemStack("gold", 500)));
        fixture.service.purchase(fixture.bag, fixture.playerShop, "growth_pack", 2);

        ShopPurchaseResult result = fixture.service.purchase(fixture.bag, fixture.playerShop, "growth_pack");

        assertEquals(ShopPurchaseStatus.LIFETIME_LIMIT_REACHED, result.status());
        assertEquals(300, fixture.bag.count("gold"));
        assertEquals(10, fixture.bag.count("stone"));
    }

    @Test
    void rejectsWhenDailyLimitIsReached() {
        Fixture fixture = Fixture.create();
        fixture.catalog.register(new ShopItemDefinition(
                "daily_pack",
                new ItemStack("gold", 10),
                Reward.of(new ItemStack("stone", 1)),
                0,
                1,
                ShopItemDefinition.UNLIMITED_STOCK
        ));
        fixture.bags.grant(fixture.bag, Reward.of(new ItemStack("gold", 500)));
        fixture.service.purchase(fixture.bag, fixture.playerShop, "daily_pack");

        ShopPurchaseResult result = fixture.service.purchase(fixture.bag, fixture.playerShop, "daily_pack");

        assertEquals(ShopPurchaseStatus.DAILY_LIMIT_REACHED, result.status());
        assertEquals(490, fixture.bag.count("gold"));
        assertEquals(1, fixture.bag.count("stone"));
    }

    @Test
    void globalStockIsReservedAcrossPlayers() {
        Fixture first = Fixture.create();
        Fixture second = first.newPlayer();
        first.bags.grant(first.bag, Reward.of(new ItemStack("gold", 500)));
        second.bags.grant(second.bag, Reward.of(new ItemStack("gold", 500)));

        ShopPurchaseResult firstBuy = first.service.purchase(first.bag, first.playerShop, "limited_pack");
        ShopPurchaseResult secondBuy = second.service.purchase(second.bag, second.playerShop, "limited_pack");

        assertEquals(ShopPurchaseStatus.SUCCESS, firstBuy.status());
        assertEquals(ShopPurchaseStatus.OUT_OF_STOCK, secondBuy.status());
        assertEquals(0, first.stocks.remaining("limited_pack"));
        assertEquals(1, first.bag.count("ticket"));
        assertEquals(0, second.bag.count("ticket"));
    }

    @Test
    void unknownItemDoesNotMutateBagOrPurchaseState() {
        Fixture fixture = Fixture.create();
        fixture.bags.grant(fixture.bag, Reward.of(new ItemStack("gold", 500)));

        ShopPurchaseResult result = fixture.service.purchase(fixture.bag, fixture.playerShop, "missing");

        assertEquals(ShopPurchaseStatus.UNKNOWN_ITEM, result.status());
        assertEquals(500, fixture.bag.count("gold"));
        assertEquals(0, fixture.playerShop.lifetimePurchased("missing"));
    }

    @Test
    void sameOrderIdReplaysSuccessfulPurchaseWithoutMutatingAgain() {
        Fixture fixture = Fixture.create();
        fixture.bags.grant(fixture.bag, Reward.of(new ItemStack("gold", 500)));

        ShopPurchaseResult first = fixture.service.purchaseAt(
                fixture.bag,
                fixture.playerShop,
                "order-10001-1",
                "growth_pack",
                1,
                CLOCK.instant()
        );
        ShopPurchaseResult replay = fixture.service.purchaseAt(
                fixture.bag,
                fixture.playerShop,
                "order-10001-1",
                "growth_pack",
                1,
                CLOCK.instant()
        );

        assertEquals(ShopPurchaseStatus.SUCCESS, first.status());
        assertEquals(ShopPurchaseStatus.SUCCESS, replay.status());
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(400, fixture.bag.count("gold"));
        assertEquals(5, fixture.bag.count("stone"));
        assertEquals(1, fixture.playerShop.lifetimePurchased("growth_pack"));
        assertEquals(1, fixture.orders.size());
        assertEquals(new ShopRuntimeStats(2, 1, 1, 0, 0, 0, 0, 0, 0, 1), fixture.service.stats());
    }

    @Test
    void sameOrderIdWithDifferentRequestIsRejectedWithoutMutation() {
        Fixture fixture = Fixture.create();
        fixture.bags.grant(fixture.bag, Reward.of(new ItemStack("gold", 500)));
        fixture.service.purchaseAt(fixture.bag, fixture.playerShop, "order-10001-1",
                "growth_pack", 1, CLOCK.instant());

        ShopPurchaseResult conflict = fixture.service.purchaseAt(
                fixture.bag,
                fixture.playerShop,
                "order-10001-1",
                "growth_pack",
                2,
                CLOCK.instant()
        );

        assertEquals(ShopPurchaseStatus.ORDER_CONFLICT, conflict.status());
        assertEquals(400, fixture.bag.count("gold"));
        assertEquals(5, fixture.bag.count("stone"));
        assertEquals(1, fixture.orders.size());
        assertEquals(new ShopRuntimeStats(2, 1, 0, 0, 0, 0, 0, 0, 1, 1), fixture.service.stats());
    }

    @Test
    void failedPurchaseDoesNotRecordOrderSoRetryCanSucceed() {
        Fixture fixture = Fixture.create();

        ShopPurchaseResult failed = fixture.service.purchaseAt(
                fixture.bag,
                fixture.playerShop,
                "order-10001-1",
                "growth_pack",
                1,
                CLOCK.instant()
        );
        fixture.bags.grant(fixture.bag, Reward.of(new ItemStack("gold", 500)));
        ShopPurchaseResult retry = fixture.service.purchaseAt(
                fixture.bag,
                fixture.playerShop,
                "order-10001-1",
                "growth_pack",
                1,
                CLOCK.instant()
        );

        assertEquals(ShopPurchaseStatus.NOT_ENOUGH_CURRENCY, failed.status());
        assertEquals(ShopPurchaseStatus.SUCCESS, retry.status());
        assertEquals(400, fixture.bag.count("gold"));
        assertEquals(5, fixture.bag.count("stone"));
        assertEquals(1, fixture.orders.size());
        assertEquals(new ShopRuntimeStats(2, 1, 0, 0, 0, 0, 1, 0, 0, 1), fixture.service.stats());
    }

    private record Fixture(
            ItemCatalog items,
            BagService bags,
            ShopCatalog catalog,
            InMemoryShopStockRepository stocks,
            InMemoryShopOrderRepository orders,
            ShopService service,
            PlayerBag bag,
            PlayerShopState playerShop
    ) {
        private static Fixture create() {
            ItemCatalog items = new ItemCatalog();
            items.register(new ItemDefinition("gold", "currency", 999_999));
            items.register(new ItemDefinition("stone", "material", 999_999));
            items.register(new ItemDefinition("ticket", "ticket", 999_999));
            BagService bags = new BagService(items);
            ShopCatalog catalog = new ShopCatalog();
            catalog.register(new ShopItemDefinition(
                    "growth_pack",
                    new ItemStack("gold", 100),
                    Reward.of(new ItemStack("stone", 5)),
                    2,
                    2,
                    ShopItemDefinition.UNLIMITED_STOCK
            ));
            catalog.register(new ShopItemDefinition(
                    "limited_pack",
                    new ItemStack("gold", 100),
                    Reward.of(new ItemStack("ticket", 1)),
                    0,
                    0,
                    1
            ));
            InMemoryShopStockRepository stocks = new InMemoryShopStockRepository();
            stocks.seed(catalog);
            InMemoryShopOrderRepository orders = new InMemoryShopOrderRepository();
            ShopService service = new ShopService(catalog, bags, stocks, orders, CLOCK, RESET_ZONE);
            return new Fixture(items, bags, catalog, stocks, orders, service, new PlayerBag(), new PlayerShopState());
        }

        private Fixture newPlayer() {
            return new Fixture(items, bags, catalog, stocks, orders, service, new PlayerBag(), new PlayerShopState());
        }
    }
}
