package com.commonbattle.example.cross.scene;

import com.aoi.AoiPoint;
import com.aoi.NineGridAoiIndex;
import com.aoi.viewlink.ViewLinkChangeSet;
import com.aoi.viewlink.ViewLinkManager;
import com.commonbattle.battle.state.EntityId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单 shard 内的 AOI 运行时。
 * 该对象应只在对应 shard Actor mailbox 内调用，用九宫格做候选检索，再用视野关系管理器产生增量。
 */
public final class SceneShardAoiRuntime {
    private final NineGridAoiIndex index;
    private final ViewLinkManager links;
    private final int viewRange;
    private final int viewCapacity;
    private final int maxObjects;
    private final Map<Long, EntityId> idsByPlayer = new HashMap<>();
    private final Map<EntityId, Long> playersById = new HashMap<>();
    private int nextObjectIndex;

    public SceneShardAoiRuntime(int cellSize, int viewRange, int viewCapacity, int maxObjects) {
        if (viewRange < 0) {
            throw new IllegalArgumentException("viewRange must not be negative");
        }
        if (maxObjects <= 0) {
            throw new IllegalArgumentException("maxObjects must be positive");
        }
        this.index = NineGridAoiIndex.twoDimensional(cellSize);
        this.links = new ViewLinkManager(maxObjects);
        this.viewRange = viewRange;
        this.viewCapacity = viewCapacity;
        this.maxObjects = maxObjects;
    }

    public SceneAoiChangeSet enter(long playerId, int chunkX, int chunkY) {
        EntityId id = idOf(playerId);
        if (idsByPlayer.containsKey(playerId)) {
            return move(playerId, chunkX, chunkY);
        }
        if (nextObjectIndex >= maxObjects) {
            throw new IllegalStateException("scene shard AOI capacity exceeded");
        }
        idsByPlayer.put(playerId, id);
        playersById.put(id, playerId);
        index.add(id, AoiPoint.of2d(chunkX, chunkY));
        links.add(id, nextObjectIndex++);
        return refresh(playerId, id, chunkX, chunkY);
    }

    public SceneAoiChangeSet move(long playerId, int chunkX, int chunkY) {
        EntityId id = idsByPlayer.get(playerId);
        if (id == null) {
            return SceneAoiChangeSet.empty(playerId);
        }
        index.move(id, AoiPoint.of2d(chunkX, chunkY));
        return refresh(playerId, id, chunkX, chunkY);
    }

    public SceneAoiChangeSet leave(long playerId) {
        EntityId id = idsByPlayer.remove(playerId);
        if (id == null) {
            return SceneAoiChangeSet.empty(playerId);
        }
        List<Long> visible = toPlayerIds(links.visibleTargets(id));
        links.remove(id);
        index.remove(id);
        playersById.remove(id);
        return new SceneAoiChangeSet(playerId, List.of(), visible, List.of(), visible);
    }

    public List<Long> visiblePlayers(long playerId) {
        EntityId id = idsByPlayer.get(playerId);
        if (id == null) {
            return List.of();
        }
        return toPlayerIds(links.visibleTargets(id));
    }

    private SceneAoiChangeSet refresh(long playerId, EntityId id, int chunkX, int chunkY) {
        List<EntityId> candidates = index.queryAround2d(AoiPoint.of2d(chunkX, chunkY), viewRange);
        ViewLinkChangeSet changes = links.refresh(id, candidates, viewCapacity, ignored -> 0);
        List<Long> enter = toPlayerIds(changes.enter());
        List<Long> leave = toPlayerIds(changes.leave());
        return new SceneAoiChangeSet(playerId, enter, leave, enter, leave);
    }

    private List<Long> toPlayerIds(List<EntityId> ids) {
        return ids.stream()
                .map(playersById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private static EntityId idOf(long playerId) {
        return new EntityId("player:" + playerId);
    }
}
