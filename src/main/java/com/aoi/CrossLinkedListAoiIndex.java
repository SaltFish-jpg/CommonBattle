package com.aoi;




import com.commonbattle.battle.state.EntityId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashMap;

/**
 * 二维十字链表 AOI 索引。
 * 适合对象数量中等、移动频繁且需要按 x/y 两轴快速裁剪矩形范围的场景。
 */
public final class CrossLinkedListAoiIndex implements AoiIndex {
    private final Map<EntityId, Node> nodes = new HashMap<>();
    private Node xHead;
    private Node yHead;

    @Override
    public void add(EntityId id, AoiPoint position) {
        Objects.requireNonNull(id, "id");
        validate2d(position);
        if (nodes.containsKey(id)) {
            throw new IllegalArgumentException("AOI object already exists: " + id.value());
        }

        Node node = new Node(id, position);
        nodes.put(id, node);
        insertByX(node);
        insertByY(node);
    }

    @Override
    public void move(EntityId id, AoiPoint position) {
        Objects.requireNonNull(id, "id");
        validate2d(position);
        Node node = requireNode(id);
        unlinkX(node);
        unlinkY(node);
        node.position = position;
        insertByX(node);
        insertByY(node);
    }

    @Override
    public void remove(EntityId id) {
        Objects.requireNonNull(id, "id");
        Node node = nodes.remove(id);
        if (node == null) {
            return;
        }
        unlinkX(node);
        unlinkY(node);
    }

    @Override
    public Optional<AoiPoint> positionOf(EntityId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(nodes.get(id)).map(node -> node.position);
    }

    @Override
    public List<EntityId> query(AoiBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        validate2d(AoiPoint.of3d(bounds.minX(), bounds.minY(), bounds.minZ()));
        validate2d(AoiPoint.of3d(bounds.maxX(), bounds.maxY(), bounds.maxZ()));

        Set<EntityId> xCandidates = new LinkedHashSet<>();
        for (Node cursor = firstXAtLeast(bounds.minX()); cursor != null && cursor.position.x() <= bounds.maxX(); cursor = cursor.nextX) {
            xCandidates.add(cursor.id);
        }

        List<EntityId> result = new ArrayList<>();
        for (Node cursor = firstYAtLeast(bounds.minY()); cursor != null && cursor.position.y() <= bounds.maxY(); cursor = cursor.nextY) {
            if (xCandidates.contains(cursor.id) && bounds.contains(cursor.position)) {
                result.add(cursor.id);
            }
        }
        return result;
    }

    private Node requireNode(EntityId id) {
        Node node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown AOI object: " + id.value());
        }
        return node;
    }

    private void insertByX(Node node) {
        if (xHead == null || compareX(node, xHead) < 0) {
            node.nextX = xHead;
            if (xHead != null) {
                xHead.prevX = node;
            }
            xHead = node;
            return;
        }

        Node cursor = xHead;
        while (cursor.nextX != null && compareX(cursor.nextX, node) <= 0) {
            cursor = cursor.nextX;
        }
        node.nextX = cursor.nextX;
        node.prevX = cursor;
        if (cursor.nextX != null) {
            cursor.nextX.prevX = node;
        }
        cursor.nextX = node;
    }

    private void insertByY(Node node) {
        if (yHead == null || compareY(node, yHead) < 0) {
            node.nextY = yHead;
            if (yHead != null) {
                yHead.prevY = node;
            }
            yHead = node;
            return;
        }

        Node cursor = yHead;
        while (cursor.nextY != null && compareY(cursor.nextY, node) <= 0) {
            cursor = cursor.nextY;
        }
        node.nextY = cursor.nextY;
        node.prevY = cursor;
        if (cursor.nextY != null) {
            cursor.nextY.prevY = node;
        }
        cursor.nextY = node;
    }

    private void unlinkX(Node node) {
        if (node.prevX != null) {
            node.prevX.nextX = node.nextX;
        } else if (xHead == node) {
            xHead = node.nextX;
        }
        if (node.nextX != null) {
            node.nextX.prevX = node.prevX;
        }
        node.prevX = null;
        node.nextX = null;
    }

    private void unlinkY(Node node) {
        if (node.prevY != null) {
            node.prevY.nextY = node.nextY;
        } else if (yHead == node) {
            yHead = node.nextY;
        }
        if (node.nextY != null) {
            node.nextY.prevY = node.prevY;
        }
        node.prevY = null;
        node.nextY = null;
    }

    private Node firstXAtLeast(int x) {
        Node cursor = xHead;
        while (cursor != null && cursor.position.x() < x) {
            cursor = cursor.nextX;
        }
        return cursor;
    }

    private Node firstYAtLeast(int y) {
        Node cursor = yHead;
        while (cursor != null && cursor.position.y() < y) {
            cursor = cursor.nextY;
        }
        return cursor;
    }

    private static int compareX(Node left, Node right) {
        int byX = Integer.compare(left.position.x(), right.position.x());
        if (byX != 0) {
            return byX;
        }
        int byY = Integer.compare(left.position.y(), right.position.y());
        if (byY != 0) {
            return byY;
        }
        return left.id.value().compareTo(right.id.value());
    }

    private static int compareY(Node left, Node right) {
        int byY = Integer.compare(left.position.y(), right.position.y());
        if (byY != 0) {
            return byY;
        }
        int byX = Integer.compare(left.position.x(), right.position.x());
        if (byX != 0) {
            return byX;
        }
        return left.id.value().compareTo(right.id.value());
    }

    private static void validate2d(AoiPoint position) {
        Objects.requireNonNull(position, "position");
        if (position.z() != 0) {
            throw new IllegalArgumentException("Cross linked list AOI only supports 2D positions");
        }
    }

    private static final class Node {
        private final EntityId id;
        private AoiPoint position;
        private Node prevX;
        private Node nextX;
        private Node prevY;
        private Node nextY;

        private Node(EntityId id, AoiPoint position) {
            this.id = id;
            this.position = position;
        }
    }
}
