package com.commonbattle.game.config;

import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.growth.GrowthProfile;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryGameConfigRegistryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void publishBuildsActiveRuntime() {
        InMemoryGameConfigRegistry registry = registry();

        GameConfigPublishResult result = registry.publish(GameConfigValidatorTest.validConfig(1));

        assertEquals(GameConfigPublishStatus.PUBLISHED, result.status());
        assertEquals(1, registry.active().version());
        assertEquals("currency", registry.active().items().require("gold").type());
    }

    @Test
    void publishRejectsNonIncreasingActiveVersion() {
        InMemoryGameConfigRegistry registry = registry();
        registry.publish(GameConfigValidatorTest.validConfig(2));

        GameConfigPublishResult result = registry.publish(GameConfigValidatorTest.validConfig(2));

        assertEquals(GameConfigPublishStatus.REJECTED, result.status());
        assertEquals(2, registry.active().version());
    }

    @Test
    void grayPublishSelectsStablePlayerSubsetWithoutChangingActive() {
        InMemoryGameConfigRegistry registry = registry();
        registry.publish(GameConfigValidatorTest.validConfig(1));
        registry.publishGray(configWithGrowth(2, 120), 100);

        GameConfigRuntime selected = registry.resolve(10001L);

        assertEquals(1, registry.active().version());
        assertEquals(2, selected.version());
        PlayerBag bag = new PlayerBag();
        registry.active().bagService().grant(bag, Reward.of(new ItemStack("exp_potion", 1)));
        GrowthProfile growth = new GrowthProfile();
        selected.growthService().useExpItems(bag, growth, 1);
        assertEquals(2, growth.level());
        assertEquals(20, growth.exp());
    }

    @Test
    void invalidGrayConfigDoesNotReplaceActive() {
        InMemoryGameConfigRegistry registry = registry();
        registry.publish(GameConfigValidatorTest.validConfig(1));
        GameConfigPackage invalid = new GameConfigPackage(
                2,
                List.of(new ItemDefinition("gold", "currency", 999999)),
                List.of(new ActivityDefinition("daily-login", ActivityType.LOGIN, 1,
                        Reward.of(new ItemStack("unknown", 1)))),
                new GrowthTuning("missing_exp", 60, 100),
                CLOCK.instant()
        );

        GameConfigPublishResult result = registry.publishGray(invalid, 100);

        assertEquals(GameConfigPublishStatus.REJECTED, result.status());
        assertEquals(1, registry.resolve(10001L).version());
    }

    @Test
    void rollbackSwitchesActiveToHistoricalVersionAndClearsGray() {
        InMemoryGameConfigRegistry registry = registry();
        registry.publish(GameConfigValidatorTest.validConfig(1));
        registry.publish(configWithGrowth(2, 120));
        registry.publishGray(configWithGrowth(3, 180), 100);

        GameConfigPublishResult result = registry.rollback(1);

        assertEquals(GameConfigPublishStatus.ROLLED_BACK, result.status());
        assertEquals(1, registry.active().version());
        assertEquals(1, registry.resolve(10001L).version());
        assertTrue(registry.versions().containsAll(List.of(1L, 2L, 3L)));
    }

    @Test
    void activeFailsBeforeFirstPublish() {
        assertThrows(IllegalStateException.class, registry()::active);
    }

    private static InMemoryGameConfigRegistry registry() {
        return new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
    }

    static GameConfigPackage configWithGrowthForConfigCacheTest(long version, int expPerItem) {
        return configWithGrowth(version, expPerItem);
    }

    private static GameConfigPackage configWithGrowth(long version, int expPerItem) {
        return new GameConfigPackage(
                version,
                List.of(
                        new ItemDefinition("gold", "currency", 999999),
                        new ItemDefinition("exp_potion", "growth", 999)
                ),
                List.of(new ActivityDefinition(
                        "daily-login",
                        ActivityType.LOGIN,
                        1,
                        Reward.of(new ItemStack("gold", 100), new ItemStack("exp_potion", 1))
                )),
                new GrowthTuning("exp_potion", expPerItem, 100),
                CLOCK.instant()
        );
    }
}
