package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorScheduleKey;
import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTimerHandle;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.scene.SceneRuntimeStats;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 超大场景承载策略。
 * 一个 Scene 服只承载一个大场景，并把地图地块稳定映射到多个 shard Actor，避免整个大地图被单 Actor 卡住。
 */
public final class LargeSceneShardService implements SceneServiceStrategy {
    private final ServiceDescriptor descriptor;
    private final ActorRef[] shards;
    private final String sceneId;
    private final Clock clock;
    private final Map<Long, ScenePlacement> placementsByPlayer = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Long>> playersByShard = new ConcurrentHashMap<>();
    private final AtomicLong scheduledTickJobs = new AtomicLong();
    private final AtomicLong completedTicks = new AtomicLong();
    private final AtomicLong failedTicks = new AtomicLong();

    public LargeSceneShardService(
            ActorSystem actors,
            ServiceId serviceId,
            ServiceEndpoint endpoint,
            String sceneId,
            int shardCount
    ) {
        this(actors, serviceId, endpoint, sceneId, shardCount, Clock.systemUTC());
    }

    public LargeSceneShardService(
            ActorSystem actors,
            ServiceId serviceId,
            ServiceEndpoint endpoint,
            String sceneId,
            int shardCount,
            Clock clock
    ) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        this.sceneId = Objects.requireNonNull(sceneId, "sceneId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.shards = new ActorRef[shardCount];
        for (int i = 0; i < shardCount; i++) {
            shards[i] = actors.actor(actorId(serviceId.node(), sceneId, i));
        }
        this.descriptor = ServiceMetadata.withProtocolVersion(ServiceMetadata.withLoad(new ServiceDescriptor(
                serviceId,
                endpoint,
                Set.of(SceneOperations.ENTER, SceneOperations.LEAVE, SceneOperations.MESSAGE),
                Map.of(
                        "scene.mode", SceneHostingMode.LARGE_SCENE_SHARD.name(),
                        "scene.id", sceneId,
                        "scene.shards", String.valueOf(shardCount)
                )
        ), 0, shardCount), 1);
    }

    public static LargeSceneShardService create(
            ActorSystem actors,
            String region,
            String node,
            ServiceEndpoint endpoint,
            String sceneId,
            int shardCount
    ) {
        return new LargeSceneShardService(
                actors,
                ServiceId.of(ServiceKind.SCENE, region, node),
                endpoint,
                sceneId,
                shardCount
        );
    }

    public static LargeSceneShardService create(
            ActorSystem actors,
            String region,
            String node,
            ServiceEndpoint endpoint,
            String sceneId,
            int shardCount,
            Clock clock
    ) {
        return new LargeSceneShardService(
                actors,
                ServiceId.of(ServiceKind.SCENE, region, node),
                endpoint,
                sceneId,
                shardCount,
                clock
        );
    }

    @Override
    public ServiceDescriptor descriptor() {
        return SceneRuntimeMetadata.apply(ServiceMetadata.withLoad(descriptor, stats().activePlayers(), shards.length), stats());
    }

    @Override
    public SceneRuntimeStats stats() {
        int activePlayers = placementsByPlayer.size();
        int maxShardPlayers = playersByShard.values().stream()
                .mapToInt(Set::size)
                .max()
                .orElse(0);
        return new SceneRuntimeStats(activePlayers == 0 ? 0 : 1, activePlayers, shards.length, maxShardPlayers);
    }

    @Override
    public ScenePlacement place(String requestedSceneId, int chunkX, int chunkY) {
        if (!sceneId.equals(requestedSceneId)) {
            throw new IllegalArgumentException("Scene service only hosts " + sceneId);
        }
        int shardIndex = Math.floorMod(chunkX * 31 + chunkY, shards.length);
        return new ScenePlacement(sceneId, shards[shardIndex], shardIndex, shards.length);
    }

    public Set<Long> shardPlayers(int shardIndex) {
        validateShardIndex(shardIndex);
        return Set.copyOf(playersByShard.getOrDefault(shardIndex, Set.of()));
    }

    public String sceneId() {
        return sceneId;
    }

    public int shardCount() {
        return shards.length;
    }

    public String actorId(int shardIndex) {
        validateShardIndex(shardIndex);
        return shards[shardIndex].id();
    }

    public LargeSceneShardAgentSnapshot exportShardForMigrationInCurrentMailbox(int shardIndex) {
        validateShardIndex(shardIndex);
        return new LargeSceneShardAgentSnapshot(sceneId, shardIndex, shards.length, shardPlayers(shardIndex));
    }

    public ScenePlacement restoreMigratedShard(LargeSceneShardAgentSnapshot snapshot, ActorRef actorRef) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(actorRef, "actorRef");
        validateSnapshot(snapshot);
        Set<Long> players = ConcurrentHashMap.newKeySet();
        players.addAll(snapshot.players());
        Set<Long> previousPlayers = players.isEmpty()
                ? playersByShard.remove(snapshot.shardIndex())
                : playersByShard.put(snapshot.shardIndex(), players);
        if (previousPlayers != null) {
            for (Long playerId : previousPlayers) {
                ScenePlacement placement = placementsByPlayer.get(playerId);
                if (placement != null && placement.shardIndex() == snapshot.shardIndex()) {
                    placementsByPlayer.remove(playerId, placement);
                }
            }
        }
        ScenePlacement placement = new ScenePlacement(sceneId, actorRef, snapshot.shardIndex(), shards.length);
        for (Long playerId : players) {
            ScenePlacement previous = placementsByPlayer.put(playerId, placement);
            if (previous != null && previous.shardIndex() != snapshot.shardIndex()) {
                removeFromShard(playerId, previous.shardIndex());
            }
        }
        return placement;
    }

    public LargeSceneShardAgentSnapshot removeMigratedShard(int shardIndex) {
        validateShardIndex(shardIndex);
        Set<Long> removed = playersByShard.remove(shardIndex);
        Set<Long> snapshotPlayers = removed == null ? Set.of() : Set.copyOf(removed);
        for (Long playerId : snapshotPlayers) {
            ScenePlacement placement = placementsByPlayer.get(playerId);
            if (placement != null && placement.shardIndex() == shardIndex) {
                placementsByPlayer.remove(playerId, placement);
            }
        }
        return new LargeSceneShardAgentSnapshot(sceneId, shardIndex, shards.length, snapshotPlayers);
    }

    public SceneShardTickStats tickStats() {
        return new SceneShardTickStats(
                shards.length,
                scheduledTickJobs.get(),
                completedTicks.get(),
                failedTicks.get()
        );
    }

    public java.util.List<ActorTimerHandle> scheduleShardTicks(
            ActorScheduleRegistry schedules,
            Duration initialDelay,
            Duration interval,
            SceneShardTickHandler handler
    ) {
        Objects.requireNonNull(schedules, "schedules");
        Objects.requireNonNull(handler, "handler");
        java.util.List<ActorTimerHandle> handles = new java.util.ArrayList<>(shards.length);
        for (int shardIndex = 0; shardIndex < shards.length; shardIndex++) {
            int currentShard = shardIndex;
            ActorScheduleKey key = new ActorScheduleKey(
                    "scene.shard.tick",
                    descriptor.id() + ":" + sceneId + ":shard-" + currentShard
            );
            handles.add(schedules.scheduleAtFixedRate(
                    key,
                    shards[currentShard],
                    initialDelay,
                    interval,
                    ignored -> tickShard(currentShard, handler)
            ));
            scheduledTickJobs.incrementAndGet();
        }
        return java.util.List.copyOf(handles);
    }

    @Override
    public ScenePlacement enter(long playerId, String requestedSceneId, int chunkX, int chunkY) {
        ScenePlacement next = place(requestedSceneId, chunkX, chunkY);
        ScenePlacement previous = placementsByPlayer.put(playerId, next);
        if (previous != null) {
            removeFromShard(playerId, previous.shardIndex());
        }
        playersByShard.computeIfAbsent(next.shardIndex(), ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
        return next;
    }

    @Override
    public boolean leave(long playerId, String requestedSceneId) {
        if (!sceneId.equals(requestedSceneId)) {
            return false;
        }
        ScenePlacement previous = placementsByPlayer.remove(playerId);
        if (previous == null) {
            return false;
        }
        Set<Long> players = playersByShard.get(previous.shardIndex());
        if (players != null) {
            players.remove(playerId);
            if (players.isEmpty()) {
                playersByShard.remove(previous.shardIndex(), players);
            }
        }
        return true;
    }

    private void removeFromShard(long playerId, int shardIndex) {
        Set<Long> players = playersByShard.get(shardIndex);
        if (players != null) {
            players.remove(playerId);
            if (players.isEmpty()) {
                playersByShard.remove(shardIndex, players);
            }
        }
    }

    private void tickShard(int shardIndex, SceneShardTickHandler handler) {
        SceneShardTickContext context = new SceneShardTickContext(
                sceneId,
                shardIndex,
                shards.length,
                shardPlayers(shardIndex),
                clock.instant()
        );
        try {
            handler.onTick(context);
            completedTicks.incrementAndGet();
        } catch (RuntimeException e) {
            failedTicks.incrementAndGet();
            throw e;
        }
    }

    private void validateShardIndex(int shardIndex) {
        if (shardIndex < 0 || shardIndex >= shards.length) {
            throw new IllegalArgumentException("invalid shard index: " + shardIndex);
        }
    }

    private void validateSnapshot(LargeSceneShardAgentSnapshot snapshot) {
        if (!sceneId.equals(snapshot.sceneId())) {
            throw new IllegalArgumentException("snapshot scene mismatch: " + snapshot.sceneId());
        }
        if (snapshot.shardCount() != shards.length) {
            throw new IllegalArgumentException("snapshot shard count mismatch: " + snapshot.shardCount());
        }
        validateShardIndex(snapshot.shardIndex());
    }

    private static String actorId(String node, String sceneId, int shardIndex) {
        return "scene:" + node + ":" + sceneId + ":shard-" + shardIndex;
    }
}
