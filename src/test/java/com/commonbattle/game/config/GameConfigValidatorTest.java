package com.commonbattle.game.config;

import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameConfigValidatorTest {
    @Test
    void validConfigPassesCrossTableValidation() {
        GameConfigValidation validation = new GameConfigValidator().validate(validConfig(1));

        assertTrue(validation.valid());
    }

    @Test
    void collectsDuplicateIdsAndUnknownItemReferences() {
        GameConfigPackage config = new GameConfigPackage(
                1,
                List.of(
                        new ItemDefinition("gold", "currency", 999999),
                        new ItemDefinition("gold", "currency", 999999)
                ),
                List.of(
                        new ActivityDefinition(
                                "daily-login",
                                ActivityType.LOGIN,
                                1,
                                Reward.of(new ItemStack("missing_reward", 1))
                        ),
                        new ActivityDefinition(
                                "daily-login",
                                ActivityType.LOGIN,
                                1,
                                Reward.of(new ItemStack("gold", 1))
                        )
                ),
                new GrowthTuning("missing_exp", 60, 100),
                Instant.parse("2026-09-01T00:00:00Z")
        );

        GameConfigValidation validation = new GameConfigValidator().validate(config);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(GameConfigIssue::key).toList();
        assertTrue(keys.contains("items.gold"));
        assertTrue(keys.contains("activities.daily-login"));
        assertTrue(keys.contains("activities.daily-login.reward.missing_reward"));
        assertTrue(keys.contains("growth.expItemId"));
    }

    static GameConfigPackage validConfig(long version) {
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
                new GrowthTuning("exp_potion", 60, 100),
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }
}
