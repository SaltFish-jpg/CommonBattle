package com.aoi;



import com.commonbattle.battle.state.EntityId;
import com.aoi.AoiBounds;
import com.aoi.AoiPoint;
import com.aoi.NineGridAoiIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NineGridAoiIndexTest {
    @Test
    void twoDimensionalGridQueriesPreciseBoundsAcrossNeighborCells() {
        NineGridAoiIndex index = NineGridAoiIndex.twoDimensional(10);
        EntityId a = new EntityId("a");
        EntityId b = new EntityId("b");
        EntityId c = new EntityId("c");

        index.add(a, AoiPoint.of2d(5, 5));
        index.add(b, AoiPoint.of2d(14, 14));
        index.add(c, AoiPoint.of2d(21, 5));

        assertEquals(List.of(a, b), index.queryAround2d(AoiPoint.of2d(10, 10), 5));
    }

    @Test
    void threeDimensionalGridQueriesZAxisAndMovesBetweenCells() {
        NineGridAoiIndex index = NineGridAoiIndex.threeDimensional(10);
        EntityId a = new EntityId("a");
        EntityId b = new EntityId("b");
        EntityId c = new EntityId("c");

        index.add(a, AoiPoint.of3d(0, 0, 0));
        index.add(b, AoiPoint.of3d(9, 9, 9));
        index.add(c, AoiPoint.of3d(0, 0, 20));
        index.move(c, AoiPoint.of3d(4, 4, 4));

        assertEquals(List.of(a, b, c), index.queryAround3d(AoiPoint.of3d(5, 5, 5), 5));
    }

    @Test
    void twoDimensionalGridRejectsNonZeroZ() {
        NineGridAoiIndex index = NineGridAoiIndex.twoDimensional(10);

        assertThrows(IllegalArgumentException.class,
                () -> index.add(new EntityId("a"), AoiPoint.of3d(1, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> index.query(AoiBounds.of3d(0, 10, 0, 10, -1, 1)));
    }
}
