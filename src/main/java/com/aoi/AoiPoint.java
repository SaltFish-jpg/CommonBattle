package com.aoi;

/**
 * AOI 空间中的整数坐标。
 * 二维场景使用 z=0，三维场景使用完整 xyz 坐标。
 */
public record AoiPoint(int x, int y, int z) {
    public static AoiPoint of2d(int x, int y) {
        return new AoiPoint(x, y, 0);
    }

    public static AoiPoint of3d(int x, int y, int z) {
        return new AoiPoint(x, y, z);
    }
}
