package com.commonbattle.game.bag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BagServiceTest {
    @Test
    void grantAndConsumeItems() {
        ItemCatalog catalog = new ItemCatalog();
        catalog.register(new ItemDefinition("gold", "currency", 999999));
        BagService service = new BagService(catalog);
        PlayerBag bag = new PlayerBag();

        BagResult grant = service.grant(bag, Reward.of(new ItemStack("gold", 100)));
        BagResult consume = service.consume(bag, new ItemStack("gold", 30));

        assertEquals(100, grant.changes().getFirst().after());
        assertEquals(70, consume.changes().getFirst().after());
        assertEquals(70, bag.count("gold"));
    }

    @Test
    void consumeFailsWhenItemIsNotEnough() {
        ItemCatalog catalog = new ItemCatalog();
        catalog.register(new ItemDefinition("gold", "currency", 999999));
        BagService service = new BagService(catalog);

        assertThrows(IllegalStateException.class, () -> service.consume(new PlayerBag(), new ItemStack("gold", 1)));
    }
}
