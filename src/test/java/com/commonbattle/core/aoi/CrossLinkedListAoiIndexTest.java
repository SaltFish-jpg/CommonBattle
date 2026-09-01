package com.commonbattle.core.aoi;

import com.aoi.AoiBounds;
import com.aoi.AoiPoint;
import com.aoi.CrossLinkedListAoiIndex;
import com.commonbattle.core.EntityId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrossLinkedListAoiIndexTest {
    @Test
    void queryReturnsObjectsInside2dBounds() {
        CrossLinkedListAoiIndex index = new CrossLinkedListAoiIndex();
        EntityId a = new EntityId("a");
        EntityId b = new EntityId("b");
        EntityId c = new EntityId("c");

        index.add(a, AoiPoint.of2d(1, 1));
        index.add(b, AoiPoint.of2d(4, 3));
        index.add(c, AoiPoint.of2d(5, 8));

        assertEquals(List.of(a, b), index.query(AoiBounds.of2d(0, 4, 0, 4)));
    }

    @Test
    void moveRelinksBothAxesAndRemoveDropsObject() {
        CrossLinkedListAoiIndex index = new CrossLinkedListAoiIndex();
        EntityId a = new EntityId("a");
        EntityId b = new EntityId("b");

        index.add(a, AoiPoint.of2d(10, 10));
        index.add(b, AoiPoint.of2d(2, 2));
        index.move(a, AoiPoint.of2d(3, 3));
        index.remove(b);

        assertEquals(List.of(a), index.queryAround2d(AoiPoint.of2d(3, 3), 0));
        assertFalse(index.positionOf(b).isPresent());
    }

    @Test
    void rejects3dPositions() {
        CrossLinkedListAoiIndex index = new CrossLinkedListAoiIndex();

        assertThrows(IllegalArgumentException.class,
                () -> index.add(new EntityId("a"), AoiPoint.of3d(1, 1, 1)));
    }
}
