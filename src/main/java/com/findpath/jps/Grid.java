package com.findpath.jps;


import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Setter
@Getter
public class Grid {

    private int x;
    private int y;
    private int type;

    public static Grid valueOf(int x, int y) {
        Grid grid = new Grid();
        grid.x = x;
        grid.y = y;
        return grid;
    }

    public static Grid valueOf(int x, int y, int type) {
        Grid grid = new Grid();
        grid.x = x;
        grid.y = y;
        grid.type = type;
        return grid;
    }

    public boolean isSameGrid(Grid grid) {
        return x == grid.getX() && y == grid.getY();
    }

    @Override
    public boolean equals(Object o) {
        // 注意这个有可能有子类,所以不需要对比class,不要用自动生成的
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        Grid grid = (Grid) o;
        return this.isSameGrid(grid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

    public boolean walkable() {
        return type != 1;
    }

    @Override
    public String toString() {
        return "Grid{" +
                "x=" + x +
                ", y=" + y +
                ", type=" + type +
                '}';
    }
}
