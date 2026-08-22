package com.findpath.findPathPainter.painter;

import com.findpath.findPathPainter.constants.WAWDirectionEnum;
import com.findpath.findPathPainter.model.PathPainter;
import com.findpath.jps.Grid;
import com.findpath.jps.GridQuery;
import lombok.extern.slf4j.Slf4j;

import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;


/**
 * <p>
 * 转向点：当沿着墙壁推进，无法保持方向推进时的点，为转向点
 * </p>
 * <p>
 * 跳点：绕过墙壁的点，查找跳点的时候，需要保持各个跳点连通，同时缩短跳点数量
 * </p>
 */
@Slf4j
public class WAWGridPainter {

    public static LinkedList<Grid> findPath(GridQuery query, Grid start, Grid end, PathPainter painter,
                                            boolean mergeLine) {

        int[] collidePoint = straightTo(query, start.getX(), start.getY(), end.getX(), end.getY(), painter);
        LinkedList<Grid> path = new LinkedList<>();
        if (collidePoint[2] == end.getX() && collidePoint[3] == end.getY()) {
            path.add(start);
        } else {
            List<TouchingWall> jumpList =
                    walkAlongTheWall(query, start.getX(), start.getY(), end.getX(), end.getY(), collidePoint, painter);
            if (mergeLine) {
                mergeJumpToPath(query, path, jumpList, painter);
            } else {
                for (TouchingWall jump : jumpList) {
                    path.add(query.findGrid(jump.getX(), jump.getY()));
                }
            }
        }
        path.add(end);
        return path;
    }

    private static LinkedList<TouchingWall> walkAlongTheWall(GridQuery query, int startX, int startY, int endX,
                                                             int endY, int[] collidePoint,
                                                             PathPainter painter) {

        painter.info(String.format("开始寻路, 起点(%s, %s), 终点(%s, %s)", startX, startY, endX, endY));

        Map<TouchingWall, TouchingWall> parentMap = new HashMap<>();
        BitSet closed = new BitSet();
        Queue<TouchingWall> queue = new PriorityQueue<>();
        TouchingWall startTouch = TouchingWall.valueOf(startX, startY, null, null);
        startTouch.setFinish(true);
        // 根据碰撞点，创建两个WAW，放入优先级队列

        createTouchingWall(queue, query, collidePoint, startTouch, endX, endY, closed, parentMap);

        TouchingWall touch = null;

        // 循环遍历所有的WAW, 向前推进
        while (queue.size() > 0) {
            touch = queue.poll();

            double w = queue.peek() == null ? Double.MAX_VALUE : queue.peek().getW();
            while (touch.getW() <= w) {
                step(queue, query, touch, closed, parentMap, endX, endY, painter);

                // 如果绕过墙壁，则finish为true，拉取直线，
                if (touch.isFinish()) {
                    // 向着终点拉射线，寻找碰撞点
                    int[] center = straightTo(query, touch.getX(), touch.getY(), endX, endY, painter);

                    // 如果碰撞点是终点，结束推进
                    if (center[2] == endX && center[3] == endY) {
                        return backTraceJump(query, parentMap, touch, painter);
                    } else {
                        createTouchingWall(queue, query, center, touch, endX, endY, closed, parentMap);
                    }
                    break;
                }

                if (touch.isJump() || touch.isDead()) {
                    break;
                }
            }

            if (touch.isDead() || touch.isJump() || touch.isFinish()) {
                continue;
            }
            queue.add(touch);
        }

        return backTraceJump(query, parentMap, touch, painter);
    }

    private static Grid getGrid(GridQuery query, TouchingWall touch) {
        return query.findGrid(touch.getX(), touch.getY());
    }

    private static LinkedList<TouchingWall> backTraceJump(GridQuery query, Map<TouchingWall, TouchingWall> parent,
                                                          TouchingWall touch, PathPainter painter) {
        TouchingWall child = touch, father = parent.get(child);
        LinkedList<TouchingWall> jumpList = new LinkedList<>();
        jumpList.addFirst(child);

        painter.info("收集路径信息");

        while (father != null) {
            // 如果下一个点是finish点，则递归调用，寻找从finish点到当前点的路径，并插入到现有路径中
            if (father.isFinish()) {
                int[] collidePoint =
                        straightTo(query, father.getX(), father.getY(), child.getX(), child.getY(), painter);
                if (collidePoint[2] != child.getX() || collidePoint[3] != child.getY()) {
                    LinkedList<TouchingWall> centerJumpList = walkAlongTheWall(query, father.getX(), father.getY(),
                            child.getX(), child.getY(), collidePoint, painter);
                    centerJumpList.removeLast();
                    centerJumpList.removeFirst();
                    centerJumpList.addAll(jumpList);
                    jumpList = centerJumpList;
                }
            }

            child = father;
            father = parent.get(child);
            jumpList.addFirst(child);
        }

        // mergeJumpToPath(query, path, jumpList, painter);

        // 结果是根据Parent拿到的touch列表，从尾向头遍历。
        // 遍历过程中，记录第一个跳点，当下一个不是跳点，则从起点到终点开始合并
        // LinkedList<MyGrid> centerPath = kong.findPath(query, start, getGrid(query, child), painter);
        // centerPath.removeLast();
        // path.addFirst(centerPath.getLast());
        // 没有下一个parent了，返回路径列表
        return jumpList;
    }

    /**
     * 合并所有跳点，构建一条直线，将结果放入路径
     *
     * @param query
     * @param path
     * @param jumpList
     * @param painter
     */
    private static void mergeJumpToPath(GridQuery query, LinkedList<Grid> path, List<TouchingWall> jumpList,
                                        PathPainter painter) {
        painter.info("开始合并路径");
        // 如果小于两个，直接把这两个点放入path
        if (jumpList.size() <= 2) {
            for (TouchingWall jump : jumpList) {
                path.add(getGrid(query, jump));
            }
            return;
        }

        // 从头遍历，当下一个点，无法构成直线，则前面的点让如pathFirst
        TouchingWall pre = null, next = null, link = null;
        for (TouchingWall jump : jumpList) {
            if (pre == null) {
                pre = jump;
                continue;
            }

            if (link == null) {
                link = jump;
                continue;
            }

            next = jump;

            if (canStraightRaise(query, pre, next, painter)) {
                // log.info("back trace, point:({},{}) crossed.", link.getX(), link.getY());
                link = next;
            } else {
                path.add(getGrid(query, pre));
                pre = link;
                link = next;
            }
        }

        if (link != null) {
            path.add(getGrid(query, pre));
            path.add(getGrid(query, link));
        }
    }

    private static boolean canStraightRaise(GridQuery query, TouchingWall pre, TouchingWall next, PathPainter painter) {
        Grid preGrid = getGrid(query, pre);
        Grid nextGrid = getGrid(query, next);
        int[] collidePoint = straightTo(query, preGrid.getX(), pre.getY(), nextGrid.getX(), nextGrid.getY(), painter);
        return collidePoint[2] == nextGrid.getX() && collidePoint[3] == nextGrid.getY();
    }

    /**
     * 沿着墙壁走一步
     *
     * @param queue
     * @param query
     * @param touch
     * @param closed
     * @param parentMap
     * @param endX
     * @param endY
     * @param painter
     */
    private static void step(Queue<TouchingWall> queue, GridQuery query, TouchingWall touch, BitSet closed,
                             Map<TouchingWall, TouchingWall> parentMap, int endX, int endY, PathPainter painter) {
        painter.startRecord();
        // 如果抵达拐角，则转向移动，否则沿着固定方向移动，设置bitset
        int[] nextIndex = dirNextIndex(touch, touch.getDir());

        Grid nextGrid = query.findGrid(nextIndex[0], nextIndex[1]);
        if (nextGrid == null) {
            touch.setDead(true);
            return;
        }

        if (closed.get(toTag(query, nextIndex[0], nextIndex[1], touch.getWallDir().ordinal()))) {
            touch.setDead(true);
            return;
        }
        if (!nextGrid.walkable()) {
            turn(queue, query, touch, closed, parentMap, endX, endY, painter);
        } else {
            touch.setX(nextIndex[0]);
            touch.setY(nextIndex[1]);
        }

        painter.traverse(touch.getX(), touch.getY());
        closed.set(toTag(query, touch.getX(), touch.getY(), touch.getWallDir().ordinal()));
        updateWeight(touch, parentMap.get(touch), endX, endY);

        int[] wallIndex = dirNextIndex(touch, touch.getWallDir());
        Grid wall = query.findGrid(wallIndex[0], wallIndex[1]);
        if (wall.walkable()) {
            // 如果推进到【跳点】，保留跳点。判断跳跃方向是否向着终点方向
            if (finish(touch, endX, endY)) {
                touch.setFinish(true);
                return;
            }

            // 不能直达，则作为跳点，创建新的touchingWall
            touch.setJump(true);
            painter.jump(touch.getX(), touch.getY());

            TouchingWall jump =
                    TouchingWall.valueOf(wallIndex[0], wallIndex[1], touch.getWallDir(), touch.getDir().getBack());
            painter.traverse(wallIndex[0], wallIndex[1]);
            parentMap.put(jump, touch);
            updateWeight(jump, touch, endX, endY);

            queue.add(jump);
        }
        painter.cost("step");
    }

    private static int toTag(GridQuery query, int x, int y, int wallDir) {
        return (y * query.getW() + x) << 2 | wallDir;
    }

    private static boolean finish(TouchingWall touch, int endX, int endY) {
        int[] walkDir = dirNextIndex(touch, touch.getDir());
        int[] wallDir = dirNextIndex(touch, touch.getWallDir());

        boolean sameDirWithWalk = (walkDir[0] - touch.getX()) * (endX - touch.getX()) >= 0
                && (walkDir[1] - touch.getY()) * (endY - touch.getY()) >= 0;
        boolean sameDirWithWall = (wallDir[0] - touch.getX()) * (endX - touch.getX()) >= 0
                && (wallDir[1] - touch.getY()) * (endY - touch.getY()) >= 0;

        return sameDirWithWalk && sameDirWithWall;
    }

    private static void turn(Queue<TouchingWall> queue, GridQuery gridQuery, TouchingWall touch, BitSet closed,
                             Map<TouchingWall, TouchingWall> parentMap, int endX, int endY, PathPainter painter) {
        WAWDirectionEnum dir = touch.getDir();
        int[] neighbours = dir.getNeighbours();
        WAWDirectionEnum turnDir;

        turnDir = neighbours[0] == touch.getWallDir().ordinal() ? WAWDirectionEnum.valueOf(neighbours[1])
                : WAWDirectionEnum.valueOf(neighbours[0]);

        touch.setWallDir(dir);
        touch.setDir(turnDir);

        step(queue, gridQuery, touch, closed, parentMap, endX, endY, painter);
    }

    private static int[] dirNextIndex(TouchingWall touch, WAWDirectionEnum dir) {
        switch (dir) {
            case Up:
                return new int[]{touch.getX(), touch.getY() + 1};
            case Down:
                return new int[]{touch.getX(), touch.getY() - 1};
            case Left:
                return new int[]{touch.getX() - 1, touch.getY()};
            case Right:
                return new int[]{touch.getX() + 1, touch.getY()};
            default:
                throw new RuntimeException();
        }
    }

    /**
     * 按照两个方向创建TouchingWall
     *
     * @param queue
     * @param query
     * @param collidePoint
     * @param parent
     * @param endX
     * @param endY
     * @param closed
     * @param parentMap
     */
    private static void createTouchingWall(Queue<TouchingWall> queue, GridQuery query, int[] collidePoint,
                                           TouchingWall parent, int endX, int endY, BitSet closed, Map<TouchingWall, TouchingWall> parentMap) {
        int x = collidePoint[0];
        int y = collidePoint[1];
        int dx = collidePoint[2] - x;
        int dy = collidePoint[3] - y;

        setClose(query, closed, x, y);

        WAWDirectionEnum wallDir;
        if (dx != 0) {
            wallDir = dx > 0 ? WAWDirectionEnum.Right : WAWDirectionEnum.Left;
        } else {
            wallDir = dy > 0 ? WAWDirectionEnum.Up : WAWDirectionEnum.Down;
        }

        int[] neighbours = wallDir.getNeighbours();
        for (int neighbour : neighbours) {
            WAWDirectionEnum dir = WAWDirectionEnum.values()[neighbour];
            TouchingWall touchingWall = TouchingWall.valueOf(x, y, dir, wallDir);
            updateWeight(touchingWall, parent, endX, endY);
            parentMap.put(touchingWall, parent);
            queue.add(touchingWall);
        }

    }

    private static void updateWeight(TouchingWall touchingWall, TouchingWall parent, int endX, int endY) {
        double d = distance(touchingWall.getX(), touchingWall.getY(), parent.getX(), parent.getY());
        touchingWall.setG(d + parent.getG());
        touchingWall.setW(touchingWall.getG() + distance(touchingWall.getX(), touchingWall.getY(), endX, endY));
    }

    private static double distance(int x1, int y1, int x2, int y2) {
        return Math.abs(x2 - x1) + Math.abs(y2 - y1);
    }

    private static void setClose(GridQuery query, BitSet closed, int x, int y) {
        closed.set(y * query.getW() + x);
    }

    private static int[] straightTo(GridQuery gridQuery, int startX, int startY, int endX, int endY,
                                    PathPainter painter) {
        painter.startRecord();
        int dx = endX - startX, dy = endY - startY;
        double distance = Math.sqrt(dx * dx + dy * dy);

        int[] step = new int[2];
        int abs_dx = Math.abs(dx);
        int abs_dy = Math.abs(dy);
        step[0] = dx == 0 ? 0 : dx / abs_dx;
        step[1] = dy == 0 ? 0 : dy / abs_dy;

        float[] dirInfo = new float[]{(float) (distance / abs_dx), (float) (distance / abs_dy)};
        float[] tMax = new float[]{(float) (distance / abs_dx), (float) (distance / abs_dy)};
        int[] index = new int[]{startX, startY, endX, endY};
        painter.traverse(startX, startY);

        int dir;
        Grid cur = gridQuery.findGrid(startX, startY);

        while (true) {
            if (index[0] == index[2] && index[1] == index[3]) {
                break;
            }

            dir = tMax[1] == 0 || (tMax[0] != 0 && tMax[0] < tMax[1]) ? 0 : 1;
            index[dir] += step[dir];
            Grid linkGrid = gridQuery.getLinkSpan(cur, index[0], index[1]);
            if (linkGrid == null || !linkGrid.walkable()) {
                index[2] = index[0];
                index[3] = index[1];
                index[dir] -= step[dir];
                break;
            }

            painter.traverse(linkGrid.getX(), linkGrid.getY());

            tMax[dir] += dirInfo[dir];
            cur = linkGrid;
        }
        painter.cost("straightTo");
        return index;
    }

}