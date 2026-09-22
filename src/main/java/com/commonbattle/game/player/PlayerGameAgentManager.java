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
import com.commonbattle.game.shop.ShopStockAsyncClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Game 服玩家 Agent 管理器。
 * 登录、网关命令和本服业务入口都应通过它取得玩家 Agent，避免重复恢复、重复占用 owner 或漏注入事件发布器。
 */
public final class PlayerGameAgentManager implements AsyncShopPurchaseView {
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
    private final ActorScheduleRegistry actorSchedules;
    private final boolean growthStaminaRecoveryEnabled;
    private final Duration growthStaminaInitialDelay;
    private final Duration growthStaminaInterval;
    private final AsyncShopPurchaseMetrics asyncShopPurchases = new AsyncShopPurchaseMetrics();
    private final Map<Long, PlayerGameAgentHandle> agents = new ConcurrentHashMap<>();

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
                null, false, Duration.ZERO, Duration.ofMinutes(5));
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
        PlayerProfile profile = PlayerProfile.restore(snapshot);
        PlayerGameAgent agent = createAgent(actorRef, profile, snapshot);
        PlayerGameAgentHandle handle = new PlayerGameAgentHandle(
                agent,
                snapshot,
                false,
                scheduleGrowthStaminaRecovery(agent)
        );
        PlayerGameAgentHandle previous = agents.putIfAbsent(snapshot.playerId(), handle);
        if (previous != null) {
            handle.cancelTimers();
            throw new IllegalStateException("player agent already loaded: " + snapshot.playerId());
        }
        repository.save(snapshot.playerId(), snapshot);
        return handle;
    }

    public Optional<PlayerGameAgentHandle> removeMigrated(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        PlayerGameAgentHandle removed = agents.remove(playerId);
        if (removed != null) {
            removed.cancelTimers();
        }
        return Optional.ofNullable(removed);
    }

    public int loadedAgents() {
        return agents.size();
    }

    public List<Long> loadedPlayerIds() {
        return List.copyOf(agents.keySet());
    }

    public AsyncShopPurchaseView asyncShopPurchases() {
        return asyncShopPurchases;
    }

    @Override
    public AsyncShopPurchaseStats stats() {
        return asyncShopPurchases.stats();
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
        PlayerAgentRecovery recovery = recoveryService.recover(playerId);
        ActorRef self = actors.actor(actorId(playerId));
        lifecycles.activate(AgentIdentity.player(playerId), self.id());
        PlayerStateSnapshot snapshot = recovery.snapshot();
        PlayerGameAgent agent = createAgent(self, recovery.profile(), snapshot);
        return new PlayerGameAgentHandle(agent, snapshot, recovery.created(), scheduleGrowthStaminaRecovery(agent));
    }

    private PlayerGameAgent createAgent(ActorRef self, PlayerProfile profile, PlayerStateSnapshot snapshot) {
        return new PlayerGameAgent(
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
                pushes
        );
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
