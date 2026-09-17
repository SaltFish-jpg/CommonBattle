package com.commonbattle.game.growth;

import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.bag.ItemDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowthServiceTest {
    @Test
    void recoversStaminaByElapsedPeriodsAndKeepsRemainderTime() {
        GrowthService service = new GrowthService(bagService(), "exp_potion", 60, 100,
                10, 2, Duration.ofMinutes(5));
        GrowthProfile profile = new GrowthProfile();
        profile.restore(new GrowthSnapshot(1, 0, 1, 10, Instant.parse("2026-09-01T00:00:00Z")));

        GrowthRecoveryResult result = service.recoverStamina(profile, Instant.parse("2026-09-01T00:12:00Z"));

        assertTrue(result.changed());
        assertEquals(1, result.beforeStamina());
        assertEquals(5, result.afterStamina());
        assertEquals(5, profile.stamina());
        assertEquals(Instant.parse("2026-09-01T00:10:00Z"), profile.staminaUpdatedAt());
    }

    @Test
    void fullStaminaOnlyMovesRecoveryAnchor() {
        GrowthService service = new GrowthService(bagService(), "exp_potion", 60, 100,
                10, 2, Duration.ofMinutes(5));
        GrowthProfile profile = new GrowthProfile();
        profile.restore(new GrowthSnapshot(1, 0, 10, 10, Instant.parse("2026-09-01T00:00:00Z")));

        GrowthRecoveryResult result = service.recoverStamina(profile, Instant.parse("2026-09-01T00:12:00Z"));

        assertFalse(result.changed());
        assertEquals(10, profile.stamina());
        assertEquals(Instant.parse("2026-09-01T00:12:00Z"), profile.staminaUpdatedAt());
    }

    private static BagService bagService() {
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("exp_potion", "growth", 999));
        return new BagService(items);
    }
}
