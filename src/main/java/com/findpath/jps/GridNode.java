package com.findpath.jps;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GridNode implements Comparable<GridNode> {

    private Grid grid;

    private double f;

    private double g;

    public GridNode(Grid grid, double f) {
        this.grid = grid;
        this.f = f;
    }

    public GridNode(Grid grid, double f, double g) {
        this.grid = grid;
        this.f = f;
        this.g = g;
    }

    @Override
    public int compareTo(GridNode o) {
        if (this.f == o.f) {
            // 相同f, g大优先: 走的更远,更靠近终点
            return Double.compare(o.g, this.g);
        }
        return Double.compare(this.f, o.f);
    }
}
