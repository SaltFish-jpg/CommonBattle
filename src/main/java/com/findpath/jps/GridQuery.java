package com.findpath.jps;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;


public class GridQuery {
    private final Terrain2D graph;

    public GridQuery(Terrain2D graph) {
        this.graph = graph;
    }

    public Grid toVector(Grid current) {
        return current;
    }

    public double distance(Grid a, Grid b) {
        return Math.sqrt((b.getX() - a.getX()) * (b.getX() - a.getX()) + (b.getY() - a.getY()) * (b.getY() - a.getY()));
    }

    public double calcPathDistance(Grid a, Grid b) {
        return diagonalDistance(a, b);
    }

    /**
     * 欧几里得距离/任意方向
     */
    public double euclideanDistance(Grid a, Grid b) {
        return chebyshevDistance(a,b);
    }

    /**
     * 切比雪夫距离/允许八方向移动,但斜对角移动代价与直线相等的网格/象棋
     */
    private int chebyshevDistance(Grid a, Grid b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        return Math.max(dx, dy);
    }

    /**
     * 对角距离,八向移动 / 用这个直接转成整数运算
     */
    private int diagonalDistance(Grid a, Grid b) {
        // 没必要走开方
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int min = Math.min(dx, dy), max = Math.max(dx, dy);
        // 斜向≈1.4，直角=1.0
        return 14 * min + 10 * (max - min);
    }

    /**
     * 曼哈顿距离/四向移动
     */
    private int manhattanDistance(Grid a, Grid b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        return dx + dy;
    }

    public void setGridType(int x, int y, int type) {
        graph.setType(x, y, type);
    }

    public Grid getLinkSpan(Grid current, int x, int y) {
        int type = graph.getType(x, y);
        if (type == -1) {
            return null;
        }
        return Grid.valueOf(x, y, type);
    }

    public int getType(int x, int y) {
        return graph.getType(x, y);
    }

    public boolean walkable(Grid grid, int x, int y) {
        Grid linkSpan = getLinkSpan(grid, x, y);
        return linkSpan != null && linkSpan.walkable();
    }

    public Collection<? extends Grid> getNeighbours(Grid current) {
        Set<Grid> neighbours = new HashSet<>();
        for (int x = current.getX() - 1; x <= current.getX() + 1; ++x) {
            for (int y = current.getY() - 1; y <= current.getY() + 1; ++y) {
                if (x == current.getX() && y == current.getY()) {
                    continue;
                }

                Grid linkSpan = getLinkSpan(current, x, y);
                if (linkSpan != null && linkSpan.walkable()) {
                    neighbours.add(linkSpan);
                }
            }
        }
        return neighbours;
    }

    public Grid findGrid(int x, int y) {
        int type = graph.getType(x, y);
        if (type == -1) {
            return null;
        }
        return Grid.valueOf(x, y, type);
    }

    public int getW() {
        return graph.getW();
    }

    public int getH() {
        return graph.getH();
    }

}
