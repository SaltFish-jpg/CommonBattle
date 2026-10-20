package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTimerHandle;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.config.GameConfigView;
import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.session.PlayerCommandAuditSink;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerCommandSequencerSnapshot;
import com.commonbattle.game.shop.ShopStockAsyncClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Game 服玩家 Agent 管理器。
 * 登录、网关命令和本服业务入口都应通过它取得玩家 Agent，避免重复恢复、重复占用 owner 或漏注入事件发布器。
 */
public final class PlayerGameAgentManager implements AsyncShopPurchaseView, AsyncBusinessCommandView {
    private final ActorSystem actors;
    private final AgentMessagePort messages;
    private final PlayerStateRepository repository;
    private final PlayerAgentRecoveryService recoveryService;
    private final GameConfigView configView;
    private final AgentLifecycleManager lifecycles;
    private final Clock clock;
    private final Instant serverOpenTime;
    private final EventPublisher domainEventPublisher;
    private final PlayerDomainEventListener domainEventListener;
    private final PlayerStateSaveListener saveListener;
    private final ShopStockAsyncClient shopStockAsyncClient;
    private final PlayerPushPort pushes;
    private final PlayerCommandAuditSink asyncCommandAuditSink;
    private final PlayerCommandSequencer commandSequencer;
    private final AsyncBusinessCommandPolicy asyncCommandPolicy;
    private final ActorScheduleRegistry actorSchedules;
    private final boolean growthStaminaRecoveryEnabled;
    private final Duration growthStaminaInitialDelay;
    private final Duration growthStaminaInterval;
    private final AsyncShopPurchaseMetrics asyncShopPurchases = new AsyncShopPurchaseMetrics();
    private final AsyncBusinessCommandMetrics asyncBusinessCommands = new AsyncBusinessCommandMetrics();
    private final Map<Long, PlayerGameAgentHandle> agents = new ConcurrentHashMap<>();
    private final LongAdder fencingConflicts = new LongAdder();
    private volatile LongConsumer passivationListener = ignored -> {
    };
    private volatile java.util.function.LongFunction<PlayerBusinessResponseSnapshot> businessResponseSnapshots =
            PlayerBusinessResponseSnapshot::empty;

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime
    ) {
        this(actors, messages, repository, configView, lifecycles, clock, serverOpenTime, null);
    }

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher
    ) {
        this(actors, messages, repository, configView, lifecycles, clock, serverOpenTime,
                domainEventPublisher, null, PlayerStateSaveListener.ignore());
    }

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            PlayerStateSaveListener saveListener
    ) {
        this(actors, messages, repository, configView, lifecycles, clock, serverOpenTime,
                domainEventPublisher, null, saveListener);
    }

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            PlayerDomainEventListener domainEventListener,
            PlayerStateSaveListener saveListener
    ) {
        this(actors, messages, repository, configView, lifecycles, clock, serverOpenTime,
                domainEventPublisher, domainEventListener, saveListener, null);
    }

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            PlayerDomainEventListener domainEventListener,
            PlayerStateSaveListener saveListener,
            ShopStockAsyncClient shopStockAsyncClient
    ) {
        this(actors, messages, repository, configView, lifecycles, clock, serverOpenTime,
                domainEventPublisher, domainEventListener, saveListener, shopStockAsyncClient, PlayerPushPort.NOOP);
    }

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            PlayerDomainEventListener domainEventListener,
            PlayerStateSaveListener saveListener,
            ShopStockAsyncClient shopStockAsyncClient,
            PlayerPushPort pushes
    ) {
        this(actors, messages, repository, configView, lifecycles, clock, serverOpenTime,
                domainEventPublisher, domainEventListener, saveListener, shopStockAsyncClient, pushes,
                PlayerCommandAuditSink.NOOP);
    }

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            PlayerDomainEventListener domainEventListener,
            PlayerStateSaveListener saveListener,
            ShopStockAsyncClient shopStockAsyncClient,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink
    ) {
        this(actors, messages, repository, configView, lifecycles, clock, serverOpenTime,
                domainEventPublisher, domainEventListener, saveListener, shopStockAsyncClient, pushes,
                asyncCommandAuditSink, null, null, false, Duration.ZERO, Duration.ofMinutes(5));
    }

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            PlayerDomainEventListener domainEventListener,
            PlayerStateSaveListener saveListener,
            ShopStockAsyncClient shopStockAsyncClient,
            PlayerPushPort pushes,
            ActorScheduleRegistry actorSchedules,
            boolean growthStaminaRecoveryEnabled,
            Duration growthStaminaInitialDelay,
            Duration growthStaminaInterval
    ) {
        this(actors, messages, repository, configView, lifecycles, clock, serverOpenTime,
                domainEventPublisher, domainEventListener, saveListener, shopStockAsyncClient, pushes,
                PlayerCommandAuditSink.NOOP, null, actorSchedules, growthStaminaRecoveryEnabled,
                growthStaminaInitialDelay, growthStaminaInterval);
    }

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            PlayerDomainEventListener domainEventListener,
            PlayerStateSaveListener saveListener,
            ShopStockAsyncClient shopStockAsyncClient,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink,
            ActorScheduleRegistry actorSchedules,
            boolean growthStaminaRecoveryEnabled,
            Duration growthStaminaInitialDelay,
            Duration growthStaminaInterval
    ) {
        this(actors, messages, repository, configView, lifecycles, clock, serverOpenTime,
                domainEventPublisher, domainEventListener, saveListener, shopStockAsyncClient, pushes,
                asyncCommandAuditSink, null, actorSchedules, growthStaminaRecoveryEnabled,
                growthStaminaInitialDelay, growthStaminaInterval);
    }

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            PlayerDomainEventListener domainEventListener,
            PlayerStateSaveListener saveListener,
            ShopStockAsyncClient shopStockAsyncClient,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink,
            PlayerCommandSequencer commandSequencer,
            ActorScheduleRegistry actorSchedules,
            boolean growthStaminaRecoveryEnabled,
            Duration growthStaminaInitialDelay,
            Duration growthStaminaInterval
    ) {
        this(actors, messages, repository, configView, lifecycles, clock, serverOpenTime,
                domainEventPublisher, domainEventListener, saveListener, shopStockAsyncClient, pushes,
                asyncCommandAuditSink, commandSequencer, AsyncBusinessCommandPolicy.unlimited(), actorSchedules,
                growthStaminaRecoveryEnabled, growthStaminaInitialDelay, growthStaminaInterval);
    }

    public PlayerGameAgentManager(
            ActorSystem actors,
            AgentMessagePort messages,
            PlayerStateRepository repository,
            GameConfigView configView,
            AgentLifecycleManager lifecycles,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            PlayerDomainEventListener domainEventListener,
            PlayerStateSaveListener saveListener,
            ShopStockAsyncClient shopStockAsyncClient,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink,
            PlayerCommandSequencer commandSequencer,
            AsyncBusinessCommandPolicy asyncCommandPolicy,
            ActorScheduleRegistry actorSchedules,
            boolean growthStaminaRecoveryEnabled,
            Duration growthStaminaInitialDelay,
            Duration growthStaminaInterval
    ) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.recoveryService = new PlayerAgentRecoveryService(this.repository,
                Objects.requireNonNull(clock, "clock"));
        this.configView = Objects.requireNonNull(configView, "configView");
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        this.clock = clock;
        this.serverOpenTime = Objects.requireNonNull(serverOpenTime, "serverOpenTime");
        this.domainEventPublisher = domainEventPublisher;
        this.domainEventListener = domainEventListener;
        this.saveListener = Objects.requireNonNull(saveListener, "saveListener");
        this.shopStockAsyncClient = shopStockAsyncClient;
        this.pushes = Objects.requireNonNull(pushes, "pushes");
        this.asyncCommandAuditSink = Objects.requireNonNull(asyncCommandAuditSink, "asyncCommandAuditSink");
        this.commandSequencer = commandSequencer;
        this.asyncCommandPolicy = Objects.requireNonNull(asyncCommandPolicy, "asyncCommandPolicy");
        this.actorSchedules = actorSchedules;
        this.growthStaminaRecoveryEnabled = growthStaminaRecoveryEnabled;
        this.growthStaminaInitialDelay = positiveOrZero(growthStaminaInitialDelay, "growthStaminaInitialDelay");
        this.growthStaminaInterval = positive(growthStaminaInterval, "growthStaminaInterval");
    }

    public PlayerGameAgent getOrCreate(long playerId) {
        return load(playerId).agent();
    }

    public PlayerGameAgentHandle load(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        return agents.computeIfAbsent(playerId, this::create);
    }

    public Optional<PlayerGameAgent> get(long playerId) {
        return handle(playerId).map(PlayerGameAgentHandle::agent);
    }

    public Optional<PlayerGameAgentHandle> handle(long playerId) {
        return Optional.ofNullable(agents.get(playerId));
    }

    public PlayerGameAgentHandle restoreMigrated(PlayerStateSnapshot snapshot, ActorRef actorRef) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(actorRef, "actorRef");
        String ownerToken = mutationOwnerToken(snapshot.playerId());
        Optional<PlayerMutationRepository> mutations = mutationRepository();
        PlayerStateSnapshot restored = mutations
                .map(repository -> acquireMigratedSnapshot(repository, snapshot, ownerToken))
                .orElse(snapshot);
        PlayerProfile profile = PlayerProfile.restore(restored);
        PlayerGameAgent agent = createAgent(actorRef, profile, restored, mutations.orElse(null), ownerToken);
        PlayerGameAgentHandle handle = new PlayerGameAgentHandle(
                agent,
                restored,
                false,
                scheduleGrowthStaminaRecovery(agent)
        );
        PlayerGameAgentHandle previous = agents.putIfAbsent(restored.playerId(), handle);
        if (previous != null) {
            handle.cancelTimers();
            throw new IllegalStateException("player agent already loaded: " + restored.playerId());
        }
        if (mutations.isEmpty()) {
            repository.save(restored.playerId(), restored);
        }
        return handle;
    }

    public Optional<PlayerGameAgentHandle> removeMigrated(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        PlayerGameAgentHandle removed = agents.remove(playerId);
        if (removed != null) {
            removed.cancelTimers();
            actors.retire(removed.agent().actorRef());
        }
        return Optional.ofNullable(removed);
    }

    public int loadedAgents() {
        return agents.size();
    }

    public long fencingConflicts() {
        return fencingConflicts.sum();
    }

    /**
     * 迁移恢复时返回持久化检查点中的权威响应；未启用原子仓库时使用迁移包。
     */
    public PlayerBusinessResponseSnapshot migrationBusinessResponses(
            long playerId,
            PlayerBusinessResponseSnapshot fallback
    ) {
        Objects.requireNonNull(fallback, "fallback");
        if (fallback.playerId() != playerId) {
            throw new IllegalArgumentException("fallback playerId mismatch");
        }
        return mutationRepository()
                .flatMap(repository -> repository.loadResponses(playerId))
                .orElse(fallback);
    }

    /**
     * 迁移目标失败并回滚目录后，为仍驻留内存的源 Agent 重新取得 fencing token。
     */
    public PlayerGameAgentHandle reclaimAfterMigrationRollback(PlayerStateSnapshot migrationSnapshot) {
        Objects.requireNonNull(migrationSnapshot, "migrationSnapshot");
        PlayerGameAgentHandle handle = agents.get(migrationSnapshot.playerId());
        if (handle == null) {
            throw new IllegalStateException("player agent not loaded: " + migrationSnapshot.playerId());
        }
        Optional<PlayerMutationRepository> mutations = mutationRepository();
        if (mutations.isEmpty()) {
            return handle;
        }
        String ownerToken = mutationOwnerToken(migrationSnapshot.playerId());
        PlayerMutationRecord acquired = mutations.orElseThrow()
                .acquireOwner(migrationSnapshot, ownerToken);
        PlayerStateSnapshot authoritative = acquired.snapshot();
        if (authoritative.revision() < migrationSnapshot.revision()) {
            if (migrationSnapshot.revision() != authoritative.revision() + 1) {
                throw new PlayerMutationConflictException(
                        migrationSnapshot.playerId(), migrationSnapshot.revision(), authoritative.revision(),
                        PlayerMutationConflictReason.REVISION_GAP
                );
            }
            authoritative = mutations.orElseThrow()
                    .commitOwned(ownerToken, migrationSnapshot, List.of())
                    .snapshot();
        }
        handle.agent().reclaimMutationOwner(ownerToken, authoritative.revision());
        return new PlayerGameAgentHandle(
                handle.agent(), authoritative, handle.created(), handle.growthStaminaRecovery()
        );
    }

    /**
     * 注册玩家成功钝化后的内存索引清理器；监听器在状态保存成功后、钝化完成前执行。
     */
    public void onPassivated(LongConsumer listener) {
        passivationListener = Objects.requireNonNull(listener, "listener");
    }

    public List<Long> loadedPlayerIds() {
        return List.copyOf(agents.keySet());
    }

    /**
     * 绑定完成响应快照来源，使后续玩家命令可把原响应与状态放入同一原子提交。
     */
    public void attachBusinessResponseSnapshots(
            java.util.function.LongFunction<PlayerBusinessResponseSnapshot> snapshots
    ) {
        businessResponseSnapshots = Objects.requireNonNull(snapshots, "snapshots");
        agents.values().forEach(handle -> handle.agent().attachBusinessResponseSnapshots(snapshots));
    }

    /**
     * 为全部已加载玩家投递一次事务事件补交任务；实际发布仍在各自玩家邮箱内串行执行。
     */
    public int replayPendingDomainEvents() {
        int submitted = 0;
        for (PlayerGameAgentHandle handle : agents.values()) {
            handle.agent().replayPendingDomainEvents();
            submitted++;
        }
        return submitted;
    }

    public AsyncShopPurchaseView asyncShopPurchases() {
        return asyncShopPurchases;
    }

    public AsyncBusinessCommandView asyncBusinessCommands() {
        return asyncBusinessCommands;
    }

    @Override
    public AsyncShopPurchaseStats stats() {
        return asyncShopPurchases.stats();
    }

    @Override
    public AsyncBusinessCommandStats asyncBusinessCommandStats() {
        return asyncBusinessCommands.asyncBusinessCommandStats();
    }

    public boolean save(long playerId, Consumer<PlayerStateSnapshot> callback) {
        return save(playerId, PlayerStateSaveCallback.onSaved(callback));
    }

    public boolean save(long playerId, PlayerStateSaveCallback callback) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        Objects.requireNonNull(callback, "callback");
        PlayerGameAgentHandle handle = agents.get(playerId);
        if (handle == null) {
            return false;
        }
        handle.agent().save(repository, new PlayerStateSaveCallback() {
            @Override
            public void saved(PlayerStateSnapshot snapshot) {
                saveListener.saved(snapshot);
                callback.saved(snapshot);
            }

            @Override
            public void failed(long failedPlayerId, RuntimeException error) {
                callback.failed(failedPlayerId, error);
            }
        });
        return true;
    }

    public int saveAllLoaded(Consumer<PlayerStateSnapshot> callback) {
        return saveAllLoaded(PlayerStateSaveCallback.onSaved(callback));
    }

    public int saveAllLoaded(PlayerStateSaveCallback callback) {
        Objects.requireNonNull(callback, "callback");
        int submitted = 0;
        for (Long playerId : loadedPlayerIds()) {
            if (save(playerId, callback)) {
                submitted++;
            }
        }
        return submitted;
    }

    /**
     * 在玩家邮箱内保存快照后释放 owner。
     * 下线、迁移前收缩和运维驱逐都应通过这个入口完成，避免从邮箱外部读取半更新状态。
     */
    public boolean passivateAndSave(long playerId, Consumer<PlayerStateSnapshot> callback) {
        return passivateAndSave(playerId, PlayerStateSaveCallback.onSaved(callback));
    }

    /**
     * 在玩家邮箱内保存快照后释放 owner。
     * 下线、迁移前收缩和运维驱逐都应通过这个入口完成，避免从邮箱外部读取半更新状态。
     */
    public boolean passivateAndSave(long playerId, PlayerStateSaveCallback callback) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        Objects.requireNonNull(callback, "callback");
        PlayerGameAgentHandle handle = agents.get(playerId);
        if (handle == null) {
            return false;
        }
        return lifecycles.passivate(AgentIdentity.player(playerId), ignored -> {
            try {
                PlayerStateSnapshot snapshot = handle.agent().saveInCurrentMailbox(repository);
                saveListener.saved(snapshot);
                agents.remove(playerId, handle);
                handle.cancelTimers();
                if (commandSequencer != null) {
                    commandSequencer.forgetPlayer(playerId);
                }
                passivationListener.accept(playerId);
                callback.saved(snapshot);
            } catch (RuntimeException e) {
                callback.failed(playerId, e);
                throw e;
            }
        });
    }

    public int passivateAllAndSave(Consumer<PlayerStateSnapshot> callback) {
        return passivateAllAndSave(PlayerStateSaveCallback.onSaved(callback));
    }

    public int passivateAllAndSave(PlayerStateSaveCallback callback) {
        Objects.requireNonNull(callback, "callback");
        int submitted = 0;
        for (Long playerId : loadedPlayerIds()) {
            if (passivateAndSave(playerId, callback)) {
                submitted++;
            }
        }
        return submitted;
    }

    private PlayerGameAgentHandle create(long playerId) {
        ActorRef self = actors.actor(actorId(playerId));
        lifecycles.activate(AgentIdentity.player(playerId), self.id());
        try {
            Optional<PlayerMutationRepository> mutations = mutationRepository();
            String ownerToken = mutationOwnerToken(playerId);
            PlayerAgentRecovery recovery = recoveryService.recover(playerId);
            if (mutations.isPresent()) {
                PlayerMutationRecord acquired = mutations.orElseThrow()
                        .acquireOwner(recovery.snapshot(), ownerToken);
                recovery = PlayerAgentRecovery.restore(acquired.snapshot());
            }
            PlayerStateSnapshot snapshot = recovery.snapshot();
            restoreCommandSequencer(snapshot);
            PlayerGameAgent agent = createAgent(
                    self, recovery.profile(), snapshot, mutations.orElse(null), ownerToken
            );
            return new PlayerGameAgentHandle(
                    agent, snapshot, recovery.created(), scheduleGrowthStaminaRecovery(agent)
            );
        } catch (RuntimeException | Error error) {
            lifecycles.closeFailedActivation(AgentIdentity.player(playerId), self);
            throw error;
        }
    }

    private void restoreCommandSequencer(PlayerStateSnapshot snapshot) {
        if (commandSequencer != null) {
            commandSequencer.restore(snapshot.commandSequencer());
        }
    }

    private PlayerGameAgent createAgent(
            ActorRef self,
            PlayerProfile profile,
            PlayerStateSnapshot snapshot,
            PlayerMutationRepository mutations,
            String ownerToken
    ) {
        PlayerGameAgent agent = new PlayerGameAgent(
                messages,
                self,
                profile,
                configView,
                clock,
                serverOpenTime,
                domainEventPublisher,
                snapshot.revision(),
                snapshot.eventRevision(),
                domainEventListener,
                shopStockAsyncClient,
                asyncShopPurchases,
                asyncBusinessCommands,
                asyncCommandPolicy,
                pushes,
                asyncCommandAuditSink,
                commandSequencer == null ? PlayerCommandSequencerSnapshot::empty : commandSequencer::snapshotForPlayer
        );
        if (mutations != null) {
            agent.attachMutationRepository(mutations, ownerToken);
            agent.onFenced(this::fenceAgent);
            agent.replayPendingDomainEvents();
        }
        agent.attachBusinessResponseSnapshots(businessResponseSnapshots);
        return agent;
    }

    private PlayerStateSnapshot acquireMigratedSnapshot(
            PlayerMutationRepository mutations,
            PlayerStateSnapshot incoming,
            String ownerToken
    ) {
        PlayerMutationRecord current = mutations.acquireOwner(incoming, ownerToken);
        long currentRevision = current.snapshot().revision();
        if (currentRevision >= incoming.revision()) {
            return current.snapshot();
        }
        if (incoming.revision() != currentRevision + 1) {
            throw new PlayerMutationConflictException(
                    incoming.playerId(), incoming.revision(), currentRevision,
                    PlayerMutationConflictReason.REVISION_GAP
            );
        }
        return mutations.commitOwned(ownerToken, incoming, List.of()).snapshot();
    }

    private void fenceAgent(PlayerGameAgent agent, PlayerMutationConflictException conflict) {
        long playerId = agent.profile().playerId();
        fencingConflicts.increment();
        PlayerGameAgentHandle handle = agents.get(playerId);
        if (handle != null && handle.agent() == agent && agents.remove(playerId, handle)) {
            handle.cancelTimers();
        }
        if (commandSequencer != null) {
            commandSequencer.forgetPlayer(playerId);
        }
        passivationListener.accept(playerId);
        lifecycles.fenceCurrent(AgentIdentity.player(playerId), agent.actorRef());
    }

    private Optional<PlayerMutationRepository> mutationRepository() {
        if (repository instanceof PlayerMutationRepository mutations) {
            return Optional.of(mutations);
        }
        if (repository instanceof PlayerCheckpointRepository checkpoints) {
            return checkpoints.mutations();
        }
        return Optional.empty();
    }

    private String mutationOwnerToken(long playerId) {
        return lifecycles.lease(AgentIdentity.player(playerId))
                .map(PlayerMutationOwnerToken::encode)
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    private String actorId(long playerId) {
        return "player-" + playerId;
    }

    private ActorTimerHandle scheduleGrowthStaminaRecovery(PlayerGameAgent agent) {
        if (!growthStaminaRecoveryEnabled || actorSchedules == null) {
            return null;
        }
        return agent.scheduleGrowthStaminaRecovery(actorSchedules, growthStaminaInitialDelay, growthStaminaInterval);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration positiveOrZero(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
