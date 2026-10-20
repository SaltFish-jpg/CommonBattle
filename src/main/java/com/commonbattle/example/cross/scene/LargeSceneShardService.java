package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorScheduleKey;
import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTimerHandle;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.scene.SceneRuntimeStats;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 超大场景承载策略。
 * 一个 Scene 服只承载一个大场景，并把地图地块稳定映射到多个 shard Actor，避免整个大地图被单 Actor 卡住。
 */
public final class LargeSceneShardService implements SceneServiceStrategy {
    private final ActorSystem actors;
    private final ServiceDescriptor descriptor;
    private final ActorRef[] shards;
    private final LargeSceneShardPlanner planner;
    private final String sceneId;
    private final Clock clock;
    private final Map<Long, ScenePlacement> placementsByPlayer = new ConcurrentHashMap<>();
    private final Map<Long, SceneChunk> chunksByPlayer = new ConcurrentHashMap<>();
    private final Map<Long, PendingShardTransfer> pendingTransfersByPlayer = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Long>> playersByShard = new ConcurrentHashMap<>();
    private final Map<Integer, SceneShardAoiRuntime> aoiByShard = new ConcurrentHashMap<>();
    private final Map<Long, Integer> hitPointsByPlayer = new ConcurrentHashMap<>();
    private final SceneBroadcastSink broadcastSink;
    private final AtomicLong scheduledTickJobs = new AtomicLong();
    private final AtomicLong completedTicks = new AtomicLong();
    private final AtomicLong failedTicks = new AtomicLong();
    private final AtomicLong failedShardTransfers = new AtomicLong();
    private volatile AgentLifecycleManager lifecycles;

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
        this(actors, serviceId, endpoint, new LargeSceneShardPlanner(sceneId, shardCount), clock);
    }

    public LargeSceneShardService(
            ActorSystem actors,
            ServiceId serviceId,
            ServiceEndpoint endpoint,
            String sceneId,
            int shardCount,
            int chunkSize,
            Clock clock
    ) {
        this(actors, serviceId, endpoint, new LargeSceneShardPlanner(sceneId, shardCount, chunkSize), clock);
    }

    public LargeSceneShardService(
            ActorSystem actors,
            ServiceId serviceId,
            ServiceEndpoint endpoint,
            LargeSceneShardPlanner planner,
            Clock clock
    ) {
        this(actors, serviceId, endpoint, planner, clock, SceneBroadcastSink.NOOP);
    }

    public LargeSceneShardService(
            ActorSystem actors,
            ServiceId serviceId,
            ServiceEndpoint endpoint,
            LargeSceneShardPlanner planner,
            Clock clock,
            SceneBroadcastSink broadcastSink
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.sceneId = planner.sceneId();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.broadcastSink = Objects.requireNonNull(broadcastSink, "broadcastSink");
        this.shards = new ActorRef[planner.shardCount()];
        for (int i = 0; i < planner.shardCount(); i++) {
            shards[i] = actors.actor(actorId(serviceId.node(), planner.sceneId(), i));
        }
        this.descriptor = ServiceMetadata.withProtocolVersion(ServiceMetadata.withLoad(new ServiceDescriptor(
                serviceId,
                endpoint,
                Set.of(SceneOperations.ENTER, SceneOperations.LEAVE, SceneOperations.MOVE,
                        SceneOperations.COMBAT_DAMAGE, SceneOperations.MESSAGE),
                Map.of(
                        "scene.mode", SceneHostingMode.LARGE_SCENE_SHARD.name(),
                        "scene.id", planner.sceneId(),
                        "scene.shards", String.valueOf(planner.shardCount()),
                        "scene.shard.chunk.size", String.valueOf(planner.chunkSize())
                )
        ), 0, planner.shardCount()), 1);
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

    public static LargeSceneShardService create(
            ActorSystem actors,
            String region,
            String node,
            ServiceEndpoint endpoint,
            String sceneId,
            int shardCount,
            int chunkSize,
            Clock clock,
            SceneBroadcastSink broadcastSink
    ) {
        return new LargeSceneShardService(
                actors,
                ServiceId.of(ServiceKind.SCENE, region, node),
                endpoint,
                new LargeSceneShardPlanner(sceneId, shardCount, chunkSize),
                clock,
                broadcastSink
        );
    }

    public static LargeSceneShardService create(
            ActorSystem actors,
            String region,
            String node,
            ServiceEndpoint endpoint,
            String sceneId,
            int shardCount,
            int chunkSize,
            Clock clock
    ) {
        return new LargeSceneShardService(
                actors,
                ServiceId.of(ServiceKind.SCENE, region, node),
                endpoint,
                sceneId,
                shardCount,
                chunkSize,
                clock
        );
    }

    @Override
    public ServiceDescriptor descriptor() {
        return SceneRuntimeMetadata.apply(ServiceMetadata.withLoad(descriptor, stats().activePlayers(), shards.length), stats());
    }

    @Override
    public synchronized void attachLifecycles(AgentLifecycleManager lifecycles) {
        Objects.requireNonNull(lifecycles, "lifecycles");
        if (this.lifecycles != null) {
            if (this.lifecycles != lifecycles) {
                throw new IllegalStateException("scene lifecycles already attached");
            }
            return;
        }
        for (int i = 0; i < shards.length; i++) {
            lifecycles.activate(AgentIdentity.sceneShard(sceneId, i), shards[i].id());
        }
        this.lifecycles = lifecycles;
    }

    @Override
    public SceneRuntimeStats stats() {
        int activePlayers = placementsByPlayer.size();
        int maxShardPlayers = playersByShard.values().stream()
                .mapToInt(Set::size)
                .max()
                .orElse(0);
        return new SceneRuntimeStats(
                activePlayers == 0 ? 0 : 1,
                activePlayers,
                shards.length,
                maxShardPlayers,
                pendingTransfersByPlayer.size(),
                failedShardTransfers.get(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    @Override
    public ScenePlacement place(String requestedSceneId, int chunkX, int chunkY) {
        LargeSceneShardRoute route = planner.route(requestedSceneId, chunkX, chunkY);
        return new ScenePlacement(sceneId, shards[route.shardIndex()], route.shardIndex(), route.shardCount());
    }

    @Override
    public AgentIdentity routingIdentity(String requestedSceneId) {
        planner.route(requestedSceneId, 0, 0);
        return AgentIdentity.sceneShard(sceneId, 0);
    }

    @Override
    public Optional<AgentIdentity> identityOfActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return Optional.empty();
        }
        for (int i = 0; i < shards.length; i++) {
            if (shards[i].id().equals(actorId)) {
                return Optional.of(AgentIdentity.sceneShard(sceneId, i));
            }
        }
        return Optional.empty();
    }

    @Override
    public String migrationTargetActorId(AgentIdentity identity, ServiceId targetServiceId) {
        LargeSceneShardAgentKey key = LargeSceneShardAgentKey.fromIdentity(identity);
        return actorId(targetServiceId.node(), key.sceneId(), key.shardIndex());
    }

    public LargeSceneShardRoute route(String requestedSceneId, int chunkX, int chunkY) {
        return planner.route(requestedSceneId, chunkX, chunkY);
    }

    public LargeSceneShardPlanner planner() {
        return planner;
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

    public java.util.List<Long> visiblePlayers(long playerId) {
        ScenePlacement placement = placementsByPlayer.get(playerId);
        if (placement == null) {
            return java.util.List.of();
        }
        java.util.Set<Long> visible = new java.util.TreeSet<>(aoi(placement.shardIndex()).visiblePlayers(playerId));
        visible.addAll(crossShardVisiblePlayers(playerId, placement));
        return java.util.List.copyOf(visible);
    }

    public void registerCombatant(long playerId, int hitPoints) {
        if (hitPoints <= 0) {
            throw new IllegalArgumentException("hitPoints must be positive");
        }
        if (!placementsByPlayer.containsKey(playerId)) {
            throw new IllegalArgumentException("player must enter scene before combat registration");
        }
        hitPointsByPlayer.put(playerId, hitPoints);
    }

    public java.util.OptionalInt hitPoints(long playerId) {
        Integer hitPoints = hitPointsByPlayer.get(playerId);
        return hitPoints == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(hitPoints);
    }

    public LargeSceneShardAgentSnapshot exportShardForMigrationInCurrentMailbox(int shardIndex) {
        validateShardIndex(shardIndex);
        ActorContext.requireCurrent(shards[shardIndex]);
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

    public int repairExpiredShardTransfers(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        Instant expiredBefore = clock.instant().minus(timeout);
        int scheduledRepairs = 0;
        for (PendingShardTransfer transfer : pendingTransfersByPlayer.values()) {
            if (transfer.startedAt().isAfter(expiredBefore)) {
                continue;
            }
            if (actors.trySend(transfer.previous.actor(), ignored -> rollbackExpiredShardTransfer(transfer))) {
                scheduledRepairs++;
            } else {
                failedShardTransfers.incrementAndGet();
            }
        }
        return scheduledRepairs;
    }

    public ActorTimerHandle scheduleShardTransferRepairs(
            ActorScheduleRegistry schedules,
            Duration initialDelay,
            Duration interval,
            Duration timeout
    ) {
        Objects.requireNonNull(schedules, "schedules");
        Objects.requireNonNull(timeout, "timeout");
        ActorScheduleKey key = new ActorScheduleKey(
                "scene.shard.transfer.repair",
                descriptor.id() + ":" + sceneId
        );
        return schedules.scheduleAtFixedRate(
                key,
                shards[0],
                initialDelay,
                interval,
                ignored -> repairExpiredShardTransfers(timeout)
        );
    }

    @Override
    public ScenePlacement enter(long playerId, String requestedSceneId, int chunkX, int chunkY) {
        ScenePlacement next = place(requestedSceneId, chunkX, chunkY);
        ScenePlacement previous = placementsByPlayer.put(playerId, next);
        if (previous != null) {
            removeFromShard(playerId, previous.shardIndex());
            aoi(previous.shardIndex()).leave(playerId);
        }
        playersByShard.computeIfAbsent(next.shardIndex(), ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
        chunksByPlayer.put(playerId, new SceneChunk(chunkX, chunkY));
        SceneAoiChangeSet aoiChange = aoi(next.shardIndex()).enter(playerId, chunkX, chunkY);
        publishEnter(playerId, next, chunkX, chunkY, aoiChange);
        return next;
    }

    @Override
    public void enterAsync(
            long playerId,
            String requestedSceneId,
            int chunkX,
            int chunkY,
            Consumer<ScenePlacement> callback,
            Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(failure, "failure");
        ScenePlacement placement;
        try {
            placement = place(requestedSceneId, chunkX, chunkY);
        } catch (RuntimeException e) {
            failure.accept(e);
            return;
        }
        actorsSend(placement.actor(), () -> {
            ActorContext.requireCurrent(placement.actor());
            callback.accept(enter(playerId, requestedSceneId, chunkX, chunkY));
        }, failure);
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
        hitPointsByPlayer.remove(playerId);
        chunksByPlayer.remove(playerId);
        pendingTransfersByPlayer.remove(playerId);
        Set<Long> players = playersByShard.get(previous.shardIndex());
        if (players != null) {
            players.remove(playerId);
            if (players.isEmpty()) {
                playersByShard.remove(previous.shardIndex(), players);
            }
        }
        SceneAoiChangeSet aoiChange = aoi(previous.shardIndex()).leave(playerId);
        publishLeave(playerId, previous, aoiChange);
        return true;
    }

    @Override
    public void leaveAsync(
            long playerId,
            String requestedSceneId,
            Consumer<Boolean> callback,
            Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(failure, "failure");
        if (!sceneId.equals(requestedSceneId)) {
            callback.accept(false);
            return;
        }
        ScenePlacement placement = placementsByPlayer.get(playerId);
        if (placement == null) {
            callback.accept(false);
            return;
        }
        actorsSend(placement.actor(), () -> {
            ActorContext.requireCurrent(placement.actor());
            callback.accept(leave(playerId, requestedSceneId));
        }, failure);
    }

    @Override
    public SceneMoveOutcome move(long playerId, String requestedSceneId, int chunkX, int chunkY) {
        if (!sceneId.equals(requestedSceneId)) {
            return SceneMoveOutcome.rejected(requestedSceneId, chunkX, chunkY);
        }
        ScenePlacement previous = placementsByPlayer.get(playerId);
        if (previous == null) {
            return SceneMoveOutcome.rejected(requestedSceneId, chunkX, chunkY);
        }
        ScenePlacement next = place(requestedSceneId, chunkX, chunkY);
        placementsByPlayer.put(playerId, next);
        SceneAoiChangeSet aoiChange;
        if (previous.shardIndex() != next.shardIndex()) {
            removeFromShard(playerId, previous.shardIndex());
            SceneAoiChangeSet leave = aoi(previous.shardIndex()).leave(playerId);
            playersByShard.computeIfAbsent(next.shardIndex(), ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
            SceneAoiChangeSet enter = aoi(next.shardIndex()).enter(playerId, chunkX, chunkY);
            aoiChange = leave.plus(enter);
        } else {
            aoiChange = aoi(next.shardIndex()).move(playerId, chunkX, chunkY);
        }
        chunksByPlayer.put(playerId, new SceneChunk(chunkX, chunkY));
        publishMove(playerId, next, chunkX, chunkY, aoiChange);
        return new SceneMoveOutcome(
                sceneId,
                chunkX,
                chunkY,
                previous.shardIndex(),
                next.shardIndex(),
                true,
                aoiChange
        );
    }

    @Override
    public void moveAsync(
            long playerId,
            String requestedSceneId,
            int chunkX,
            int chunkY,
            Consumer<SceneMoveOutcome> callback,
            Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(failure, "failure");
        if (!sceneId.equals(requestedSceneId)) {
            callback.accept(SceneMoveOutcome.rejected(requestedSceneId, chunkX, chunkY));
            return;
        }
        ScenePlacement placement = placementsByPlayer.get(playerId);
        if (placement == null) {
            callback.accept(SceneMoveOutcome.rejected(requestedSceneId, chunkX, chunkY));
            return;
        }
        ScenePlacement next;
        try {
            next = place(requestedSceneId, chunkX, chunkY);
        } catch (RuntimeException e) {
            failure.accept(e);
            return;
        }
        if (placement.shardIndex() == next.shardIndex()) {
            actorsSend(placement.actor(), () -> {
                ActorContext.requireCurrent(placement.actor());
                callback.accept(move(playerId, requestedSceneId, chunkX, chunkY));
            }, failure);
            return;
        }
        actorsSend(placement.actor(), () -> moveAcrossShardFromSource(
                playerId,
                requestedSceneId,
                chunkX,
                chunkY,
                placement,
                next,
                callback,
                failure
        ), failure);
    }

    public SceneCombatDamageOutcome applyDamage(long attackerId, long targetId, int damage) {
        if (damage <= 0) {
            throw new IllegalArgumentException("damage must be positive");
        }
        ScenePlacement attacker = placementsByPlayer.get(attackerId);
        ScenePlacement target = placementsByPlayer.get(targetId);
        if (attacker == null || target == null || attacker.shardIndex() != target.shardIndex()) {
            return SceneCombatDamageOutcome.rejected(sceneId, attackerId, targetId);
        }
        Integer hpBefore = hitPointsByPlayer.get(targetId);
        if (hpBefore == null || hpBefore <= 0) {
            return SceneCombatDamageOutcome.rejected(sceneId, attackerId, targetId);
        }
        int hpAfter = Math.max(0, hpBefore - damage);
        hitPointsByPlayer.put(targetId, hpAfter);
        SceneCombatDamageOutcome outcome = new SceneCombatDamageOutcome(
                sceneId,
                attackerId,
                targetId,
                target.shardIndex(),
                damage,
                hpBefore,
                hpAfter,
                true
        );
        publishCombatDamage(outcome);
        return outcome;
    }

    public void applyDamageAsync(
            long attackerId,
            long targetId,
            int damage,
            Consumer<SceneCombatDamageOutcome> callback,
            Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(failure, "failure");
        if (damage <= 0) {
            failure.accept(new IllegalArgumentException("damage must be positive"));
            return;
        }
        ScenePlacement placement = placementsByPlayer.get(attackerId);
        if (placement == null) {
            callback.accept(SceneCombatDamageOutcome.rejected(sceneId, attackerId, targetId));
            return;
        }
        ScenePlacement targetPlacement = placementsByPlayer.get(targetId);
        if (targetPlacement == null) {
            callback.accept(SceneCombatDamageOutcome.rejected(sceneId, attackerId, targetId));
            return;
        }
        if (placement.shardIndex() == targetPlacement.shardIndex()) {
            actorsSend(placement.actor(), () -> {
                ActorContext.requireCurrent(placement.actor());
                callback.accept(applyDamage(attackerId, targetId, damage));
            }, failure);
            return;
        }
        actorsSend(targetPlacement.actor(), () -> {
            ActorContext.requireCurrent(targetPlacement.actor());
            callback.accept(applyCrossShardDamageOnTarget(attackerId, targetId, damage, targetPlacement));
        }, failure);
    }

    private SceneCombatDamageOutcome applyCrossShardDamageOnTarget(
            long attackerId,
            long targetId,
            int damage,
            ScenePlacement expectedTargetPlacement
    ) {
        ScenePlacement attacker = placementsByPlayer.get(attackerId);
        ScenePlacement target = placementsByPlayer.get(targetId);
        if (attacker == null || target == null || target.shardIndex() != expectedTargetPlacement.shardIndex()
                || !visiblePlayers(attackerId).contains(targetId)) {
            return SceneCombatDamageOutcome.rejected(sceneId, attackerId, targetId);
        }
        Integer hpBefore = hitPointsByPlayer.get(targetId);
        if (hpBefore == null || hpBefore <= 0) {
            return SceneCombatDamageOutcome.rejected(sceneId, attackerId, targetId);
        }
        int hpAfter = Math.max(0, hpBefore - damage);
        hitPointsByPlayer.put(targetId, hpAfter);
        SceneCombatDamageOutcome outcome = new SceneCombatDamageOutcome(
                sceneId,
                attackerId,
                targetId,
                target.shardIndex(),
                damage,
                hpBefore,
                hpAfter,
                true
        );
        publishCombatDamage(outcome);
        return outcome;
    }

    private void publishCombatDamage(SceneCombatDamageOutcome outcome) {
        java.util.Set<Long> recipients = new java.util.LinkedHashSet<>(visiblePlayers(outcome.targetId()));
        recipients.add(outcome.attackerId());
        recipients.add(outcome.targetId());
        publish(recipients, new SceneBroadcastEvent(
                SceneBroadcastEventType.COMBAT_DAMAGE,
                sceneId,
                outcome.targetId(),
                outcome.attackerId(),
                outcome.shardIndex(),
                0,
                0,
                outcome.damage(),
                outcome.hpAfter()
        ));
    }

    private void publishEnter(long playerId, ScenePlacement placement, int chunkX, int chunkY, SceneAoiChangeSet aoi) {
        publish(merge(aoi.observerEnter(), crossShardVisiblePlayers(playerId, placement)), new SceneBroadcastEvent(
                SceneBroadcastEventType.ENTER_VIEW,
                sceneId,
                playerId,
                placement.shardIndex(),
                chunkX,
                chunkY
        ));
    }

    private void publishLeave(long playerId, ScenePlacement placement, SceneAoiChangeSet aoi) {
        publish(merge(aoi.observerLeave(), crossShardVisiblePlayers(playerId, placement)), new SceneBroadcastEvent(
                SceneBroadcastEventType.LEAVE_VIEW,
                sceneId,
                playerId,
                placement.shardIndex(),
                0,
                0
        ));
    }

    private void publishMove(long playerId, ScenePlacement placement, int chunkX, int chunkY, SceneAoiChangeSet aoi) {
        publish(aoi.observerLeave(), new SceneBroadcastEvent(
                SceneBroadcastEventType.LEAVE_VIEW,
                sceneId,
                playerId,
                placement.shardIndex(),
                chunkX,
                chunkY
        ));
        publish(aoi.observerEnter(), new SceneBroadcastEvent(
                SceneBroadcastEventType.ENTER_VIEW,
                sceneId,
                playerId,
                placement.shardIndex(),
                chunkX,
                chunkY
        ));
        publish(visiblePlayers(playerId), new SceneBroadcastEvent(
                SceneBroadcastEventType.PLAYER_MOVED,
                sceneId,
                playerId,
                placement.shardIndex(),
                chunkX,
                chunkY
        ));
    }

    private void publish(java.util.Collection<Long> recipients, SceneBroadcastEvent event) {
        if (recipients.isEmpty()) {
            return;
        }
        broadcastSink.publish(new SceneBroadcastEnvelope(Set.copyOf(recipients), event));
    }

    private java.util.List<Long> crossShardVisiblePlayers(long playerId, ScenePlacement placement) {
        SceneChunk chunk = chunksByPlayer.get(playerId);
        if (chunk == null) {
            return java.util.List.of();
        }
        int viewRange = Math.max(1, planner.chunkSize());
        return chunksByPlayer.entrySet().stream()
                .filter(entry -> entry.getKey() != playerId)
                .filter(entry -> {
                    ScenePlacement otherPlacement = placementsByPlayer.get(entry.getKey());
                    return otherPlacement != null && otherPlacement.shardIndex() != placement.shardIndex();
                })
                .filter(entry -> chunk.visible(entry.getValue(), viewRange))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static java.util.List<Long> merge(java.util.Collection<Long> first, java.util.Collection<Long> second) {
        if (first.isEmpty()) {
            return second.stream().sorted().toList();
        }
        if (second.isEmpty()) {
            return first.stream().sorted().toList();
        }
        java.util.Set<Long> merged = new java.util.TreeSet<>(first);
        merged.addAll(second);
        return java.util.List.copyOf(merged);
    }

    private void moveAcrossShardFromSource(
            long playerId,
            String requestedSceneId,
            int chunkX,
            int chunkY,
            ScenePlacement previous,
            ScenePlacement next,
            Consumer<SceneMoveOutcome> callback,
            Consumer<Throwable> failure
    ) {
        ActorContext.requireCurrent(previous.actor());
        ScenePlacement current = placementsByPlayer.get(playerId);
        if (current == null || !sceneId.equals(requestedSceneId) || current.shardIndex() != previous.shardIndex()) {
            callback.accept(SceneMoveOutcome.rejected(requestedSceneId, chunkX, chunkY));
            return;
        }
        SceneChunk previousChunk = chunksByPlayer.get(playerId);
        if (previousChunk == null) {
            callback.accept(SceneMoveOutcome.rejected(requestedSceneId, chunkX, chunkY));
            return;
        }
        PendingShardTransfer transfer = new PendingShardTransfer(playerId, previous, previousChunk, next,
                new SceneChunk(chunkX, chunkY), clock.instant());
        if (pendingTransfersByPlayer.putIfAbsent(playerId, transfer) != null) {
            callback.accept(SceneMoveOutcome.rejected(requestedSceneId, chunkX, chunkY));
            return;
        }
        removeFromShard(playerId, previous.shardIndex());
        SceneAoiChangeSet leave = aoi(previous.shardIndex()).leave(playerId);
        // 跨 shard 转移分两段投递：旧 shard 只移除本地状态，新 shard 在自己的 mailbox 内接管玩家。
        try {
            actors.send(next.actor(), ignored -> commitMoveAcrossShardOnTarget(
                    transfer,
                    requestedSceneId,
                    leave,
                    callback
            ));
        } catch (RuntimeException e) {
            rollbackMoveAcrossShardOnSource(transfer);
            failedShardTransfers.incrementAndGet();
            failure.accept(e);
        }
    }

    private void commitMoveAcrossShardOnTarget(
            PendingShardTransfer transfer,
            String requestedSceneId,
            SceneAoiChangeSet leave,
            Consumer<SceneMoveOutcome> callback
    ) {
        ActorContext.requireCurrent(transfer.next.actor());
        PendingShardTransfer currentTransfer = pendingTransfersByPlayer.remove(transfer.playerId);
        if (!transfer.equals(currentTransfer)) {
            callback.accept(SceneMoveOutcome.rejected(requestedSceneId, transfer.nextChunk.x, transfer.nextChunk.y));
            return;
        }
        placementsByPlayer.put(transfer.playerId, transfer.next);
        chunksByPlayer.put(transfer.playerId, transfer.nextChunk);
        playersByShard.computeIfAbsent(transfer.next.shardIndex(), ignored -> ConcurrentHashMap.newKeySet())
                .add(transfer.playerId);
        SceneAoiChangeSet enter = aoi(transfer.next.shardIndex())
                .enter(transfer.playerId, transfer.nextChunk.x, transfer.nextChunk.y);
        SceneAoiChangeSet aoiChange = leave.plus(enter);
        publishMove(transfer.playerId, transfer.next, transfer.nextChunk.x, transfer.nextChunk.y, aoiChange);
        callback.accept(new SceneMoveOutcome(
                sceneId,
                transfer.nextChunk.x,
                transfer.nextChunk.y,
                transfer.previous.shardIndex(),
                transfer.next.shardIndex(),
                true,
                aoiChange
        ));
    }

    private void rollbackMoveAcrossShardOnSource(PendingShardTransfer transfer) {
        pendingTransfersByPlayer.remove(transfer.playerId, transfer);
        placementsByPlayer.put(transfer.playerId, transfer.previous);
        chunksByPlayer.put(transfer.playerId, transfer.previousChunk);
        playersByShard.computeIfAbsent(transfer.previous.shardIndex(), ignored -> ConcurrentHashMap.newKeySet())
                .add(transfer.playerId);
        aoi(transfer.previous.shardIndex()).enter(
                transfer.playerId,
                transfer.previousChunk.x,
                transfer.previousChunk.y
        );
    }

    private void rollbackExpiredShardTransfer(PendingShardTransfer transfer) {
        ActorContext.requireCurrent(transfer.previous.actor());
        if (!pendingTransfersByPlayer.remove(transfer.playerId, transfer)) {
            return;
        }
        failedShardTransfers.incrementAndGet();
        rollbackMoveAcrossShardOnSource(transfer);
    }

    private SceneShardAoiRuntime aoi(int shardIndex) {
        validateShardIndex(shardIndex);
        return aoiByShard.computeIfAbsent(shardIndex, ignored -> new SceneShardAoiRuntime(
                Math.max(1, planner.chunkSize()),
                Math.max(1, planner.chunkSize()),
                -1,
                100_000
        ));
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

    private void actorsSend(ActorRef actor, Runnable task, Consumer<Throwable> failure) {
        try {
            // Scene RPC 入站边界：调用线程只投递，具体状态变更在目标 shard actor mailbox 内执行。
            actors.send(actor, ignored -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    failure.accept(e);
                }
            });
        } catch (RuntimeException e) {
            failure.accept(e);
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

    private record SceneChunk(int x, int y) {
        private boolean visible(SceneChunk other, int range) {
            return Math.abs(x - other.x) <= range && Math.abs(y - other.y) <= range;
        }
    }

    private record PendingShardTransfer(
            long playerId,
            ScenePlacement previous,
            SceneChunk previousChunk,
            ScenePlacement next,
            SceneChunk nextChunk,
            Instant startedAt
    ) {
    }
}
