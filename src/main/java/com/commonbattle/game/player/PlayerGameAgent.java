package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTimerHandle;
import com.commonbattle.actor.ActorTimerService;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.activity.ActivityAccessContext;
import com.commonbattle.game.activity.ActivityClaimResult;
import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.achievement.AchievementClaimResult;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.battle.BattleService;
import com.commonbattle.game.config.GameConfigRegistry;
import com.commonbattle.game.config.GameConfigView;
import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.growth.GrowthResult;
import com.commonbattle.game.growth.GrowthService;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.GrowthLevelChangedEvent;
import com.commonbattle.game.player.event.PlayerDomainEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.player.event.ShopItemPurchasedEvent;
import com.commonbattle.game.shop.ActorMailboxShopStockClient;
import com.commonbattle.game.shop.ActorShopStockCallback;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopService;
import com.commonbattle.game.shop.ShopStockAsyncClient;
import com.commonbattle.game.shop.ShopStockCallback;
import com.commonbattle.game.shop.ShopStockReleaseResponse;
import com.commonbattle.game.shop.ShopStockReserveResponse;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.task.TaskClaimResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/**
 * 玩家通用业务 Agent。
 * 背包、活动、养成等模块都通过同一个玩家 Actor 串行执行，避免模块之间再加锁。
 */
public final class PlayerGameAgent {
    private final AgentMessagePort messages;
    private final ActorRef self;
    private final PlayerProfile profile;
    private final LongFunction<PlayerGameRuntime> runtimeResolver;
    private final Clock clock;
    private final Instant serverOpenTime;
    private final EventPublisher domainEventPublisher;
    private final PlayerDomainEventListener domainEventListener;
    private final ShopStockAsyncClient shopStockAsyncClient;
    private final AsyncShopPurchaseMetrics asyncShopPurchases;
    private long stateRevision;
    private long domainEventRevision;

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            ActivityService activityService,
            GrowthService growthService
    ) {
        this(messages, self, profile, activityService, growthService, Clock.systemUTC(), Instant.EPOCH);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            ActivityService activityService,
            GrowthService growthService,
            ShopService shopService
    ) {
        this(messages, self, profile, activityService, growthService, shopService, Clock.systemUTC(), Instant.EPOCH);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            ActivityService activityService,
            GrowthService growthService,
            ShopService shopService,
            BattleService battleService
    ) {
        this(messages, self, profile, activityService, growthService, shopService, battleService,
                Clock.systemUTC(), Instant.EPOCH);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            GameConfigRegistry configRegistry
    ) {
        this(messages, self, profile, configRegistry, Clock.systemUTC(), Instant.EPOCH);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            GameConfigView configView
    ) {
        this(messages, self, profile, configView, Clock.systemUTC(), Instant.EPOCH);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            ActivityService activityService,
            GrowthService growthService,
            Clock clock,
            Instant serverOpenTime
    ) {
        this(messages, self, profile, activityService, growthService, clock, serverOpenTime, 0);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            GameConfigRegistry configRegistry,
            Clock clock,
            Instant serverOpenTime
    ) {
        this(messages, self, profile, (GameConfigView) configRegistry, clock, serverOpenTime);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            GameConfigView configView,
            Clock clock,
            Instant serverOpenTime
    ) {
        this(
                messages,
                self,
                profile,
                runtimeResolver(configView),
                clock,
                serverOpenTime,
                null,
                0,
                0,
                null,
                null,
                new AsyncShopPurchaseMetrics()
        );
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            GameConfigRegistry configRegistry,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            long initialStateRevision,
            long initialEventRevision
    ) {
        this(messages, self, profile, (GameConfigView) configRegistry, clock, serverOpenTime,
                domainEventPublisher, initialStateRevision, initialEventRevision);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            GameConfigView configView,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            long initialStateRevision,
            long initialEventRevision
    ) {
        this(messages, self, profile, configView, clock, serverOpenTime,
                domainEventPublisher, initialStateRevision, initialEventRevision, null);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            GameConfigView configView,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            long initialStateRevision,
            long initialEventRevision,
            PlayerDomainEventListener domainEventListener
    ) {
        this(messages, self, profile, configView, clock, serverOpenTime,
                domainEventPublisher, initialStateRevision, initialEventRevision, domainEventListener, null);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            GameConfigView configView,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            long initialStateRevision,
            long initialEventRevision,
            PlayerDomainEventListener domainEventListener,
            ShopStockAsyncClient shopStockAsyncClient
    ) {
        this(
                messages,
                self,
                profile,
                runtimeResolver(configView),
                clock,
                serverOpenTime,
                domainEventPublisher,
                initialStateRevision,
                initialEventRevision,
                domainEventListener,
                shopStockAsyncClient,
                new AsyncShopPurchaseMetrics()
        );
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            GameConfigView configView,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            long initialStateRevision,
            long initialEventRevision,
            PlayerDomainEventListener domainEventListener,
            ShopStockAsyncClient shopStockAsyncClient,
            AsyncShopPurchaseMetrics asyncShopPurchases
    ) {
        this(
                messages,
                self,
                profile,
                runtimeResolver(configView),
                clock,
                serverOpenTime,
                domainEventPublisher,
                initialStateRevision,
                initialEventRevision,
                domainEventListener,
                shopStockAsyncClient,
                asyncShopPurchases
        );
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            ActivityService activityService,
            GrowthService growthService,
            ShopService shopService,
            Clock clock,
            Instant serverOpenTime
    ) {
        this(messages, self, profile, activityService, growthService, shopService, null, clock, serverOpenTime);
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            ActivityService activityService,
            GrowthService growthService,
            ShopService shopService,
            BattleService battleService,
            Clock clock,
            Instant serverOpenTime
    ) {
        this(
                messages,
                self,
                profile,
                fixedRuntimeResolver(activityService, growthService, shopService, battleService),
                clock,
                serverOpenTime,
                null,
                0,
                0,
                null,
                null,
                new AsyncShopPurchaseMetrics()
        );
    }

    public PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            ActivityService activityService,
            GrowthService growthService,
            Clock clock,
            Instant serverOpenTime,
            long initialStateRevision
    ) {
        this(
                messages,
                self,
                profile,
                fixedRuntimeResolver(activityService, growthService),
                clock,
                serverOpenTime,
                null,
                initialStateRevision,
                0,
                null,
                null,
                new AsyncShopPurchaseMetrics()
        );
    }

    private PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            LongFunction<PlayerGameRuntime> runtimeResolver,
            Clock clock,
            Instant serverOpenTime,
            EventPublisher domainEventPublisher,
            long initialStateRevision,
            long initialEventRevision,
            PlayerDomainEventListener domainEventListener,
            ShopStockAsyncClient shopStockAsyncClient,
            AsyncShopPurchaseMetrics asyncShopPurchases
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serverOpenTime = Objects.requireNonNull(serverOpenTime, "serverOpenTime");
        this.domainEventPublisher = domainEventPublisher;
        this.domainEventListener = domainEventListener;
        this.shopStockAsyncClient = shopStockAsyncClient;
        this.asyncShopPurchases = Objects.requireNonNull(asyncShopPurchases, "asyncShopPurchases");
        loadRevision(initialStateRevision);
        loadDomainEventRevision(initialEventRevision);
    }

    public PlayerProfile profile() {
        return profile;
    }

    public void onLogin(String activityId, Consumer<ActivityClaimResult> callback) {
        execute(execution -> {
            ActivityService service = execution.runtime().activityService();
            service.recordLogin(profile.activities(), execution.activityAccess(), activityId);
            callback.accept(service.claim(profile.activities(), profile.bag(), execution.activityAccess(), activityId));
        });
    }

    public void addActivityProgress(String activityId, int delta) {
        execute(execution ->
                execution.runtime().activityService().increase(profile.activities(), execution.activityAccess(), activityId, delta));
    }

    public void claimActivity(String activityId, Consumer<ActivityClaimResult> callback) {
        execute(execution ->
                callback.accept(execution.runtime().activityService()
                        .claim(profile.activities(), profile.bag(), execution.activityAccess(), activityId)));
    }

    public void useExpItems(int count, Consumer<GrowthResult> callback) {
        execute(execution -> {
            GrowthResult result = execution.runtime().growthService().useExpItems(profile.bag(), profile.growth(), count);
            if (result.afterLevel() > result.beforeLevel()) {
                execution.publish(new GrowthLevelChangedEvent(profile.playerId(), result.beforeLevel(), result.afterLevel()));
            }
            callback.accept(result);
        });
    }

    public void buyShopItem(String sku, int quantity, Consumer<ShopPurchaseResult> callback) {
        buyShopItem("", sku, quantity, callback);
    }

    public void buyShopItem(String orderId, String sku, int quantity, Consumer<ShopPurchaseResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> {
            ShopPurchaseResult result = execution.runtime().requireShopService()
                    .purchaseAt(profile.bag(), profile.shop(), orderId, sku, quantity, clock.instant());
            if (result.success()) {
                execution.publish(ShopItemPurchasedEvent.from(profile.playerId(), result));
            }
            callback.accept(result);
        });
    }

    public void buyShopItemAsync(
            PlayerCommand command,
            String orderId,
            String sku,
            int quantity,
            PlayerBusinessResultSink results
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(results, "results");
        asyncShopPurchases.startedPurchase();
        PlayerGameExecution execution = execution();
        ShopService service = execution.runtime().requireShopService();
        if (!service.requiresStockReservation(sku)) {
            asyncShopPurchases.immediatePurchase();
            completeShopPurchase(command, execution,
                    service.purchaseAt(profile.bag(), profile.shop(), orderId, sku, quantity, clock.instant()), results);
            return;
        }
        String reservationId = reservationId(command, orderId);
        asyncShopPurchases.stockReservation();
        new ActorMailboxShopStockClient(requireShopStockAsyncClient(), messages, self).reserve(
                reservationId,
                sku,
                quantity,
                new ActorShopStockCallback<>() {
                    @Override
                    public void success(com.commonbattle.actor.ActorContext context, ShopStockReserveResponse response) {
                        if (!results.canComplete(command)) {
                            asyncShopPurchases.lateCallback();
                            if (response.reserved()) {
                                releaseReservedStock(reservationId, sku, quantity);
                            }
                            return;
                        }
                        PlayerGameExecution callbackExecution = execution();
                        ShopService callbackService = callbackExecution.runtime().requireShopService();
                        if (!response.reserved()) {
                            asyncShopPurchases.outOfStockCallback();
                            completeShopPurchase(command, callbackExecution,
                                    callbackService.outOfStock(profile.shop(), sku, quantity, clock.instant()), results);
                            return;
                        }
                        asyncShopPurchases.reservedCallback();
                        ShopPurchaseResult result = callbackService.purchaseReservedAt(
                                profile.bag(),
                                profile.shop(),
                                orderId,
                                sku,
                                quantity,
                                clock.instant()
                        );
                        if (!result.success()) {
                            releaseReservedStock(reservationId, sku, quantity);
                        }
                        completeShopPurchase(command, callbackExecution, result, results);
                    }

                    @Override
                    public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                        if (!results.canComplete(command)) {
                            asyncShopPurchases.lateCallback();
                            return;
                        }
                        asyncShopPurchases.rpcFailure();
                        results.failed(command, error);
                    }
                }
        );
    }

    public void clearBattleStage(String stageId, Consumer<BattleSettlementResult> callback) {
        clearBattleStage("", stageId, callback);
    }

    public void clearBattleStage(String settlementId, String stageId, Consumer<BattleSettlementResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> {
            BattleSettlementResult result = execution.runtime().requireBattleService()
                    .clear(profile.bag(), profile.battle(), execution.activityAccess().now(), settlementId, stageId);
            if (result.victory()) {
                execution.publish(BattleStageClearedEvent.from(profile.playerId(), result));
            }
            callback.accept(result);
        });
    }

    public void sweepBattleStage(String stageId, Consumer<BattleSettlementResult> callback) {
        sweepBattleStage("", stageId, callback);
    }

    public void sweepBattleStage(String settlementId, String stageId, Consumer<BattleSettlementResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> {
            BattleSettlementResult result = execution.runtime().requireBattleService()
                    .sweep(profile.bag(), profile.battle(), execution.activityAccess().now(), settlementId, stageId);
            execution.publish(BattleStageClearedEvent.from(profile.playerId(), result));
            callback.accept(result);
        });
    }

    public void claimTask(String taskId, Consumer<TaskClaimResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> callback.accept(execution.runtime().requireTaskService()
                .claim(profile.tasks(), profile.bag(), taskId)));
    }

    public void claimAchievement(String achievementId, Consumer<AchievementClaimResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> callback.accept(execution.runtime().requireAchievementService()
                .claim(profile.achievements(), profile.bag(), achievementId)));
    }

    public <R> R executeBusiness(PlayerBusinessCommand<R> command) {
        Objects.requireNonNull(command, "command");
        return command.execute(execution());
    }

    /**
     * 在玩家邮箱中执行自定义业务逻辑，并在入口绑定本次消息使用的配置版本。
     */
    public void execute(Consumer<PlayerGameExecution> handler) {
        Objects.requireNonNull(handler, "handler");
        messages.tellLocal(self, ignored -> handler.accept(execution()));
    }

    public void save(PlayerStateRepository repository, Consumer<PlayerStateSnapshot> callback) {
        save(repository, PlayerStateSaveCallback.onSaved(callback));
    }

    public void save(PlayerStateRepository repository, PlayerStateSaveCallback callback) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ignored -> {
            try {
                PlayerStateSnapshot snapshot = nextSnapshot();
                repository.save(profile.playerId(), snapshot);
                callback.saved(snapshot);
            } catch (RuntimeException e) {
                callback.failed(profile.playerId(), e);
                throw e;
            }
        });
    }

    PlayerStateSnapshot saveInCurrentMailbox(PlayerStateRepository repository) {
        Objects.requireNonNull(repository, "repository");
        PlayerStateSnapshot snapshot = nextSnapshot();
        repository.save(profile.playerId(), snapshot);
        return snapshot;
    }

    public void exportForMigration(Consumer<PlayerStateSnapshot> callback) {
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ignored -> callback.accept(nextSnapshot()));
    }

    public ActorTimerHandle scheduleAutoSave(
            ActorTimerService timers,
            Duration initialDelay,
            Duration interval,
            PlayerStateRepository repository,
            Consumer<PlayerStateSnapshot> callback
    ) {
        Objects.requireNonNull(timers, "timers");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(callback, "callback");
        return timers.scheduleAtFixedRate(self, initialDelay, interval, ignored -> {
            PlayerStateSnapshot snapshot = nextSnapshot();
            repository.save(profile.playerId(), snapshot);
            callback.accept(snapshot);
        });
    }

    public void loadRevision(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        stateRevision = revision;
    }

    public void loadDomainEventRevision(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        domainEventRevision = revision;
    }

    private ActivityAccessContext activityAccess() {
        return new ActivityAccessContext(clock.instant(), serverOpenTime, profile);
    }

    private PlayerGameExecution execution() {
        PlayerGameRuntime runtime = runtimeResolver.apply(profile.playerId());
        return new PlayerGameExecution(profile, runtime, activityAccess(), this::publishDomainEvent);
    }

    private void publishDomainEvent(PlayerDomainEvent event) {
        if (event.replayed() || (domainEventPublisher == null && domainEventListener == null)) {
            return;
        }
        domainEventRevision++;
        if (domainEventPublisher != null) {
            domainEventPublisher.publish(PlayerDomainVersionedEvent.from(
                    profile.playerId(),
                    domainEventRevision,
                    clock.instant(),
                    event
            ));
        }
        if (domainEventListener != null) {
            domainEventListener.onEvent(profile, domainEventRevision, event);
        }
    }

    private ShopStockAsyncClient requireShopStockAsyncClient() {
        if (shopStockAsyncClient == null) {
            throw new IllegalStateException("shop stock async client is not available for this runtime");
        }
        return shopStockAsyncClient;
    }

    private void releaseReservedStock(String reservationId, String sku, int quantity) {
        requireShopStockAsyncClient().release(
                reservationId,
                sku,
                quantity,
                new ShopStockCallback<>() {
                    @Override
                    public void success(ShopStockReleaseResponse response) {
                        asyncShopPurchases.releasedReservation();
                    }

                    @Override
                    public void failure(Throwable error) {
                        asyncShopPurchases.releaseFailure();
                    }
                }
        );
    }

    private void completeShopPurchase(
            PlayerCommand command,
            PlayerGameExecution execution,
            ShopPurchaseResult result,
            PlayerBusinessResultSink results
    ) {
        if (result.success()) {
            asyncShopPurchases.completedPurchase();
            execution.publish(ShopItemPurchasedEvent.from(profile.playerId(), result));
        } else {
            asyncShopPurchases.rejectedPurchase();
        }
        results.completed(command, PlayerBusinessResponse.success(command, result));
    }

    private String reservationId(PlayerCommand command, String orderId) {
        if (orderId != null && !orderId.isBlank()) {
            return orderId;
        }
        return "player:" + command.playerId() + ":seq:" + command.sequence();
    }

    private static LongFunction<PlayerGameRuntime> fixedRuntimeResolver(
            ActivityService activityService,
            GrowthService growthService
    ) {
        PlayerGameRuntime runtime = PlayerGameRuntime.fixed(activityService, growthService);
        return ignored -> runtime;
    }

    private static LongFunction<PlayerGameRuntime> fixedRuntimeResolver(
            ActivityService activityService,
            GrowthService growthService,
            ShopService shopService
    ) {
        return fixedRuntimeResolver(activityService, growthService, shopService, null);
    }

    private static LongFunction<PlayerGameRuntime> fixedRuntimeResolver(
            ActivityService activityService,
            GrowthService growthService,
            ShopService shopService,
            BattleService battleService
    ) {
        PlayerGameRuntime runtime = PlayerGameRuntime.fixed(activityService, growthService, shopService, battleService);
        return ignored -> runtime;
    }

    private static LongFunction<PlayerGameRuntime> runtimeResolver(GameConfigView configView) {
        Objects.requireNonNull(configView, "configView");
        return playerId -> PlayerGameRuntime.from(configView.resolve(playerId));
    }

    private PlayerStateSnapshot nextSnapshot() {
        // 存盘边界：在玩家邮箱内生成完整状态快照并递增 revision，避免跨模块状态被拆成不一致切片。
        stateRevision++;
        return profile.snapshot(stateRevision, domainEventRevision, clock.instant());
    }
}
