package com.findpath.findPathPainter.painter;

import com.findpath.findPathPainter.model.PathPainter;
import com.findpath.jps.Grid;
import com.findpath.jps.GridNode;
import com.findpath.jps.GridQuery;

import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

public class AStarGridPainter {
    private static ThreadLocal<BitSet> CHECKED_TL = ThreadLocal.withInitial(() -> new BitSet());

    public static List<Grid> findPath(GridQuery gridQuery, Grid start, Grid end, PathPainter painter) {
        Map<Grid, Grid> parentMap = new HashMap<>();
        // 只需要记录g就行,g依赖前置结点,h直接算
        Map<Grid, Double> gMap = new HashMap<>();
        Set<Grid> closed = new HashSet<>();
        // 待计算格子
        Queue<GridNode> waiting = new PriorityQueue<>();
        waiting.add(new GridNode(start, 0D, 0D));
        gMap.put(start, 0D);

        while (!waiting.isEmpty()) {
            // 取出优先级最高的格子(权值最小)
            Grid current = waiting.poll().getGrid();
            // 已经遍历过
            if (closed.contains(current)) {
                continue;
            }

            if (painter != null  && current != start && current != end) {
                painter.open(current.getX(), current.getY());
            }

            // 到达终点
            if (current.equals(end)) {
                return backTrackGrid(gridQuery, parentMap, current);
            }

            // 这个时候才是close的
            closed.add(current);

            // 获取周围格子信息
            Collection<? extends Grid> rounds = gridQuery.getNeighbours(current);
            // 遍历周围的点
            for (Grid round : rounds) {
                if (painter != null) {
                    painter.traverse(round.getX(), round.getY());
                }

                if (closed.contains(round)) {
                    continue;
                }

                if (round.getY() != current.getY() && round.getX() != current.getX()) {
                    // 斜边判断相邻方向是否都为阻挡，不允许从两个相邻阻挡点之间穿过
                    // 保留原实现非严格禁止切角
                    if (!gridQuery.walkable(current, current.getX(), round.getY()) && !gridQuery.walkable(current,
                        round.getX(), current.getY())) {
                        continue;
                    }
                }

                Double oldG = gMap.getOrDefault(round, Double.MAX_VALUE);
                double newG = gMap.get(current) + gridQuery.calcPathDistance(current, round);
                if (newG < oldG) {
                    gMap.put(round, newG);
                    parentMap.put(round, current);
                    waiting.add(new GridNode(round, newG + gridQuery.calcPathDistance(round, end), newG));
                }
            }
        }

        return Collections.emptyList();
    }

    private static List<Grid> backTrackGrid(GridQuery query, Map<Grid, Grid> parentMap, Grid grid) {
        LinkedList<Grid> path = new LinkedList<>();
        Grid current = grid;
        while (parentMap.containsKey(current)) {
            path.addFirst(query.toVector(current));
            current = parentMap.get(current);
        }
        return path;
    }
}
