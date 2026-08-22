package com.findpath.jps.role;


import com.findpath.jps.Grid;
import com.findpath.jps.GridQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 可切角
 */
public class CornerCuttingRule implements JPSRule {

    @Override
    public boolean canDiagonal(GridQuery query, Grid parent, Grid current, int dx, int dy) {
        return true;
    }

    @Override
    public boolean hasForcedNeighbour(GridQuery query, Grid current, int dx, int dy) {
        if (dx != 0 && dy != 0) {
            //000
            //#C0
            //P00
            // 斜向移动y轴分量-dx, 0 是障碍, -dx, dy 可同行  -dx,dy 是强制邻居
            if (!query.walkable(current, current.getX() - dx, current.getY()) && query.walkable(current,
                current.getX() - dx, current.getY() + dy)) {
                return true;
            }
            //000
            //0C0
            //P#0
            // 斜向移动x轴分量 0, -dy 是障碍, +dx, -dy 可同行  +dx,-dy 是强制邻居
            return !query.walkable(current, current.getX(), current.getY() - dy) && query.walkable(current,
                current.getX() + dx, current.getY() - dy);
        }

        if (dx != 0) {
            // 上侧
            if (!query.walkable(current, current.getX(), current.getY() + 1) && query.walkable(current,
                current.getX() + dx, current.getY() + 1)) {
                return true;
            }
            // 下侧
            return !query.walkable(current, current.getX(), current.getY() - 1) && query.walkable(current,
                current.getX() + dx, current.getY() - 1);
        }

        if (dy != 0) {
            // 左侧
            if (!query.walkable(current, current.getX() - 1, current.getY()) && query.walkable(current,
                current.getX() - 1, current.getY() + dy)) {
                return true;
            }
            // 右侧
            return !query.walkable(current, current.getX() + 1, current.getY()) && query.walkable(current,
                current.getX() + 1, current.getY() + dy);
        }

        return false;
    }

    @Override
    public Collection<Grid> findNeighbours(GridQuery query, Grid current, Map<Grid, Grid> parentMap) {
        List<Grid> neighbours = new ArrayList<>();
        Grid parent = parentMap.get(current);

        if (parent == null) {
            neighbours.addAll(query.getNeighbours(current));
            return neighbours;
        }
        final int x = current.getX();
        final int y = current.getY();
        final int dx = Integer.signum(x - parent.getX());
        final int dy = Integer.signum(y - parent.getY());
        Grid linkSpan;

        // 斜向
        if (dx != 0 && dy != 0) {
            linkSpan = query.getLinkSpan(current, x, y + dy);
            if (linkSpan != null) {
                neighbours.add(linkSpan);
            }

            linkSpan = query.getLinkSpan(current, x + dx, y);
            if (linkSpan != null) {
                neighbours.add(linkSpan);
            }

            linkSpan = query.getLinkSpan(current, x + dx, y + dy);
            if (linkSpan != null) {
                neighbours.add(linkSpan);
            }

            if (!query.walkable(current, x - dx, y)) {
                linkSpan = query.getLinkSpan(current, x - dx, y + dy);
                if (linkSpan != null) {
                    neighbours.add(linkSpan);
                }
            }

            if (!query.walkable(current, x, y - dy)) {
                linkSpan = query.getLinkSpan(current, x + dx, y - dy);
                if (linkSpan != null) {
                    neighbours.add(linkSpan);
                }
            }
            return neighbours;
        }

        // 水平方向
        if (dx != 0) {
            if (query.walkable(current, x + dx, y)) {
                linkSpan = query.getLinkSpan(current, x + dx, y);
                if (linkSpan != null) {
                    neighbours.add(linkSpan);
                }
            }
            if (!query.walkable(current, x, y + 1) && query.walkable(current, x + dx, y + 1)) {
                linkSpan = query.getLinkSpan(current, x + dx, y + 1);
                if (linkSpan != null) {
                    neighbours.add(linkSpan);
                }
            }

            if (!query.walkable(current, x, y - 1) && query.walkable(current, x + dx, y - 1)) {
                linkSpan = query.getLinkSpan(current, x + dx, y - 1);
                if (linkSpan != null) {
                    neighbours.add(linkSpan);
                }
            }
            return neighbours;
        }

        // 垂直方向
        if (dy != 0) {
            // 直走
            if (query.walkable(current, x, y + dy)) {
                linkSpan = query.getLinkSpan(current, x, y + dy);
                if (linkSpan != null) {
                    neighbours.add(linkSpan);
                }
            }
            // 斜向
            if (!query.walkable(current, x + 1, y) && query.walkable(current, x + 1, y + dy)) {
                linkSpan = query.getLinkSpan(current, x + 1, y + dy);
                if (linkSpan != null) {
                    neighbours.add(linkSpan);
                }
            }

            if (!query.walkable(current, x - 1, y) && query.walkable(current, x - 1, y + dy)) {
                linkSpan = query.getLinkSpan(current, x - 1, y + dy);
                if (linkSpan != null) {
                    neighbours.add(linkSpan);
                }
            }
            return neighbours;
        }

        return neighbours;
    }
}
