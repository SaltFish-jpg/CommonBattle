package com.aoi;

import com.commonbattle.core.EntityId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 九宫格 AOI 索引。
 * 二维模式按 3x3 邻近格裁剪，三维模式扩展为 3x3x3 邻近格，并在返回前做精确范围过滤。
 */
public final class NineGridAoiIndex implements AoiIndex {
    private final int cellSize;
    private final boolean threeDimensional;
    private final Map<EntityId, Entry> entries = new LinkedHashMap<>();
    private final Map<Cell, LinkedHashSet<EntityId>> cells = new LinkedHashMap<>();

    private NineGridAoiIndex(int cellSize, boolean threeDimensional) {
        if (cellSize <= 0) {
            throw new IllegalArgumentException("cellSize must be > 0");
        }
        this.cellSize = cellSize;
        this.threeDimensional = threeDimensional;
    }

    public static NineGridAoiIndex twoDimensional(int cellSize) {
        return new NineGridAoiIndex(cellSize, false);
    }

    public static NineGridAoiIndex threeDimensional(int cellSize) {
        return new NineGridAoiIndex(cellSize, true);
    }

    @Override
    public void add(EntityId id, AoiPoint position) {
        Objects.requireNonNull(id, "id");
        validateDimension(position);
        if (entries.containsKey(id)) {
            throw new IllegalArgumentException("AOI object already exists: " + id.value());
        }

        Cell cell = cellOf(position);
        entries.put(id, new Entry(position, cell));
        cells.computeIfAbsent(cell, ignored -> new LinkedHashSet<>()).add(id);
    }

    @Override
    public void move(EntityId id, AoiPoint position) {
        Objects.requireNonNull(id, "id");
        validateDimension(position);
        Entry entry = requireEntry(id);
        Cell newCell = cellOf(position);
        if (!entry.cell.equals(newCell)) {
            removeFromCell(id, entry.cell);
            cells.computeIfAbsent(newCell, ignored -> new LinkedHashSet<>()).add(id);
            entry.cell = newCell;
        }
        entry.position = position;
    }

    @Override
    public void remove(EntityId id) {
        Objects.requireNonNull(id, "id");
        Entry entry = entries.remove(id);
        if (entry != null) {
            removeFromCell(id, entry.cell);
        }
    }

    @Override
    public Optional<AoiPoint> positionOf(EntityId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(entries.get(id)).map(entry -> entry.position);
    }

    @Override
    public List<EntityId> query(AoiBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        validateBounds(bounds);

        Set<EntityId> candidates = new LinkedHashSet<>();
        int minCellX = cellCoordinate(bounds.minX());
        int maxCellX = cellCoordinate(bounds.maxX());
        int minCellY = cellCoordinate(bounds.minY());
        int maxCellY = cellCoordinate(bounds.maxY());
        int minCellZ = threeDimensional ? cellCoordinate(bounds.minZ()) : 0;
        int maxCellZ = threeDimensional ? cellCoordinate(bounds.maxZ()) : 0;

        for (int x = minCellX; x <= maxCellX; x++) {
            for (int y = minCellY; y <= maxCellY; y++) {
                for (int z = minCellZ; z <= maxCellZ; z++) {
                    Set<EntityId> bucket = cells.get(new Cell(x, y, z));
                    if (bucket != null) {
                        candidates.addAll(bucket);
                    }
                }
            }
        }

        List<EntityId> result = new ArrayList<>();
        for (EntityId id : candidates) {
            Entry entry = entries.get(id);
            if (entry != null && bounds.contains(entry.position)) {
                result.add(id);
            }
        }
        return result;
    }

    private Entry requireEntry(EntityId id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown AOI object: " + id.value());
        }
        return entry;
    }

    private Cell cellOf(AoiPoint position) {
        return new Cell(cellCoordinate(position.x()), cellCoordinate(position.y()),
                threeDimensional ? cellCoordinate(position.z()) : 0);
    }

    private int cellCoordinate(int value) {
        return Math.floorDiv(value, cellSize);
    }

    private void removeFromCell(EntityId id, Cell cell) {
        Set<EntityId> bucket = cells.get(cell);
        if (bucket == null) {
            return;
        }
        bucket.remove(id);
        if (bucket.isEmpty()) {
            cells.remove(cell);
        }
    }

    private void validateDimension(AoiPoint position) {
        Objects.requireNonNull(position, "position");
        if (!threeDimensional && position.z() != 0) {
            throw new IllegalArgumentException("2D nine grid AOI requires z=0");
        }
    }

    private void validateBounds(AoiBounds bounds) {
        if (!threeDimensional && (bounds.minZ() != 0 || bounds.maxZ() != 0)) {
            throw new IllegalArgumentException("2D nine grid AOI requires z bounds to be 0");
        }
    }

    private static final class Entry {
        private AoiPoint position;
        private Cell cell;

        private Entry(AoiPoint position, Cell cell) {
            this.position = position;
            this.cell = cell;
        }
    }

    private record Cell(int x, int y, int z) {
    }
}
