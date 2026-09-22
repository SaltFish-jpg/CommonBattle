package com.aoi.viewlink;



import com.commonbattle.battle.state.EntityId;
import com.aoi.viewlink.ViewLinkChangeSet;
import com.aoi.viewlink.ViewLinkManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewLinkManagerTest {
    @Test
    void linkCreatesSymmetricVisibilityAndUnlinkRemovesBothSides() {
        ViewLinkManager manager = new ViewLinkManager(8);
        EntityId a = new EntityId("a");
        EntityId b = new EntityId("b");

        manager.add(a, 0);
        manager.add(b, 1);

        assertTrue(manager.link(a, b));
        assertTrue(manager.canSee(a, b));
        assertTrue(manager.canSee(b, a));
        assertEquals(List.of(b), manager.visibleTargets(a));
        assertEquals(List.of(a), manager.visibleTargets(b));

        assertTrue(manager.unlink(a, b));
        assertFalse(manager.canSee(a, b));
        assertFalse(manager.canSee(b, a));
        assertEquals(List.of(), manager.visibleTargets(a));
        assertEquals(List.of(), manager.visibleTargets(b));
    }

    @Test
    void refreshReturnsEnterAndLeaveDelta() {
        ViewLinkManager manager = new ViewLinkManager(8);
        EntityId me = new EntityId("me");
        EntityId oldOnly = new EntityId("old");
        EntityId both = new EntityId("both");
        EntityId fresh = new EntityId("fresh");

        manager.add(me, 0);
        manager.add(oldOnly, 1);
        manager.add(both, 2);
        manager.add(fresh, 3);
        manager.link(me, oldOnly);
        manager.link(me, both);

        ViewLinkChangeSet changeSet = manager.refresh(me, List.of(both, fresh), -1, id -> 0);

        assertEquals(List.of(fresh), changeSet.enter());
        assertEquals(List.of(oldOnly), changeSet.leave());
        assertFalse(manager.canSee(me, oldOnly));
        assertTrue(manager.canSee(me, both));
        assertTrue(manager.canSee(me, fresh));
    }

    @Test
    void refreshUsesPriorityWhenCapacityIsFull() {
        ViewLinkManager manager = new ViewLinkManager(8);
        EntityId me = new EntityId("me");
        EntityId low = new EntityId("low");
        EntityId high = new EntityId("high");
        Map<EntityId, Integer> priority = Map.of(me, 0, low, 10, high, 1);

        manager.add(me, 0);
        manager.add(low, 1);
        manager.add(high, 2);
        manager.link(me, low);

        ViewLinkChangeSet changeSet = manager.refresh(me, List.of(low, high), 1, priority::get);

        assertEquals(List.of(high), changeSet.enter());
        assertEquals(List.of(low), changeSet.leave());
        assertFalse(manager.canSee(me, low));
        assertTrue(manager.canSee(me, high));
    }

    @Test
    void validatesObjectIndexRangeAndUniqueness() {
        ViewLinkManager manager = new ViewLinkManager(2);

        manager.add(new EntityId("a"), 0);

        assertThrows(IllegalArgumentException.class, () -> manager.add(new EntityId("b"), 0));
        assertThrows(IllegalArgumentException.class, () -> manager.add(new EntityId("c"), 2));
    }
}
