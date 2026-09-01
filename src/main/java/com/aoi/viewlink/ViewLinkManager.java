package com.aoi.viewlink;

import com.commonbattle.core.EntityId;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * 文档版十字链表视野关系管理器。
 * <p>
 * 该类型不负责空间检索，调用方应先用九宫格、Chunk 或其他空间索引找出候选对象，再把候选对象交给
 * {@link #refresh(EntityId, List, int, ToIntFunction)} 做可见关系差量维护。
 */
public final class ViewLinkManager {
    private final int maxObjectIndex;
    private final Map<EntityId, Viewer> viewers = new HashMap<>();
    private final Map<Integer, EntityId> idsByObjectIndex = new HashMap<>();

    public ViewLinkManager(int maxObjectIndex) {
        if (maxObjectIndex <= 0) {
            throw new IllegalArgumentException("maxObjectIndex must be positive");
        }
        this.maxObjectIndex = maxObjectIndex;
    }

    /**
     * 注册可参与视野关系的对象。
     * objectIndex 必须是场景内连续或近似连续的小整数，用于位标记 O(1) 判断可见关系。
     */
    public void add(EntityId id, int objectIndex) {
        Objects.requireNonNull(id, "id");
        validateObjectIndex(objectIndex);
        if (viewers.containsKey(id)) {
            throw new IllegalArgumentException("View object already exists: " + id.value());
        }
        EntityId old = idsByObjectIndex.putIfAbsent(objectIndex, id);
        if (old != null) {
            throw new IllegalArgumentException("Object index already exists: " + objectIndex);
        }
        viewers.put(id, new Viewer(id, objectIndex, maxObjectIndex));
    }

    /**
     * 移除对象，并同步解除它和其他对象之间的所有可见边。
     */
    public void remove(EntityId id) {
        Viewer viewer = viewers.get(Objects.requireNonNull(id, "id"));
        if (viewer == null) {
            return;
        }

        List<LinkPair> pairs = new ArrayList<>();
        for (LinkNode cursor = viewer.head; cursor != null; cursor = cursor.next) {
            pairs.add(cursor.parent);
        }
        for (LinkPair pair : pairs) {
            unlink(pair);
        }

        viewers.remove(id);
        idsByObjectIndex.remove(viewer.objectIndex);
    }

    public boolean contains(EntityId id) {
        return viewers.containsKey(Objects.requireNonNull(id, "id"));
    }

    public boolean canSee(EntityId viewerId, EntityId targetId) {
        Viewer viewer = requireViewer(viewerId);
        Viewer target = requireViewer(targetId);
        return viewer.visibleBits.get(target.objectIndex);
    }

    public List<EntityId> visibleTargets(EntityId viewerId) {
        Viewer viewer = requireViewer(viewerId);
        List<EntityId> result = new ArrayList<>();
        for (LinkNode cursor = viewer.head; cursor != null; cursor = cursor.next) {
            result.add(cursor.target.id);
        }
        return result;
    }

    /**
     * 建立双向可见边。
     * 两端链表节点共享同一个 LinkPair，后续删除任意一端时可通过 parent 直接定位另一端节点。
     */
    public boolean link(EntityId leftId, EntityId rightId) {
        Viewer left = requireViewer(leftId);
        Viewer right = requireViewer(rightId);
        if (left == right) {
            return false;
        }
        if (left.visibleBits.get(right.objectIndex)) {
            return false;
        }

        LinkPair pair = new LinkPair(left, right);
        append(left, pair.leftNode);
        append(right, pair.rightNode);
        left.visibleBits.set(right.objectIndex);
        right.visibleBits.set(left.objectIndex);
        return true;
    }

    /**
     * 解除双向可见边。
     * 如果调用方只有对象 ID，本方法需要先在一端链表中找到对应节点；刷新流程会直接记录 LinkPair，
     * 因此批量 leave 时不需要再遍历另一端链表。
     */
    public boolean unlink(EntityId leftId, EntityId rightId) {
        Viewer left = requireViewer(leftId);
        Viewer right = requireViewer(rightId);
        LinkNode leftNode = findNode(left, right);
        if (leftNode == null) {
            return false;
        }
        unlink(leftNode.parent);
        return true;
    }

    /**
     * 根据调用方传入的新候选视野刷新观察者的可见关系。
     * capacity 小于 0 表示不限制视野数量；priority 值越小，进入视野的优先级越高。
     */
    public ViewLinkChangeSet refresh(EntityId viewerId, List<EntityId> candidateIds, int capacity,
                                     ToIntFunction<EntityId> priority) {
        Objects.requireNonNull(candidateIds, "candidateIds");
        Objects.requireNonNull(priority, "priority");
        Viewer viewer = requireViewer(viewerId);

        Set<EntityId> normalizedCandidates = new LinkedHashSet<>();
        for (EntityId candidateId : candidateIds) {
            Viewer candidate = requireViewer(candidateId);
            if (candidate != viewer) {
                normalizedCandidates.add(candidateId);
            }
        }

        List<LinkPair> leavePairs = new ArrayList<>();
        List<EntityId> leave = new ArrayList<>();
        for (LinkNode cursor = viewer.head; cursor != null; cursor = cursor.next) {
            if (!normalizedCandidates.contains(cursor.target.id)) {
                leavePairs.add(cursor.parent);
                leave.add(cursor.target.id);
            }
        }

        List<EntityId> enterCandidates = new ArrayList<>();
        for (EntityId candidateId : normalizedCandidates) {
            if (!canSee(viewerId, candidateId)) {
                enterCandidates.add(candidateId);
            }
        }
        enterCandidates.sort(Comparator
                .comparingInt(priority)
                .thenComparing(EntityId::value));

        for (LinkPair pair : leavePairs) {
            unlink(pair);
        }

        List<EntityId> enter = new ArrayList<>();
        for (EntityId candidateId : enterCandidates) {
            if (capacity >= 0) {
                freeLowPrioritySlot(viewer, candidateId, capacity, priority, leave);
            }
            if (capacity < 0 || viewer.size < capacity) {
                link(viewerId, candidateId);
                enter.add(candidateId);
            }
        }

        return new ViewLinkChangeSet(enter, leave);
    }

    private void freeLowPrioritySlot(Viewer viewer, EntityId candidateId, int capacity,
                                     ToIntFunction<EntityId> priority, List<EntityId> leave) {
        if (viewer.size < capacity) {
            return;
        }
        LinkNode worst = null;
        int worstPriority = Integer.MIN_VALUE;
        for (LinkNode cursor = viewer.head; cursor != null; cursor = cursor.next) {
            int currentPriority = priority.applyAsInt(cursor.target.id);
            if (worst == null || currentPriority > worstPriority) {
                worst = cursor;
                worstPriority = currentPriority;
            }
        }
        if (worst != null && priority.applyAsInt(candidateId) < worstPriority) {
            leave.add(worst.target.id);
            unlink(worst.parent);
        }
    }

    private void unlink(LinkPair pair) {
        removeNode(pair.leftViewer, pair.leftNode);
        removeNode(pair.rightViewer, pair.rightNode);
        pair.leftViewer.visibleBits.clear(pair.rightViewer.objectIndex);
        pair.rightViewer.visibleBits.clear(pair.leftViewer.objectIndex);
    }

    private void append(Viewer owner, LinkNode node) {
        if (owner.tail == null) {
            owner.head = node;
            owner.tail = node;
        } else {
            owner.tail.next = node;
            node.prev = owner.tail;
            owner.tail = node;
        }
        owner.size++;
    }

    private void removeNode(Viewer owner, LinkNode node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            owner.head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            owner.tail = node.prev;
        }
        node.prev = null;
        node.next = null;
        owner.size--;
    }

    private LinkNode findNode(Viewer owner, Viewer target) {
        for (LinkNode cursor = owner.head; cursor != null; cursor = cursor.next) {
            if (cursor.target == target) {
                return cursor;
            }
        }
        return null;
    }

    private Viewer requireViewer(EntityId id) {
        Viewer viewer = viewers.get(Objects.requireNonNull(id, "id"));
        if (viewer == null) {
            throw new IllegalArgumentException("Unknown view object: " + id.value());
        }
        return viewer;
    }

    private void validateObjectIndex(int objectIndex) {
        if (objectIndex < 0 || objectIndex >= maxObjectIndex) {
            throw new IllegalArgumentException("objectIndex out of range: " + objectIndex);
        }
    }

    private static final class Viewer {
        private final EntityId id;
        private final int objectIndex;
        private final BitSet visibleBits;
        private LinkNode head;
        private LinkNode tail;
        private int size;

        private Viewer(EntityId id, int objectIndex, int maxObjectIndex) {
            this.id = id;
            this.objectIndex = objectIndex;
            this.visibleBits = new BitSet(maxObjectIndex);
        }
    }

    private static final class LinkPair {
        private final Viewer leftViewer;
        private final Viewer rightViewer;
        private final LinkNode leftNode;
        private final LinkNode rightNode;

        private LinkPair(Viewer leftViewer, Viewer rightViewer) {
            this.leftViewer = leftViewer;
            this.rightViewer = rightViewer;
            this.leftNode = new LinkNode(this, rightViewer);
            this.rightNode = new LinkNode(this, leftViewer);
        }
    }

    private static final class LinkNode {
        private final LinkPair parent;
        private final Viewer target;
        private LinkNode prev;
        private LinkNode next;

        private LinkNode(LinkPair parent, Viewer target) {
            this.parent = parent;
            this.target = target;
        }
    }
}
