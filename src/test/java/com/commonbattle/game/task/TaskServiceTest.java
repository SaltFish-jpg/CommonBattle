package com.commonbattle.game.task;

import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskServiceTest {
    @Test
    void matchedPlayerEventIncreasesTaskProgressAndClaimGrantsReward() {
        Fixture fixture = Fixture.create();
        PlayerTasks tasks = new PlayerTasks();
        PlayerBag bag = new PlayerBag();

        fixture.service().onEvent(tasks, new BattleStageClearedEvent(
                10001L,
                "forest-1",
                3,
                true,
                1,
                false,
                false,
                "",
                1
        ));
        TaskClaimResult claim = fixture.service().claim(tasks, bag, "task-clear-forest");

        assertEquals(1, claim.progress());
        assertEquals(3, bag.count("gem"));
    }

    @Test
    void unmatchedSubjectDoesNotIncreaseProgress() {
        Fixture fixture = Fixture.create();
        PlayerTasks tasks = new PlayerTasks();

        fixture.service().onEvent(tasks, new BattleStageClearedEvent(
                10001L,
                "hard-1",
                3,
                true,
                1,
                false,
                false,
                "",
                1
        ));

        assertEquals(0, tasks.progress("task-clear-forest").value());
    }

    @Test
    void claimBeforeReadyIsRejected() {
        Fixture fixture = Fixture.create();

        assertThrows(IllegalStateException.class,
                () -> fixture.service().claim(new PlayerTasks(), new PlayerBag(), "task-clear-forest"));
    }

    private record Fixture(TaskService service) {
        private static Fixture create() {
            ItemCatalog items = new ItemCatalog();
            items.register(new ItemDefinition("gem", "currency", 999999));
            BagService bagService = new BagService(items);
            TaskCatalog tasks = new TaskCatalog();
            tasks.register(new TaskDefinition(
                    "task-clear-forest",
                    BattleStageClearedEvent.TYPE,
                    "forest-1",
                    1,
                    Reward.of(new ItemStack("gem", 3))
            ));
            return new Fixture(new TaskService(tasks, bagService));
        }
    }
}
