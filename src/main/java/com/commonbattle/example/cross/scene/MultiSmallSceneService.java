package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.migration.AgentMigrationOperations;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.scene.SceneRuntimeStats;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 多小型场景承载策略。
 * 一个 Scene 服容纳多个小场景，每个 sceneId 对应一个独立 Actor，适合副本、房间和小地图。
 */
public final class MultiSmallSceneService implements SceneServiceStrategy {
    private final ActorSystem actors;
    private final ServiceDescriptor baseDescriptor;
    private final int capacity;
    private final Map<String, ActorRef> scenes = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> playersByScene = new ConcurrentHashMap<>();
    private final Object sceneCreationLock = new Object();
    private volatile AgentLifecycleManager lifecycles;

    public MultiSmallSceneService(ActorSystem actors, ServiceId serviceId, ServiceEndpoint endpoint, int capacity) {
        this.actors = actors;
        this.capacity = capacity;
        this.baseDescriptor = ServiceMetadata.withProtocolVersion(ServiceMetadata.withLoad(new ServiceDescriptor(
                serviceId,
                endpoint,
                Set.of(SceneOperations.ENTER, SceneOperations.LEAVE, SceneOperations.MOVE,
                        SceneOperations.COMBAT_DAMAGE, SceneOperations.MESSAGE, AgentMigrationOperations.ACCEPT),
                Map.of(
                        "scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name(),
                        "scene.capacity", String.valueOf(capacity)
                )
        ), 0, capacity), 1);
    }

    public static MultiSmallSceneService create(
            ActorSystem actors,
            String region,
            String node,
            ServiceEndpoint endpoint,
            int capacity
    ) {
        return new MultiSmallSceneService(
                actors,
                ServiceId.of(ServiceKind.SCENE, region, node),
                endpoint,
                capacity
        );
    }

    @Override
    public ServiceDescriptor descriptor() {
        return SceneRuntimeMetadata.apply(ServiceMetadata.withLoad(baseDescriptor, scenes.size(), capacity), stats());
    }

    @Override
    public void attachLifecycles(AgentLifecycleManager lifecycles) {
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        lifecycles.addInvalidationListener(record -> {
            if (!AgentIdentity.SCENE.equals(record.identity().type())) {
                return;
            }
            String sceneId = record.identity().key();
            scenes.remove(sceneId, record.location().actorRef());
            playersByScene.remove(sceneId);
        });
    }

    @Override
    public SceneRuntimeStats stats() {
        int activePlayers = playersByScene.values().stream()
                .mapToInt(Set::size)
                .sum();
        return new SceneRuntimeStats(playersByScene.size(), activePlayers, 0, activePlayers);
    }

    @Override
    public ScenePlacement place(String sceneId, int chunkX, int chunkY) {
        ActorRef actor = scenes.get(sceneId);
        if (actor == null) {
            synchronized (sceneCreationLock) {
                actor = scenes.get(sceneId);
                if (actor == null) {
                    if (scenes.size() >= capacity) {
                        throw new SceneCapacityExceededException(baseDescriptor.id().node(), capacity);
                    }
                    AgentLifecycleManager lifecycle = lifecycles;
                    actor = lifecycle == null
                            ? actors.actor(actorId(sceneId))
                            : lifecycle.activate(AgentIdentity.scene(sceneId), actorId(sceneId)).actorRef();
                    scenes.put(sceneId, actor);
                }
            }
        }
        return new ScenePlacement(sceneId, actor, 0, 1);
    }

    public String actorId(String sceneId) {
        Objects.requireNonNull(sceneId, "sceneId");
        if (sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        return "scene:" + baseDescriptor.id().node() + ":" + sceneId;
    }

    @Override
    public Optional<AgentIdentity> identityOfActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return Optional.empty();
        }
        return scenes.entrySet().stream()
                .filter(entry -> entry.getValue().id().equals(actorId))
                .map(entry -> AgentIdentity.scene(entry.getKey()))
                .findFirst();
    }

    @Override
    public String migrationTargetActorId(AgentIdentity identity, ServiceId targetServiceId) {
        if (!AgentIdentity.SCENE.equals(identity.type())) {
            throw new IllegalArgumentException("unsupported small scene identity: " + identity.type());
        }
        return "scene:" + targetServiceId.node() + ":" + identity.key();
    }

    public Set<Long> scenePlayers(String sceneId) {
        return Set.copyOf(playersByScene.getOrDefault(sceneId, Set.of()));
    }

    public SmallSceneAgentSnapshot exportForMigrationInCurrentMailbox(String sceneId) {
        ActorRef actor = scenes.get(sceneId);
        if (actor == null) {
            throw new IllegalStateException("scene agent not loaded: " + sceneId);
        }
        ActorContext.requireCurrent(actor);
        return new SmallSceneAgentSnapshot(sceneId, scenePlayers(sceneId));
    }

    public ScenePlacement restoreMigrated(SmallSceneAgentSnapshot snapshot, ActorRef actorRef) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(actorRef, "actorRef");
        ActorRef previous = scenes.putIfAbsent(snapshot.sceneId(), actorRef);
        if (previous != null) {
            throw new IllegalStateException("scene agent already loaded: " + snapshot.sceneId());
        }
        Set<Long> players = ConcurrentHashMap.newKeySet();
        players.addAll(snapshot.players());
        if (players.isEmpty()) {
            playersByScene.remove(snapshot.sceneId());
        } else {
            playersByScene.put(snapshot.sceneId(), players);
        }
        return new ScenePlacement(snapshot.sceneId(), actorRef, 0, 1);
    }

    public SmallSceneAgentSnapshot removeMigrated(String sceneId) {
        ActorRef removed = scenes.remove(sceneId);
        Set<Long> players = playersByScene.remove(sceneId);
        if (removed == null) {
            throw new IllegalStateException("scene agent not loaded: " + sceneId);
        }
        actors.retire(removed);
        return new SmallSceneAgentSnapshot(sceneId, players == null ? Set.of() : Set.copyOf(players));
    }

    @Override
    public ScenePlacement enter(long playerId, String sceneId, int chunkX, int chunkY) {
        ScenePlacement placement = place(sceneId, chunkX, chunkY);
        playersByScene.computeIfAbsent(placement.sceneId(), ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
        return placement;
    }

    @Override
    public void enterAsync(
            long playerId,
            String sceneId,
            int chunkX,
            int chunkY,
            Consumer<ScenePlacement> callback,
            Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(failure, "failure");
        ScenePlacement placement;
        try {
            placement = place(sceneId, chunkX, chunkY);
        } catch (RuntimeException e) {
            failure.accept(e);
            return;
        }
        actorsSend(placement.actor(), () -> {
            ActorContext.requireCurrent(placement.actor());
            callback.accept(enter(playerId, sceneId, chunkX, chunkY));
        }, failure);
    }

    @Override
    public boolean leave(long playerId, String sceneId) {
        Set<Long> players = playersByScene.get(sceneId);
        if (players == null) {
            return false;
        }
        boolean removed = players.remove(playerId);
        if (players.isEmpty()) {
            playersByScene.remove(sceneId, players);
            ActorRef retired = scenes.remove(sceneId);
            if (retired != null) {
                actors.retire(retired);
            }
        }
        return removed;
    }

    @Override
    public void leaveAsync(
            long playerId,
            String sceneId,
            Consumer<Boolean> callback,
            Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(failure, "failure");
        ActorRef actor = scenes.get(sceneId);
        if (actor == null) {
            callback.accept(false);
            return;
        }
        actorsSend(actor, () -> {
            ActorContext.requireCurrent(actor);
            callback.accept(leave(playerId, sceneId));
        }, failure);
    }

    @Override
    public SceneMoveOutcome move(long playerId, String sceneId, int chunkX, int chunkY) {
        Set<Long> players = playersByScene.get(sceneId);
        if (players == null || !players.contains(playerId)) {
            return SceneMoveOutcome.rejected(sceneId, chunkX, chunkY);
        }
        return new SceneMoveOutcome(sceneId, chunkX, chunkY, 0, 0, true);
    }

    @Override
    public void moveAsync(
            long playerId,
            String sceneId,
            int chunkX,
            int chunkY,
            Consumer<SceneMoveOutcome> callback,
            Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(failure, "failure");
        ActorRef actor = scenes.get(sceneId);
        if (actor == null) {
            callback.accept(SceneMoveOutcome.rejected(sceneId, chunkX, chunkY));
            return;
        }
        actorsSend(actor, () -> {
            ActorContext.requireCurrent(actor);
            callback.accept(move(playerId, sceneId, chunkX, chunkY));
        }, failure);
    }

    private void actorsSend(ActorRef actor, Runnable task, Consumer<Throwable> failure) {
        try {
            // Scene RPC 入站边界：调用线程只投递，状态变更统一回到目标 scene actor mailbox。
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
}
