package com.aoi;

import com.commonbattle.core.EntityId;

import java.util.List;
import java.util.Optional;

/**
 * 可替换的 AOI 空间索引。
 * 实现负责维护对象位置，并提供确定性顺序的范围查询结果。
 */
public interface AoiIndex {
    void add(EntityId id, AoiPoint position);

    void move(EntityId id, AoiPoint position);

    void remove(EntityId id);

    Optional<AoiPoint> positionOf(EntityId id);

    List<EntityId> query(AoiBounds bounds);

    default List<EntityId> queryAround2d(AoiPoint center, int range) {
        return query(AoiBounds.around2d(center, range));
    }

    default List<EntityId> queryAround3d(AoiPoint center, int range) {
        return query(AoiBounds.around3d(center, range));
    }
}
