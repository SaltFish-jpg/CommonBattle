package com.aoi;

import java.util.Objects;

/**
 * AOI 范围查询使用的闭区间包围盒。
 * 二维查询将 minZ/maxZ 固定为 0，三维查询按 xyz 三轴共同裁剪。
 */
public record AoiBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    public AoiBounds {
        if (minX > maxX) {
            throw new IllegalArgumentException("minX must be <= maxX");
        }
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must be <= maxY");
        }
        if (minZ > maxZ) {
            throw new IllegalArgumentException("minZ must be <= maxZ");
        }
    }

    public static AoiBounds of2d(int minX, int maxX, int minY, int maxY) {
        return new AoiBounds(minX, maxX, minY, maxY, 0, 0);
    }

    public static AoiBounds of3d(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        return new AoiBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    public static AoiBounds around2d(AoiPoint center, int range) {
        Objects.requireNonNull(center, "center");
        requireNonNegative(range);
        return of2d(center.x() - range, center.x() + range, center.y() - range, center.y() + range);
    }

    public static AoiBounds around3d(AoiPoint center, int range) {
        Objects.requireNonNull(center, "center");
        requireNonNegative(range);
        return of3d(center.x() - range, center.x() + range,
                center.y() - range, center.y() + range,
                center.z() - range, center.z() + range);
    }

    public boolean contains(AoiPoint point) {
        Objects.requireNonNull(point, "point");
        return point.x() >= minX && point.x() <= maxX
                && point.y() >= minY && point.y() <= maxY
                && point.z() >= minZ && point.z() <= maxZ;
    }

    private static void requireNonNegative(int range) {
        if (range < 0) {
            throw new IllegalArgumentException("range must be >= 0");
        }
    }
}
