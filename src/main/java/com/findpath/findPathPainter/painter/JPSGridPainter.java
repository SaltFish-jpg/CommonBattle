package com.findpath.findPathPainter.painter;

import com.findpath.findPathPainter.model.PathPainter;
import com.findpath.jps.Grid;
import com.findpath.jps.GridNode;
import com.findpath.jps.GridQuery;
import com.findpath.jps.role.CornerCuttingRule;
import com.findpath.jps.role.JPSRule;
import com.findpath.jps.role.NoCornerCuttingRule;
import lombok.extern.slf4j.Slf4j;

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

@Slf4j
public class JPSGridPainter {

    /**
     * 是否允许切角
     */
    private final boolean allowCornerCutting;

    public static final JPSRule NO_CORNER_CUTTING_RULE = new NoCornerCuttingRule();

    public static final JPSRule CORNER_CUTTING_RULE = new CornerCuttingRule();

    public JPSGridPainter(boolean allowCornerCutting) {
        this.allowCornerCutting = allowCornerCutting;
    }

    /**
     * 寻路
     *
     * @param query
     * @param start
     * @param end
     * @param painter
     * @return
     */
    public List<Grid> findPath(GridQuery query, Grid start, Grid end, PathPainter painter) {
        Set<Grid> closed = new HashSet<>();
        Map<Grid, Grid> parentMap = new HashMap<>();
        Map<Grid, Double> gMap = new HashMap<>();
        Queue<GridNode> open = new PriorityQueue<>();

        open.add(new GridNode(start, 0D, 0D));
        gMap.put(start, 0D);
        Grid current;
        while (!open.isEmpty()) {
            current = open.poll().getGrid();
            if (closed.contains(current)) {
                continue;
            }

            if (current.equals(end)) {
                return backtrace(query, current, parentMap);
            }

            closed.add(current);
            addNextToOpen(query, end, current, open, closed, parentMap, gMap, painter);
        }

        return Collections.emptyList();
    }

    private List<Grid> backtrace(GridQuery query, Grid grid, Map<Grid, Grid> parentMap) {
        LinkedList<Grid> path = new LinkedList<>();
        Grid current = grid;
        while (parentMap.containsKey(current)) {
            path.addFirst(query.toVector(current));
            current = parentMap.get(current);
        }
        return path;
    }

    private void addNextToOpen(GridQuery query, Grid end, Grid current, Queue<GridNode> open, Collection<Grid> closed,
        Map<Grid, Grid> parentMap, Map<Grid, Double> gMap, PathPainter painter) {
        Collection<Grid> neighbours = findNeighbours(query, current, parentMap);

        double ng;
        Grid jumpRoad;
        for (Grid neighbour : neighbours) {
            // painter.traverse(neighbour.getX(), neighbour.getY());

            jumpRoad = jump(query, neighbour, current, end, painter);
            if (jumpRoad == null || closed.contains(jumpRoad)) {
                continue;
            }
            painter.jump(jumpRoad.getX(), jumpRoad.getY());

            Double oldG = gMap.getOrDefault(jumpRoad, Double.MAX_VALUE);
            ng = gMap.get(current) + query.calcPathDistance(jumpRoad, current);

            if (ng < oldG) {
                gMap.put(jumpRoad, ng);
                parentMap.put(jumpRoad, current);
                open.add(new GridNode(jumpRoad, ng + query.calcPathDistance(jumpRoad, end), ng));
            }
        }
    }

    private Grid jump(GridQuery query, Grid neighbour, Grid current, Grid end, PathPainter painter) {

        if (neighbour == null) {
            return null;
        }

        int dx = Integer.signum(neighbour.getX() - current.getX());
        int dy = Integer.signum(neighbour.getY() - current.getY());

        while (neighbour != null) {

            if (!neighbour.walkable()) {
                return null;
            }

            painter.traverse(neighbour.getX(), neighbour.getY());
            if (neighbour.equals(end)) {
                return neighbour;
            }

            if (hasForcedNeighbour(query, neighbour, dx, dy)) {
                return neighbour;
            }

            if (dx != 0 && dy != 0) {
                // 剪枝
                // 分量方向找到任意一个就返回
                if ((jump(query, query.getLinkSpan(neighbour, neighbour.getX() + dx, neighbour.getY()), neighbour, end,
                    painter)) != null) {
                    return neighbour;
                }

                if ((jump(query, query.getLinkSpan(neighbour, neighbour.getX(), neighbour.getY() + dy), neighbour, end,
                    painter)) != null) {
                    return neighbour;
                }

                if (!canDiagonal(query, current, neighbour, dx, dy)) {
                    return null;
                }
            }

            current = neighbour;
            neighbour = query.getLinkSpan(current, current.getX() + dx, current.getY() + dy);
        }

        return null;
    }

    private boolean canDiagonal(GridQuery query, Grid parent, Grid current, int dx, int dy) {
        return getJPSRule().canDiagonal(query, parent, current, dx, dy);
    }

    private boolean hasForcedNeighbour(GridQuery query, Grid current, int dx, int dy) {
        return getJPSRule().hasForcedNeighbour(query, current, dx, dy);
    }

    private Collection<Grid> findNeighbours(GridQuery query, Grid current, Map<Grid, Grid> parentMap) {
        return getJPSRule().findNeighbours(query, current, parentMap);
    }

    private JPSRule getJPSRule() {
        return allowCornerCutting ? CORNER_CUTTING_RULE : NO_CORNER_CUTTING_RULE;
    }
}
