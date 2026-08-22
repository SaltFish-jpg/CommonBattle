package com.findpath.jps;

/**
 * 2D
 */
public class Terrain2D {
    private int w;
    private int h;
    /**
     * 格子信息
     */
    private int[] grids;

    public Terrain2D(int w, int h, int[] grids) {
        this.w = w;
        this.h = h;
        this.grids = grids;
    }

    public int getType(int x, int y) {
        if (x < 0 || x >= w || y < 0 || y >= h) {
            return -1;
        }
        return grids[y * w + x];
    }

    public void setType(int x, int y, int type) {
        if (x < 0 || x >= w || y < 0 || y >= h) {
            return;
        }
        grids[y * w + x] = type;
    }

    public int getW() {
        return w;
    }

    public void setW(int w) {
        this.w = w;
    }

    public int getH() {
        return h;
    }

    public void setH(int h) {
        this.h = h;
    }

    public int[] getGrids() {
        return grids;
    }

    public void setGrids(int[] grids) {
        this.grids = grids;
    }
}
