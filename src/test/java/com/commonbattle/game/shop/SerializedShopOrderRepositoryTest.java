package com.commonbattle.game.shop;

import com.commonbattle.game.bag.BagChange;
import com.commonbattle.game.bag.BagResult;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedShopOrderRepositoryTest {
    @Test
    void serializesSuccessfulOrderForReplayAfterRepositoryRebuild() {
        InMemoryAtomicBytesStore store = new InMemoryAtomicBytesStore();
        ProtoShopPurchaseOrderSerializer serializer = new ProtoShopPurchaseOrderSerializer();
        SerializedShopOrderRepository first = new SerializedShopOrderRepository(store, serializer);
        ShopPurchaseResult result = new ShopPurchaseResult(
                ShopPurchaseStatus.SUCCESS,
                "growth_pack",
                1,
                new BagResult(List.of(new BagChange("gold", 500, 400))),
                new BagResult(List.of(new BagChange("stone", 0, 5))),
                1,
                1
        );
        ShopPurchaseOrder order = new ShopPurchaseOrder(
                "order-10001-1",
                "growth_pack",
                1,
                result,
                Instant.parse("2026-09-01T00:00:00Z")
        );

        assertTrue(first.saveIfAbsent(order));
        assertFalse(first.saveIfAbsent(order));

        SerializedShopOrderRepository rebuilt = new SerializedShopOrderRepository(store, serializer);
        ShopPurchaseOrder loaded = rebuilt.find("order-10001-1").orElseThrow();

        assertEquals(order.orderId(), loaded.orderId());
        assertEquals(order.sku(), loaded.sku());
        assertEquals(order.quantity(), loaded.quantity());
        assertEquals(order.createdAt(), loaded.createdAt());
        assertEquals(result.status(), loaded.result().status());
        assertEquals(result.cost(), loaded.result().cost());
        assertEquals(result.reward(), loaded.result().reward());
        assertEquals(result.lifetimePurchased(), loaded.result().lifetimePurchased());
        assertEquals(result.dailyPurchased(), loaded.result().dailyPurchased());
    }
}
