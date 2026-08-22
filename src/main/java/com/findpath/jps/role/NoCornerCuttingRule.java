package com.findpath.jps.role;

import com.findpath.jps.Grid;
import com.findpath.jps.GridQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 不可切角
 *
 */
public class NoCornerCuttingRule implements JPSRule {

    @Override
    public boolean canDiagonal(GridQuery query, Grid parent, Grid current, int dx, int dy) {
        // 斜向方向有任意一阻挡
        // x轴方向
        if (!query.walkable(parent, current.getX() + dx, current.getY())) {
            return false;
        }
        // y轴方向
        return query.walkable(parent, current.getX(), current.getY() + dy);
    }

    @Override
    public boolean hasForcedNeighbour(GridQuery query, Grid current, int dx, int dy) {
        // 斜向没有
        if (dx != 0 && dy != 0) {
            return false;
        }

        if (dx != 0) {
            // 上侧
            // #00
            // PC0
            // 000
            if (!query.walkable(current, current.getX() - dx, current.getY() + 1) && query.walkable(current,
                current.getX(), current.getY() + 1)) {
                return true;
            }
            // 下侧
            // 000
            // PC0
            // #00
            return !query.walkable(current, current.getX() - dx, current.getY() - 1) && query.walkable(current,
                current.getX(), current.getY() - 1);
        }

        if (dy != 0) {
            // 左侧
            // 000
            // 0C0
            // #P0
            if (!query.walkable(current, current.getX() - 1, current.getY() - dy) && query.walkable(current,
                current.getX() - 1, current.getY())) {
                return true;
            }
            // 右侧
            // 000
            // 0C0
            // 0P#
            return !query.walkable(current, current.getX() + 1, current.getY() - dy) && query.walkable(current,
                current.getX() + 1, current.getY());
        }

        return false;
    }

    @Override
    public Collection<Grid> findNeighbours(GridQuery query, Grid current, Map<Grid, Grid> parentMap) {

        List<Grid> neighbours = new ArrayList<>();
        Grid parent = parentMap.get(current);
        final int x = current.getX();
        final int y = current.getY();

        // 初始八个方向
        if (parent == null) {
            int[] dxArr = new int[] {-1, 0, 1};
            int[] dyArr = new int[] {-1, 0, 1};
            for (int dx : dxArr) {
                for (int dy : dyArr) {
                    // 原点
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    // 不可达
                    Grid neighbour = query.getLinkSpan(current, x + dx, y + dy);
                    if (neighbour == null || !neighbour.walkable()) {
                        continue;
                    }

                    // 斜向移动
                    if (dx != 0 && dy != 0) {
                        // x轴分量不可达
                        if (!query.walkable(current, x + dx, y)) {
                            continue;
                        }

                        // y轴分量不可达
                        if (!query.walkable(current, x, y + dy)) {
                            continue;
                        }

                        neighbours.add(neighbour);
                        continue;
                    }
                    // 非斜向
                    neighbours.add(neighbour);
                }
            }
            return neighbours;
        }

        final int dx = Integer.signum(x - parent.getX());
        final int dy = Integer.signum(y - parent.getY());
        Grid neighbour;

        // 这种情况:
        // 0C0
        // P00
        // 000
        // 斜向移动
        if (dx != 0 && dy != 0) {
            neighbour = query.getLinkSpan(current, x + dx, y);
            // x轴邻居可达
            boolean xWalkAble = neighbour != null && neighbour.walkable();
            if (xWalkAble) {
                neighbours.add(neighbour);
            }

            neighbour = query.getLinkSpan(current, x, y + dy);
            // y轴邻居可达
            boolean yWalkAble = neighbour != null && neighbour.walkable();
            if (yWalkAble) {
                neighbours.add(neighbour);
            }

            // 斜向的邻居可达
            neighbour = query.getLinkSpan(current, x + dx, y + dy);
            if (xWalkAble && yWalkAble && neighbour != null && neighbour.walkable()) {
                neighbours.add(neighbour);
            }

            return neighbours;
        }

        // 水平移动
        if (dx != 0) {
            // 保持当前方向
            neighbour = query.getLinkSpan(current, x + dx, y);
            boolean xWalkable = neighbour != null && neighbour.walkable();
            if (xWalkable) {
                neighbours.add(neighbour);
            }

            // 这种情况:P是父节点,C是当前节点
            // #00
            // PC0
            // 000
            // 父节点上方阻挡,当前节点上方通
            if (!query.walkable(current, x - dx, y + 1) && query.walkable(current, x, y + 1)) {
                // 垂直方向
                neighbour = query.getLinkSpan(current, x, y + 1);
                if (neighbour != null) {
                    neighbours.add(neighbour);
                }
                // 斜向
                // 垂直是可达的
                neighbour = query.getLinkSpan(current, x + dx, y + 1);
                if (neighbour != null && neighbour.walkable() && xWalkable) {
                    neighbours.add(neighbour);
                }
            }
            // 这种情况:P是父节点,C是当前节点
            // 000
            // PC0
            // #00
            // 父节点下方阻挡
            if (!query.walkable(current, x - dx, y - 1) && query.walkable(current, x, y - 1)) {
                // 垂直方向
                neighbour = query.getLinkSpan(current, x, y - 1);
                if (neighbour != null) {
                    neighbours.add(neighbour);
                }
                // 斜向
                // 垂直是可达的
                neighbour = query.getLinkSpan(current, x + dx, y - 1);
                if (neighbour != null && neighbour.walkable() && xWalkable) {
                    neighbours.add(neighbour);
                }
            }
            return neighbours;
        }

        // 垂直移动
        if (dy != 0) {
            // 保持当前方向
            neighbour = query.getLinkSpan(current, x, y + dy);
            boolean yWalkable = neighbour != null && neighbour.walkable();
            if (yWalkable) {
                neighbours.add(neighbour);
            }
            // 这种情况:P是父节点,C是当前节点
            // 000
            // 0C0
            // #P0
            // 左侧
            if (!query.walkable(current, x - 1, y - dy) && query.walkable(current, x - 1, y)) {
                // 水平
                neighbour = query.getLinkSpan(current, x - 1, y);
                if (neighbour != null) {
                    neighbours.add(neighbour);
                }
                // X方向是可达的
                // 斜向
                neighbour = query.getLinkSpan(current, x - 1, y + dy);
                if (neighbour != null && neighbour.walkable() && yWalkable) {
                    neighbours.add(neighbour);
                }
            }
            // 这种情况:P是父节点,C是当前节点
            // 000
            // 0C0
            // 0P#
            // 右侧
            if (!query.walkable(current, x + 1, y - dy) && query.walkable(current, x + 1, y)) {
                // 水平
                neighbour = query.getLinkSpan(current, x + 1, y);
                if (neighbour != null) {
                    neighbours.add(neighbour);
                }
                // X方向是可达的
                // 斜向
                neighbour = query.getLinkSpan(current, x + 1, y + dy);
                if (neighbour != null && neighbour.walkable() && yWalkable) {
                    neighbours.add(neighbour);
                }
            }
            return neighbours;
        }

        return neighbours;
    }
}
