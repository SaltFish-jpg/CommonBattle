package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorScheduleKey;
import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.ActorTimerHandle;
import com.commonbattle.actor.ActorTimerService;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.activity.ActivityAccessContext;
import com.commonbattle.game.activity.ActivityClaimResult;
import com.commonbattle.game.activity.ActivityEligibilityAsyncClient;
import com.commonbattle.game.activity.ActivityEligibilityDecision;
import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.activity.AsyncActivityClaimResult;
import com.commonbattle.game.achievement.AchievementClaimResult;
import com.commonbattle.game.agent.AsyncActorCallback;
import com.commonbattle.game.bag.BagChange;
import com.commonbattle.game.bag.BagResult;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.battle.BattleService;
import com.commonbattle.game.config.GameConfigRegistry;
import com.commonbattle.game.config.GameConfigView;
import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.growth.GrowthRecoveryResult;
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
import com.commonbattle.game.session.PlayerCommandAuditOutcome;
import com.commonbattle.game.session.PlayerCommandAuditRecord;
import com.commonbattle.game.session.PlayerCommandAuditSink;
import com.commonbattle.game.session.PlayerCommandSequencerSnapshot;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;
import com.commonbattle.game.task.TaskClaimResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
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
    private final ActorMailboxShopStockClient shopStocks;
    private final AsyncShopPurchaseMetrics asyncShopPurchases;
    private final AsyncBusinessCommandMetrics asyncBusinessCommands;
    private final AsyncBusinessCommandPolicy asyncCommandPolicy;
    private final PlayerPushPort pushes;
    private final PlayerCommandAuditSink asyncCommandAuditSink;
    private final LongFunction<PlayerCommandSequencerSnapshot> commandSequencerSnapshots;
    private final Map<PendingAsyncCommand, PendingAsyncCommandState> pendingAsyncCommands = new HashMap<>();
    private PlayerMutationRepository mutationRepository;
    private volatile String mutationOwnerToken;
    private PlayerAgentFenceListener fenceListener = PlayerAgentFenceListener.NOOP;
    private volatile PlayerMutationConflictException fencingConflict;
    private MutationScope activeMutation;
    private LongFunction<PlayerBusinessResponseSnapshot> businessResponseSnapshots =
            PlayerBusinessResponseSnapshot::empty;
    private final AtomicBoolean mutationReplayQueued = new AtomicBoolean();
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
                new AsyncShopPurchaseMetrics(),
                new AsyncBusinessCommandMetrics(),
                PlayerPushPort.NOOP,
                PlayerCommandAuditSink.NOOP
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
                new AsyncShopPurchaseMetrics(),
                new AsyncBusinessCommandMetrics(),
                PlayerPushPort.NOOP,
                PlayerCommandAuditSink.NOOP
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
        this(messages, self, profile, configView, clock, serverOpenTime, domainEventPublisher,
                initialStateRevision, initialEventRevision, domainEventListener, shopStockAsyncClient,
                asyncShopPurchases, new AsyncBusinessCommandMetrics(), PlayerPushPort.NOOP, PlayerCommandAuditSink.NOOP);
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
            AsyncShopPurchaseMetrics asyncShopPurchases,
            PlayerPushPort pushes
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
                asyncShopPurchases,
                new AsyncBusinessCommandMetrics(),
                pushes,
                PlayerCommandAuditSink.NOOP
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
            AsyncShopPurchaseMetrics asyncShopPurchases,
            AsyncBusinessCommandMetrics asyncBusinessCommands,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink
    ) {
        this(messages, self, profile, configView, clock, serverOpenTime, domainEventPublisher,
                initialStateRevision, initialEventRevision, domainEventListener, shopStockAsyncClient,
                asyncShopPurchases, asyncBusinessCommands, pushes, asyncCommandAuditSink,
                PlayerCommandSequencerSnapshot::empty);
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
            AsyncShopPurchaseMetrics asyncShopPurchases,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink
    ) {
        this(messages, self, profile, configView, clock, serverOpenTime, domainEventPublisher,
                initialStateRevision, initialEventRevision, domainEventListener, shopStockAsyncClient,
                asyncShopPurchases, new AsyncBusinessCommandMetrics(), pushes, asyncCommandAuditSink,
                PlayerCommandSequencerSnapshot::empty);
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
            AsyncShopPurchaseMetrics asyncShopPurchases,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink,
            LongFunction<PlayerCommandSequencerSnapshot> commandSequencerSnapshots
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
                asyncShopPurchases,
                new AsyncBusinessCommandMetrics(),
                AsyncBusinessCommandPolicy.unlimited(),
                pushes,
                asyncCommandAuditSink,
                commandSequencerSnapshots
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
            AsyncShopPurchaseMetrics asyncShopPurchases,
            AsyncBusinessCommandMetrics asyncBusinessCommands,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink,
            LongFunction<PlayerCommandSequencerSnapshot> commandSequencerSnapshots
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
                asyncShopPurchases,
                asyncBusinessCommands,
                AsyncBusinessCommandPolicy.unlimited(),
                pushes,
                asyncCommandAuditSink,
                commandSequencerSnapshots
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
            AsyncShopPurchaseMetrics asyncShopPurchases,
            AsyncBusinessCommandMetrics asyncBusinessCommands,
            AsyncBusinessCommandPolicy asyncCommandPolicy,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink,
            LongFunction<PlayerCommandSequencerSnapshot> commandSequencerSnapshots
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
                asyncShopPurchases,
                asyncBusinessCommands,
                asyncCommandPolicy,
                pushes,
                asyncCommandAuditSink,
                commandSequencerSnapshots
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
                new AsyncShopPurchaseMetrics(),
                new AsyncBusinessCommandMetrics(),
                PlayerPushPort.NOOP,
                PlayerCommandAuditSink.NOOP
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
                new AsyncShopPurchaseMetrics(),
                new AsyncBusinessCommandMetrics(),
                PlayerPushPort.NOOP,
                PlayerCommandAuditSink.NOOP
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
            PlayerPushPort pushes
    ) {
        this(
                messages,
                self,
                profile,
                fixedRuntimeResolver(activityService, growthService),
                clock,
                serverOpenTime,
                null,
                0,
                0,
                null,
                null,
                new AsyncShopPurchaseMetrics(),
                new AsyncBusinessCommandMetrics(),
                pushes,
                PlayerCommandAuditSink.NOOP
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
            Instant serverOpenTime,
            PlayerPushPort pushes
    ) {
        this(
                messages,
                self,
                profile,
                fixedRuntimeResolver(activityService, growthService, shopService),
                clock,
                serverOpenTime,
                null,
                0,
                0,
                null,
                null,
                new AsyncShopPurchaseMetrics(),
                new AsyncBusinessCommandMetrics(),
                pushes,
                PlayerCommandAuditSink.NOOP
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
            AsyncShopPurchaseMetrics asyncShopPurchases,
            AsyncBusinessCommandMetrics asyncBusinessCommands,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink
    ) {
        this(messages, self, profile, runtimeResolver, clock, serverOpenTime, domainEventPublisher,
                initialStateRevision, initialEventRevision, domainEventListener, shopStockAsyncClient,
                asyncShopPurchases, asyncBusinessCommands, AsyncBusinessCommandPolicy.unlimited(), pushes,
                asyncCommandAuditSink,
                PlayerCommandSequencerSnapshot::empty);
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
            AsyncShopPurchaseMetrics asyncShopPurchases,
            AsyncBusinessCommandMetrics asyncBusinessCommands,
            AsyncBusinessCommandPolicy asyncCommandPolicy,
            PlayerPushPort pushes,
            PlayerCommandAuditSink asyncCommandAuditSink,
            LongFunction<PlayerCommandSequencerSnapshot> commandSequencerSnapshots
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serverOpenTime = Objects.requireNonNull(serverOpenTime, "serverOpenTime");
        this.domainEventPublisher = domainEventPublisher;
        this.domainEventListener = domainEventListener;
        this.shopStocks = shopStockAsyncClient == null
                ? null
                : new ActorMailboxShopStockClient(shopStockAsyncClient, messages, self);
        this.asyncShopPurchases = Objects.requireNonNull(asyncShopPurchases, "asyncShopPurchases");
        this.asyncBusinessCommands = Objects.requireNonNull(asyncBusinessCommands, "asyncBusinessCommands");
        this.asyncCommandPolicy = Objects.requireNonNull(asyncCommandPolicy, "asyncCommandPolicy");
        this.pushes = Objects.requireNonNull(pushes, "pushes");
        this.asyncCommandAuditSink = Objects.requireNonNull(asyncCommandAuditSink, "asyncCommandAuditSink");
        this.commandSequencerSnapshots = Objects.requireNonNull(commandSequencerSnapshots, "commandSequencerSnapshots");
        loadRevision(initialStateRevision);
        loadDomainEventRevision(initialEventRevision);
    }

    public PlayerProfile profile() {
        return profile;
    }

    /**
     * 返回承载该玩家状态的邮箱引用，用于生命周期回收和运维定位。
     */
    public ActorRef actorRef() {
        return self;
    }

    public void onLogin(String activityId, Consumer<ActivityClaimResult> callback) {
        execute(execution -> {
            ActivityService service = execution.runtime().activityService();
            service.recordLogin(profile.activities(), execution.activityAccess(), activityId);
            ActivityClaimResult result = service.claim(profile.activities(), profile.bag(), execution.activityAccess(), activityId);
            execution.pushActivitySnapshot();
            execution.publishBagChanged("player.login", false, result.bagResult());
            execution.pushBagSnapshot();
            afterCommit(() -> callback.accept(result));
        });
    }

    public void addActivityProgress(String activityId, int delta) {
        execute(execution -> {
            execution.runtime().activityService().increase(profile.activities(), execution.activityAccess(), activityId, delta);
            execution.pushActivitySnapshot();
        });
    }

    public void claimActivity(String activityId, Consumer<ActivityClaimResult> callback) {
        execute(execution -> {
            ActivityClaimResult result = execution.runtime().activityService()
                    .claim(profile.activities(), profile.bag(), execution.activityAccess(), activityId);
            execution.pushActivitySnapshot();
            execution.publishBagChanged(PlayerBusinessOperations.ACTIVITY_CLAIM, false, result.bagResult());
            execution.pushBagSnapshot();
            afterCommit(() -> callback.accept(result));
        });
    }

    public void claimActivityAsync(
            String activityId,
            ActivityEligibilityAsyncClient eligibility,
            Consumer<AsyncActivityClaimResult> callback
    ) {
        Objects.requireNonNull(activityId, "activityId");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(callback, "callback");
        eligibility.check(profile.playerId(), activityId, new com.commonbattle.game.activity.ActivityEligibilityCallback() {
            @Override
            public void success(ActivityEligibilityDecision decision) {
                deliverActivityEligibility(activityId, decision, callback);
            }

            @Override
            public void failure(Throwable error) {
                deliverActivityEligibilityFailure(activityId, error, callback);
            }
        });
    }

    public void claimActivityAsync(
            PlayerCommand command,
            String activityId,
            ActivityEligibilityAsyncClient eligibility,
            PlayerBusinessResultSink results
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(activityId, "activityId");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(results, "results");
        asyncBusinessCommands.commandStarted(command.operation());
        PendingAsyncCommand pending = startPendingAsyncCommand(command, results, false);
        if (pending == null) {
            return;
        }
        try {
            eligibility.check(profile.playerId(), activityId, new com.commonbattle.game.activity.ActivityEligibilityCallback() {
                @Override
                public void success(ActivityEligibilityDecision decision) {
                    deliverActivityEligibility(pending, command, activityId, decision, results);
                }

                @Override
                public void failure(Throwable error) {
                    deliverActivityEligibilityFailure(pending, command, activityId, error, results);
                }
            });
        } catch (RuntimeException e) {
            completeAsyncActivityClaimResult(
                    pending,
                    command,
                    AsyncActivityClaimResult.checkFailed(activityId, e),
                    results
            );
        }
    }

    public void consumeBagItem(String itemId, int count, Consumer<BagResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> {
            BagResult result = new ConsumeBagItemCommand(itemId, count).execute(execution);
            afterCommit(() -> callback.accept(result));
        });
    }

    public void useExpItems(int count, Consumer<GrowthResult> callback) {
        execute(execution -> {
            GrowthService service = execution.runtime().growthService();
            String expItemId = service.expItemId();
            int beforeItemCount = profile.bag().count(expItemId);
            GrowthResult result = service.useExpItems(profile.bag(), profile.growth(), count);
            if (result.afterLevel() > result.beforeLevel()) {
                execution.publish(new GrowthLevelChangedEvent(profile.playerId(), result.beforeLevel(), result.afterLevel()));
            }
            execution.publishBagChanged(PlayerBusinessOperations.GROWTH_USE_EXP_ITEMS, false, new BagResult(List.of(
                    new BagChange(expItemId, beforeItemCount, profile.bag().count(expItemId))
            )));
            execution.pushGrowthSnapshot();
            execution.pushBagSnapshot();
            afterCommit(() -> callback.accept(result));
        });
    }

    public void recoverGrowthStamina(Consumer<GrowthRecoveryResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> {
            GrowthRecoveryResult result = execution.runtime().growthService()
                    .recoverStamina(profile.growth(), execution.activityAccess().now());
            if (result.changed()) {
                execution.pushGrowthSnapshot();
            }
            afterCommit(() -> callback.accept(result));
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
                execution.publishBagChanged(PlayerBusinessOperations.SHOP_BUY, result.replayed(), result.cost(), result.reward());
                if (!result.replayed()) {
                    execution.pushShopSnapshot();
                    execution.pushBagSnapshot();
                }
            }
            afterCommit(() -> callback.accept(result));
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
        asyncBusinessCommands.commandStarted(command.operation());
        asyncShopPurchases.startedPurchase();
        PlayerGameExecution execution = execution();
        ShopService service = execution.runtime().requireShopService();
        if (!service.requiresStockReservation(sku)) {
            asyncShopPurchases.immediatePurchase();
            mutate(callbackExecution -> {
                ShopPurchaseResult result = callbackExecution.runtime().requireShopService()
                        .purchaseAt(profile.bag(), profile.shop(), orderId, sku, quantity, clock.instant());
                completeShopPurchase(command, callbackExecution, result, results);
                return null;
            });
            return;
        }
        String reservationId = reservationId(command, orderId);
        PendingAsyncCommand pending = startPendingAsyncCommand(command, results, true);
        if (pending == null) {
            return;
        }
        asyncShopPurchases.stockReservation();
        try {
            requireShopStocks().reserve(
                    reservationId,
                    sku,
                    quantity,
                    new ActorShopStockCallback<>() {
                        @Override
                        public void success(com.commonbattle.actor.ActorContext context, ShopStockReserveResponse response) {
                            removePendingAsyncCommand(pending);
                            if (!results.canComplete(command)) {
                                asyncBusinessCommands.lateCallback(command.operation());
                                asyncShopPurchases.lateCallback();
                                if (response.reserved()) {
                                    releaseReservedStock(reservationId, sku, quantity);
                                }
                                return;
                            }
                            mutate(callbackExecution -> {
                                ShopService callbackService = callbackExecution.runtime().requireShopService();
                                if (!response.reserved()) {
                                    asyncShopPurchases.outOfStockCallback();
                                    completeShopPurchase(command, callbackExecution,
                                            callbackService.outOfStock(profile.shop(), sku, quantity, clock.instant()), results);
                                    return null;
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
                                return null;
                            });
                        }

                        @Override
                        public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                            failAsyncShopPurchase(pending, command, results, error);
                        }
                    },
                    ignored -> {
                        asyncBusinessCommands.callbackDeliveryFailure(command.operation());
                        asyncShopPurchases.callbackDeliveryFailure();
                    }
            );
        } catch (RuntimeException e) {
            failAsyncShopPurchase(pending, command, results, e);
        }
    }

    public void clearBattleStage(String stageId, Consumer<BattleSettlementResult> callback) {
        clearBattleStage("", stageId, callback);
    }

    public void clearBattleStage(String settlementId, String stageId, Consumer<BattleSettlementResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> {
            execution.consumeBattleStaminaForNewSettlement(
                    execution.runtime().requireBattleService().requireStage(stageId),
                    settlementId
            );
            BattleSettlementResult result = execution.runtime().requireBattleService()
                    .clear(profile.bag(), profile.battle(), execution.activityAccess().now(), settlementId, stageId);
            if (result.victory()) {
                execution.publish(BattleStageClearedEvent.from(profile.playerId(), result));
                execution.publishBagChanged(PlayerBusinessOperations.BATTLE_CLEAR_STAGE, result.replayed(), result.rewardResult());
                if (!result.replayed()) {
                    execution.pushBattleSettlementSnapshots(result);
                }
            }
            afterCommit(() -> callback.accept(result));
        });
    }

    public void sweepBattleStage(String stageId, Consumer<BattleSettlementResult> callback) {
        sweepBattleStage("", stageId, callback);
    }

    public void sweepBattleStage(String settlementId, String stageId, Consumer<BattleSettlementResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> {
            execution.consumeBattleStaminaForNewSettlement(
                    execution.runtime().requireBattleService().requireStage(stageId),
                    settlementId
            );
            BattleSettlementResult result = execution.runtime().requireBattleService()
                    .sweep(profile.bag(), profile.battle(), execution.activityAccess().now(), settlementId, stageId);
            execution.publish(BattleStageClearedEvent.from(profile.playerId(), result));
            execution.publishBagChanged(PlayerBusinessOperations.BATTLE_SWEEP_STAGE, result.replayed(), result.rewardResult());
            if (!result.replayed()) {
                execution.pushBattleSettlementSnapshots(result);
            }
            afterCommit(() -> callback.accept(result));
        });
    }

    public void claimTask(String taskId, Consumer<TaskClaimResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> {
            TaskClaimResult result = execution.runtime().requireTaskService()
                    .claim(profile.tasks(), profile.bag(), taskId);
            execution.pushTaskSnapshot();
            execution.publishBagChanged(PlayerBusinessOperations.TASK_CLAIM, false, result.reward());
            execution.pushBagSnapshot();
            afterCommit(() -> callback.accept(result));
        });
    }

    public void claimAchievement(String achievementId, Consumer<AchievementClaimResult> callback) {
        Objects.requireNonNull(callback, "callback");
        execute(execution -> {
            AchievementClaimResult result = execution.runtime().requireAchievementService()
                    .claim(profile.achievements(), profile.bag(), achievementId);
            execution.pushAchievementSnapshot();
            execution.publishBagChanged(PlayerBusinessOperations.ACHIEVEMENT_CLAIM, false, result.reward());
            execution.pushBagSnapshot();
            afterCommit(() -> callback.accept(result));
        });
    }

    public <R> R executeBusiness(PlayerBusinessCommand<R> command) {
        Objects.requireNonNull(command, "command");
        return mutate(command::execute);
    }

    /**
     * 在玩家邮箱中执行自定义业务逻辑，并在入口绑定本次消息使用的配置版本。
     */
    public void execute(Consumer<PlayerGameExecution> handler) {
        Objects.requireNonNull(handler, "handler");
        messages.tellLocal(self, ignored -> mutate(execution -> {
            handler.accept(execution);
            return null;
        }));
    }

    public PlayerBusinessResponse executeBusiness(
            PlayerCommand playerCommand,
            PlayerBusinessCommand<?> businessCommand
    ) {
        Objects.requireNonNull(playerCommand, "playerCommand");
        Objects.requireNonNull(businessCommand, "businessCommand");
        return mutate(execution -> {
            Object result = businessCommand.execute(execution);
            PlayerBusinessResponse response = PlayerBusinessResponse.success(playerCommand, result);
            activeMutation.completedResponses.add(response);
            return response;
        });
    }

    PlayerBusinessResponse commitBusinessResponse(PlayerBusinessResponse response) {
        Objects.requireNonNull(response, "response");
        if (response.playerId() != profile.playerId()) {
            throw new IllegalArgumentException("response playerId mismatch");
        }
        return mutate(ignored -> {
            activeMutation.completedResponses.add(response);
            return response;
        });
    }

    void attachMutationRepository(PlayerMutationRepository repository) {
        String ownerToken = UUID.randomUUID().toString();
        PlayerMutationRecord record = repository.acquireOwner(currentSnapshot(), ownerToken);
        if (record.snapshot().revision() != stateRevision) {
            throw new PlayerMutationConflictException(
                    profile.playerId(), stateRevision, record.snapshot().revision(),
                    PlayerMutationConflictReason.STALE_REVISION
            );
        }
        attachMutationRepository(repository, ownerToken);
    }

    void attachMutationRepository(PlayerMutationRepository repository, String ownerToken) {
        if (mutationRepository != null) {
            throw new IllegalStateException("player mutation repository already attached");
        }
        mutationRepository = Objects.requireNonNull(repository, "repository");
        mutationOwnerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        if (ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken must not be blank");
        }
    }

    void onFenced(PlayerAgentFenceListener listener) {
        fenceListener = Objects.requireNonNull(listener, "listener");
    }

    public boolean isFenced() {
        return fencingConflict != null;
    }

    void reclaimMutationOwner(String ownerToken, long authoritativeRevision) {
        Objects.requireNonNull(ownerToken, "ownerToken");
        if (ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken must not be blank");
        }
        if (stateRevision != authoritativeRevision) {
            throw new PlayerMutationConflictException(
                    profile.playerId(), stateRevision, authoritativeRevision,
                    stateRevision < authoritativeRevision
                            ? PlayerMutationConflictReason.STALE_REVISION
                            : PlayerMutationConflictReason.REVISION_GAP
            );
        }
        mutationOwnerToken = ownerToken;
    }

    void attachBusinessResponseSnapshots(LongFunction<PlayerBusinessResponseSnapshot> snapshots) {
        businessResponseSnapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    void replayPendingDomainEvents() {
        if (mutationRepository == null || domainEventPublisher == null) {
            return;
        }
        if (!mutationReplayQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            messages.tellLocal(self, ignored -> {
                try {
                    dispatchPendingEvents(mutationRepository.pendingEvents(profile.playerId()));
                } finally {
                    mutationReplayQueued.set(false);
                }
            });
        } catch (RuntimeException | Error error) {
            mutationReplayQueued.set(false);
            throw error;
        }
    }

    public void save(PlayerStateRepository repository, Consumer<PlayerStateSnapshot> callback) {
        save(repository, PlayerStateSaveCallback.onSaved(callback));
    }

    public void save(PlayerStateRepository repository, PlayerStateSaveCallback callback) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ignored -> {
            long beforeRevision = stateRevision;
            try {
                ensureNotFenced();
                PlayerStateSnapshot snapshot = nextSnapshot();
                persistSnapshot(repository, snapshot);
                callback.saved(snapshot);
            } catch (PlayerMutationConflictException conflict) {
                stateRevision = beforeRevision;
                fence(conflict);
                callback.failed(profile.playerId(), conflict);
                throw conflict;
            } catch (RuntimeException e) {
                stateRevision = beforeRevision;
                callback.failed(profile.playerId(), e);
                throw e;
            }
        });
    }

    PlayerStateSnapshot saveInCurrentMailbox(PlayerStateRepository repository) {
        Objects.requireNonNull(repository, "repository");
        com.commonbattle.actor.ActorContext.requireCurrent(self);
        ensureNotFenced();
        long beforeRevision = stateRevision;
        try {
            PlayerStateSnapshot snapshot = nextSnapshot();
            persistSnapshot(repository, snapshot);
            return snapshot;
        } catch (PlayerMutationConflictException conflict) {
            stateRevision = beforeRevision;
            fence(conflict);
            throw conflict;
        } catch (RuntimeException error) {
            stateRevision = beforeRevision;
            throw error;
        }
    }

    public void exportForMigration(Consumer<PlayerStateSnapshot> callback) {
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ignored -> callback.accept(nextSnapshot()));
    }

    PlayerStateSnapshot exportForMigrationInCurrentMailbox() {
        com.commonbattle.actor.ActorContext.requireCurrent(self);
        return nextSnapshot();
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
            com.commonbattle.actor.ActorContext.requireCurrent(self);
            PlayerStateSnapshot snapshot = nextSnapshot();
            repository.save(profile.playerId(), snapshot);
            callback.accept(snapshot);
        });
    }

    public ActorTimerHandle scheduleActivitySnapshotRefresh(
            ActorScheduleRegistry schedules,
            Duration initialDelay,
            Duration interval
    ) {
        Objects.requireNonNull(schedules, "schedules");
        return schedules.scheduleAtFixedRate(
                ActorScheduleKey.of("player.activity.refresh", profile.playerId()),
                self,
                initialDelay,
                interval,
                ignored -> execution().pushActivitySnapshot()
        );
    }

    public ActorTimerHandle scheduleGrowthStaminaRecovery(
            ActorScheduleRegistry schedules,
            Duration initialDelay,
            Duration interval
    ) {
        Objects.requireNonNull(schedules, "schedules");
        return schedules.scheduleAtFixedRate(
                ActorScheduleKey.of("player.growth.stamina", profile.playerId()),
                self,
                initialDelay,
                interval,
                ignored -> {
                    mutate(execution -> {
                        GrowthRecoveryResult result = execution.runtime().growthService()
                                .recoverStamina(profile.growth(), execution.activityAccess().now());
                        if (result.changed()) {
                            execution.pushGrowthSnapshot();
                        }
                        return null;
                    });
                }
        );
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
        com.commonbattle.actor.ActorContext.requireCurrent(self);
        PlayerGameRuntime runtime = runtimeResolver.apply(profile.playerId());
        PlayerPushPort executionPushes = activeMutation == null ? pushes : activeMutation.pushes();
        return new PlayerGameExecution(profile, runtime, activityAccess(), this::publishDomainEvent, executionPushes);
    }

    private void publishDomainEvent(PlayerDomainEvent event) {
        if (event.replayed() || (domainEventPublisher == null && domainEventListener == null)) {
            return;
        }
        if (activeMutation != null) {
            activeMutation.events.add(event);
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

    private ActorMailboxShopStockClient requireShopStocks() {
        if (shopStocks == null) {
            throw new IllegalStateException("shop stock async client is not available for this runtime");
        }
        return shopStocks;
    }

    private void deliverActivityEligibility(
            String activityId,
            ActivityEligibilityDecision decision,
            Consumer<AsyncActivityClaimResult> callback
    ) {
        AsyncActorCallback.deliverDecision(
                decision,
                ignored -> {
                },
                payload -> messages.tryTellLocal(self, ActorTaskCategory.RPC_CALLBACK,
                        context -> completeActivityClaimAfterEligibility(activityId, payload, callback)),
                () -> {
                },
                () -> AsyncActivityClaimResult.backpressured(activityId),
                callback
        );
    }

    private void deliverActivityEligibilityFailure(
            String activityId,
            Throwable error,
            Consumer<AsyncActivityClaimResult> callback
    ) {
        AsyncActorCallback.deliverDecision(
                error,
                ignored -> {
                },
                payload -> messages.tryTellLocal(self, ActorTaskCategory.RPC_CALLBACK,
                        context -> callback.accept(AsyncActivityClaimResult.checkFailed(activityId, payload))),
                () -> {
                },
                () -> AsyncActivityClaimResult.backpressured(activityId),
                callback
        );
    }

    private void deliverActivityEligibility(
            PendingAsyncCommand pending,
            PlayerCommand command,
            String activityId,
            ActivityEligibilityDecision decision,
            PlayerBusinessResultSink results
    ) {
        AsyncActorCallback.deliverDecision(
                decision,
                ignored -> {
                },
                payload -> messages.tryTellLocal(self, ActorTaskCategory.RPC_CALLBACK,
                        context -> completeActivityClaimAfterEligibility(pending, command, activityId, payload, results)),
                () -> {
                    asyncBusinessCommands.callbackDeliveryFailure(command.operation());
                    removePendingAsyncCommand(pending);
                },
                () -> AsyncActivityClaimResult.backpressured(activityId),
                result -> completeAsyncActivityClaimResult(pending, command, result, results)
        );
    }

    private void deliverActivityEligibilityFailure(
            PendingAsyncCommand pending,
            PlayerCommand command,
            String activityId,
            Throwable error,
            PlayerBusinessResultSink results
    ) {
        AsyncActorCallback.deliverDecision(
                error,
                ignored -> {
                },
                payload -> messages.tryTellLocal(self, ActorTaskCategory.RPC_CALLBACK,
                        context -> completeAsyncActivityClaimResult(
                                pending,
                                command,
                                AsyncActivityClaimResult.checkFailed(activityId, payload),
                                results
                        )),
                () -> {
                    asyncBusinessCommands.callbackDeliveryFailure(command.operation());
                    removePendingAsyncCommand(pending);
                },
                () -> AsyncActivityClaimResult.backpressured(activityId),
                result -> completeAsyncActivityClaimResult(pending, command, result, results)
        );
    }

    private void completeActivityClaimAfterEligibility(
            String activityId,
            ActivityEligibilityDecision decision,
            Consumer<AsyncActivityClaimResult> callback
    ) {
        if (!decision.allowed()) {
            callback.accept(AsyncActivityClaimResult.denied(activityId, decision.reason()));
            return;
        }
        try {
            mutate(execution -> {
                ActivityClaimResult result = execution.runtime().activityService()
                        .claim(profile.activities(), profile.bag(), execution.activityAccess(), activityId);
                execution.pushActivitySnapshot();
                execution.publishBagChanged(PlayerBusinessOperations.ACTIVITY_CLAIM, false, result.bagResult());
                execution.pushBagSnapshot();
                afterCommit(() -> callback.accept(AsyncActivityClaimResult.claimed(result)));
                return null;
            });
        } catch (RuntimeException e) {
            callback.accept(AsyncActivityClaimResult.claimFailed(activityId, e));
        }
    }

    private <R> R mutate(Function<PlayerGameExecution, R> operation) {
        com.commonbattle.actor.ActorContext.requireCurrent(self);
        Objects.requireNonNull(operation, "operation");
        ensureNotFenced();
        if (activeMutation != null) {
            return operation.apply(execution());
        }
        PlayerStateSnapshot before = currentSnapshot();
        MutationScope scope = new MutationScope();
        activeMutation = scope;
        boolean committed = false;
        try {
            R result = operation.apply(execution());
            List<CommittedDomainEvent> committedEvents = versionEvents(scope.events);
            long nextEventRevision = committedEvents.isEmpty()
                    ? domainEventRevision
                    : committedEvents.get(committedEvents.size() - 1).revision();
            if (mutationRepository != null) {
                PlayerStateSnapshot snapshot = snapshot(stateRevision + 1, nextEventRevision);
                List<PlayerDomainVersionedEvent> externalEvents = domainEventPublisher == null
                        ? List.of()
                        : committedEvents.stream().map(CommittedDomainEvent::versioned).toList();
                PlayerBusinessResponseSnapshot responses = scope.completedResponses.isEmpty()
                        ? null
                        : mergedBusinessResponses(scope.completedResponses);
                PlayerMutationRecord record = responses == null
                        ? mutationRepository.commitOwned(mutationOwnerToken, snapshot, externalEvents)
                        : mutationRepository.commitOwned(mutationOwnerToken, snapshot, externalEvents, responses);
                stateRevision = snapshot.revision();
                domainEventRevision = nextEventRevision;
                committed = true;
                dispatchPendingEvents(record.pendingEvents());
            } else {
                domainEventRevision = nextEventRevision;
                committed = true;
                for (CommittedDomainEvent event : committedEvents) {
                    if (domainEventPublisher != null) {
                        domainEventPublisher.publish(event.versioned());
                    }
                }
            }
            notifyDomainListeners(committedEvents);
            scope.flushPushes();
            scope.flushAfterCommit();
            return result;
        } catch (PlayerMutationConflictException conflict) {
            fence(conflict);
            throw conflict;
        } finally {
            activeMutation = null;
            if (!committed) {
                profile.restoreFrom(before);
                stateRevision = before.revision();
                domainEventRevision = before.eventRevision();
            }
        }
    }

    private void ensureNotFenced() {
        if (fencingConflict != null) {
            throw new PlayerAgentFencedException(profile.playerId());
        }
    }

    private void fence(PlayerMutationConflictException conflict) {
        if (fencingConflict != null) {
            return;
        }
        fencingConflict = conflict;
        fenceListener.fenced(this, conflict);
    }

    private List<CommittedDomainEvent> versionEvents(List<PlayerDomainEvent> events) {
        List<CommittedDomainEvent> committed = new ArrayList<>(events.size());
        long revision = domainEventRevision;
        for (PlayerDomainEvent event : events) {
            revision++;
            committed.add(new CommittedDomainEvent(
                    revision,
                    event,
                    PlayerDomainVersionedEvent.from(profile.playerId(), revision, clock.instant(), event)
            ));
        }
        return committed;
    }

    private void dispatchPendingEvents(List<PlayerDomainVersionedEvent> pendingEvents) {
        if (domainEventPublisher == null) {
            return;
        }
        for (PlayerDomainVersionedEvent event : pendingEvents) {
            try {
                domainEventPublisher.publish(event);
                if (mutationRepository != null) {
                    mutationRepository.markEventEnqueued(profile.playerId(), event.revision());
                }
            } catch (RuntimeException | Error ignored) {
                // 事务记录保留事件；后续邮箱消息或 Agent 重建时继续移交可靠 outbox。
                return;
            }
        }
    }

    private void notifyDomainListeners(List<CommittedDomainEvent> events) {
        if (domainEventListener == null) {
            return;
        }
        for (CommittedDomainEvent event : events) {
            domainEventListener.onEvent(profile, event.revision(), event.event());
        }
    }

    private void afterCommit(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (activeMutation == null) {
            action.run();
            return;
        }
        activeMutation.afterCommit.add(action);
    }

    private void completeBusinessResponse(
            PlayerCommand command,
            PlayerBusinessResponse response,
            PlayerBusinessResultSink results
    ) {
        if (activeMutation != null && mutationRepository != null) {
            activeMutation.completedResponses.add(response);
        }
        afterCommit(() -> results.completed(command, response));
    }

    private PlayerBusinessResponseSnapshot mergedBusinessResponses(List<PlayerBusinessResponse> additions) {
        PlayerBusinessResponseSnapshot current = Objects.requireNonNull(
                businessResponseSnapshots.apply(profile.playerId()),
                "businessResponseSnapshot"
        );
        Map<String, PlayerBusinessResponse> merged = new java.util.LinkedHashMap<>();
        for (PlayerBusinessResponse response : current.responses()) {
            merged.put(responseKey(response), response);
        }
        for (PlayerBusinessResponse response : additions) {
            merged.put(responseKey(response), response);
        }
        return new PlayerBusinessResponseSnapshot(profile.playerId(), List.copyOf(merged.values()));
    }

    private static String responseKey(PlayerBusinessResponse response) {
        return response.sessionId() + '\u0000' + response.sessionEpoch() + '\u0000' + response.sequence();
    }

    private PlayerStateSnapshot currentSnapshot() {
        return profile.snapshot(stateRevision, domainEventRevision, clock.instant())
                .withCommandSequencer(commandSequencerSnapshot());
    }

    private void persistSnapshot(PlayerStateRepository repository, PlayerStateSnapshot snapshot) {
        if (mutationRepository != null) {
            mutationRepository.commitOwned(mutationOwnerToken, snapshot, List.of());
            return;
        }
        repository.save(profile.playerId(), snapshot);
    }

    private PlayerStateSnapshot snapshot(long revision, long eventRevision) {
        return profile.snapshot(revision, eventRevision, clock.instant())
                .withCommandSequencer(commandSequencerSnapshot());
    }

    private PlayerCommandSequencerSnapshot commandSequencerSnapshot() {
        return Objects.requireNonNull(
                commandSequencerSnapshots.apply(profile.playerId()),
                "commandSequencerSnapshot"
        );
    }

    private void completeActivityClaimAfterEligibility(
            PendingAsyncCommand pending,
            PlayerCommand command,
            String activityId,
            ActivityEligibilityDecision decision,
            PlayerBusinessResultSink results
    ) {
        if (!beginAsyncCommandCompletion(pending, command, results)) {
            return;
        }
        if (!decision.allowed()) {
            recordAndCompleteAsyncActivityClaim(command, AsyncActivityClaimResult.denied(activityId, decision.reason()), results);
            return;
        }
        try {
            mutate(execution -> {
                ActivityClaimResult result = execution.runtime().activityService()
                        .claim(profile.activities(), profile.bag(), execution.activityAccess(), activityId);
                execution.pushActivitySnapshot();
                execution.publishBagChanged(PlayerBusinessOperations.ACTIVITY_CLAIM, false, result.bagResult());
                execution.pushBagSnapshot();
                recordAndCompleteAsyncActivityClaim(command, AsyncActivityClaimResult.claimed(result), results);
                return null;
            });
        } catch (RuntimeException e) {
            recordAndCompleteAsyncActivityClaim(command, AsyncActivityClaimResult.claimFailed(activityId, e), results);
        }
    }

    private PendingAsyncCommand startPendingAsyncCommand(
            PlayerCommand command,
            PlayerBusinessResultSink results,
            boolean shopPurchase
    ) {
        PendingAsyncCommand pending = PendingAsyncCommand.from(command);
        int totalPending = pendingAsyncCommands.size();
        int operationPending = pendingAsyncCommandsByOperation(command.operation());
        if (!asyncCommandPolicy.allows(command.operation(), totalPending, operationPending)) {
            rejectAsyncCommand(command, results, asyncCommandPolicy.maxPendingFor(command.operation()));
            return null;
        }
        pendingAsyncCommands.put(pending, new PendingAsyncCommandState(command, results, shopPurchase));
        asyncBusinessCommands.pendingCommandStarted(command.operation());
        if (shopPurchase) {
            asyncShopPurchases.pendingCommandStarted();
        }
        return pending;
    }

    private int pendingAsyncCommandsByOperation(String operation) {
        int count = 0;
        for (PendingAsyncCommandState state : pendingAsyncCommands.values()) {
            if (state.command().operation().equals(operation)) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Integer> pendingAsyncCommandsByOperationSnapshot() {
        Map<String, Integer> counts = new HashMap<>();
        for (PendingAsyncCommandState state : pendingAsyncCommands.values()) {
            counts.merge(state.command().operation(), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private void rejectAsyncCommand(PlayerCommand command, PlayerBusinessResultSink results, int maxPending) {
        mutate(execution -> {
            AsyncBusinessCommandRejectedException error =
                    new AsyncBusinessCommandRejectedException(command.operation(), maxPending);
            asyncBusinessCommands.commandFailed(command.operation());
            PlayerBusinessResponse response = PlayerBusinessResponse.failure(command, error);
            recordAsyncCommandAudit(
                    command,
                    PlayerCommandAuditOutcome.ASYNC_FAILED,
                    execution.runtime().version(),
                    response.code(),
                    response.message()
            );
            completeBusinessResponse(command, response, results);
            return null;
        });
    }

    private void completeAsyncActivityClaimResult(
            PendingAsyncCommand pending,
            PlayerCommand command,
            AsyncActivityClaimResult result,
            PlayerBusinessResultSink results
    ) {
        if (!beginAsyncCommandCompletion(pending, command, results)) {
            return;
        }
        recordAndCompleteAsyncActivityClaim(command, result, results);
    }

    private boolean beginAsyncCommandCompletion(
            PendingAsyncCommand pending,
            PlayerCommand command,
            PlayerBusinessResultSink results
    ) {
        PendingAsyncCommandState state = removePendingAsyncCommand(pending);
        if (results.canComplete(command)) {
            return true;
        }
        asyncBusinessCommands.lateCallback(command.operation());
        if (state != null && state.shopPurchase()) {
            asyncShopPurchases.lateCallback();
        }
        return false;
    }

    private void recordAndCompleteAsyncActivityClaim(
            PlayerCommand command,
            AsyncActivityClaimResult result,
            PlayerBusinessResultSink results
    ) {
        if (activeMutation == null) {
            mutate(ignored -> {
                recordAndCompleteAsyncActivityClaim(command, result, results);
                return null;
            });
            return;
        }
        PlayerGameExecution execution = execution();
        recordAsyncCommandAudit(
                command,
                PlayerCommandAuditOutcome.ASYNC_COMPLETED,
                execution.runtime().version(),
                result.businessResultCode(),
                result.reason()
        );
        asyncBusinessCommands.commandCompleted(command.operation(), result.businessResultRejected());
        completeBusinessResponse(command, PlayerBusinessResponse.success(command, result), results);
    }

    private void releaseReservedStock(String reservationId, String sku, int quantity) {
        requireShopStocks().release(
                reservationId,
                sku,
                quantity,
                new ActorShopStockCallback<>() {
                    @Override
                    public void success(com.commonbattle.actor.ActorContext context, ShopStockReleaseResponse response) {
                        asyncShopPurchases.releasedReservation();
                    }

                    @Override
                    public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                        asyncShopPurchases.releaseFailure();
                    }
                },
                ignored -> asyncShopPurchases.callbackDeliveryFailure()
        );
    }

    private void completeShopPurchase(
            PlayerCommand command,
            PlayerGameExecution execution,
            ShopPurchaseResult result,
            PlayerBusinessResultSink results
    ) {
        if (result.success()) {
            asyncBusinessCommands.commandCompleted(command.operation(), false);
            asyncShopPurchases.completedPurchase();
            execution.publish(ShopItemPurchasedEvent.from(profile.playerId(), result));
            execution.publishBagChanged(PlayerBusinessOperations.SHOP_BUY, result.replayed(), result.cost(), result.reward());
            if (!result.replayed()) {
                execution.pushShopSnapshot();
                execution.pushBagSnapshot();
            }
        } else {
            asyncBusinessCommands.commandCompleted(command.operation(), true);
            asyncShopPurchases.rejectedPurchase();
        }
        recordAsyncCommandAudit(
                command,
                PlayerCommandAuditOutcome.ASYNC_COMPLETED,
                execution.runtime().version(),
                result.success() ? PlayerBusinessResponse.OK : result.businessResultCode(),
                ""
        );
        completeBusinessResponse(command, PlayerBusinessResponse.success(command, result), results);
    }

    private void recordAsyncCommandAudit(
            PlayerCommand command,
            PlayerCommandAuditOutcome outcome,
            long configVersion,
            String resultCode,
            String reason
    ) {
        asyncCommandAuditSink.record(new PlayerCommandAuditRecord(
                command.playerId(),
                command.sessionId(),
                command.sessionEpoch(),
                command.sequence(),
                command.operation(),
                PlayerCommandStatus.ACCEPTED,
                outcome,
                configVersion,
                Duration.ZERO,
                clock.instant(),
                resultCode,
                reason
        ));
    }

    private void failAsyncShopPurchase(
            PendingAsyncCommand pending,
            PlayerCommand command,
            PlayerBusinessResultSink results,
            Throwable error
    ) {
        removePendingAsyncCommand(pending);
        if (!results.canComplete(command)) {
            asyncBusinessCommands.lateCallback(command.operation());
            asyncShopPurchases.lateCallback();
            return;
        }
        mutate(failureExecution -> {
            asyncBusinessCommands.commandFailed(command.operation());
            asyncShopPurchases.rpcFailure();
            PlayerBusinessResponse response = PlayerBusinessResponse.failure(command, error);
            recordAsyncCommandAudit(
                    command,
                    PlayerCommandAuditOutcome.ASYNC_FAILED,
                    failureExecution.runtime().version(),
                    response.code(),
                    response.message()
            );
            completeBusinessResponse(command, response, results);
            return null;
        });
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
        cleanupAbandonedPendingAsyncCommands();
        if (!pendingAsyncCommands.isEmpty()) {
            throw new PendingAsyncPlayerCommandException(
                    profile.playerId(),
                    pendingAsyncCommands.size(),
                    pendingAsyncCommandsByOperationSnapshot()
            );
        }
        stateRevision++;
        PlayerCommandSequencerSnapshot commandSnapshot = commandSequencerSnapshot();
        return profile.snapshot(stateRevision, domainEventRevision, clock.instant())
                .withCommandSequencer(commandSnapshot);
    }

    private void cleanupAbandonedPendingAsyncCommands() {
        Iterator<PendingAsyncCommandState> iterator = pendingAsyncCommands.values().iterator();
        while (iterator.hasNext()) {
            PendingAsyncCommandState state = iterator.next();
            if (!state.results().canComplete(state.command())) {
                iterator.remove();
                asyncBusinessCommands.pendingCommandFinished(state.command().operation());
                asyncBusinessCommands.abandonedPendingCommand(state.command().operation());
                if (state.shopPurchase()) {
                    asyncShopPurchases.pendingCommandFinished();
                    asyncShopPurchases.abandonedPendingCommand();
                }
            }
        }
    }

    private PendingAsyncCommandState removePendingAsyncCommand(PendingAsyncCommand pending) {
        PendingAsyncCommandState state = pendingAsyncCommands.remove(pending);
        if (state != null) {
            asyncBusinessCommands.pendingCommandFinished(state.command().operation());
            if (state.shopPurchase()) {
                asyncShopPurchases.pendingCommandFinished();
            }
        }
        return state;
    }

    private final class MutationScope {
        private final List<PlayerDomainEvent> events = new ArrayList<>();
        private final List<PendingPush> pendingPushes = new ArrayList<>();
        private final List<Runnable> afterCommit = new ArrayList<>();
        private final List<PlayerBusinessResponse> completedResponses = new ArrayList<>();

        private PlayerPushPort pushes() {
            return (recipients, topic, payload) -> {
                pendingPushes.add(new PendingPush(recipients, topic, payload));
                return com.commonbattle.game.session.PlayerOutboundDeliveryResult.empty();
            };
        }

        private void flushPushes() {
            for (PendingPush pending : pendingPushes) {
                pushes.push(pending.recipients(), pending.topic(), pending.payload());
            }
        }

        private void flushAfterCommit() {
            afterCommit.forEach(Runnable::run);
        }
    }

    private record PendingPush(Set<Long> recipients, String topic, Object payload) {
        private PendingPush {
            recipients = Set.copyOf(recipients);
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(payload, "payload");
        }
    }

    private record CommittedDomainEvent(
            long revision,
            PlayerDomainEvent event,
            PlayerDomainVersionedEvent versioned
    ) {
    }

    private record PendingAsyncCommand(String sessionId, long sessionEpoch, long sequence, String operation) {
        private static PendingAsyncCommand from(PlayerCommand command) {
            return new PendingAsyncCommand(
                    command.sessionId(),
                    command.sessionEpoch(),
                    command.sequence(),
                    command.operation()
            );
        }
    }

    private record PendingAsyncCommandState(PlayerCommand command, PlayerBusinessResultSink results, boolean shopPurchase) {
        private PendingAsyncCommandState {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(results, "results");
        }
    }
}
