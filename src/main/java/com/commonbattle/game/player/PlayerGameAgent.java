package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorTimerHandle;
import com.commonbattle.actor.ActorTimerService;
import com.commonbattle.actor.message.AgentMessagePort;
import com.commonbattle.game.activity.ActivityAccessContext;
import com.commonbattle.game.activity.ActivityClaimResult;
import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.config.GameConfigRegistry;
import com.commonbattle.game.config.GameConfigView;
import com.commonbattle.game.growth.GrowthResult;
import com.commonbattle.game.growth.GrowthService;

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
    private long stateRevision;

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
                0
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
                initialStateRevision
        );
    }

    private PlayerGameAgent(
            AgentMessagePort messages,
            ActorRef self,
            PlayerProfile profile,
            LongFunction<PlayerGameRuntime> runtimeResolver,
            Clock clock,
            Instant serverOpenTime,
            long initialStateRevision
    ) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.self = Objects.requireNonNull(self, "self");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serverOpenTime = Objects.requireNonNull(serverOpenTime, "serverOpenTime");
        loadRevision(initialStateRevision);
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
        execute(execution ->
                callback.accept(execution.runtime().growthService().useExpItems(profile.bag(), profile.growth(), count)));
    }

    /**
     * 在玩家邮箱中执行自定义业务逻辑，并在入口绑定本次消息使用的配置版本。
     */
    public void execute(Consumer<PlayerGameExecution> handler) {
        Objects.requireNonNull(handler, "handler");
        messages.tellLocal(self, ignored -> handler.accept(execution()));
    }

    public void save(PlayerStateRepository repository, Consumer<PlayerStateSnapshot> callback) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(callback, "callback");
        messages.tellLocal(self, ignored -> {
            PlayerStateSnapshot snapshot = nextSnapshot();
            repository.save(profile.playerId(), snapshot);
            callback.accept(snapshot);
        });
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

    private ActivityAccessContext activityAccess() {
        return new ActivityAccessContext(clock.instant(), serverOpenTime, profile);
    }

    private PlayerGameExecution execution() {
        PlayerGameRuntime runtime = runtimeResolver.apply(profile.playerId());
        return new PlayerGameExecution(profile, runtime, activityAccess());
    }

    private static LongFunction<PlayerGameRuntime> fixedRuntimeResolver(
            ActivityService activityService,
            GrowthService growthService
    ) {
        PlayerGameRuntime runtime = PlayerGameRuntime.fixed(activityService, growthService);
        return ignored -> runtime;
    }

    private static LongFunction<PlayerGameRuntime> runtimeResolver(GameConfigView configView) {
        Objects.requireNonNull(configView, "configView");
        return playerId -> PlayerGameRuntime.from(configView.resolve(playerId));
    }

    private PlayerStateSnapshot nextSnapshot() {
        // 存盘边界：在玩家邮箱内生成完整状态快照并递增 revision，避免跨模块状态被拆成不一致切片。
        stateRevision++;
        return profile.snapshot(stateRevision, clock.instant());
    }
}
