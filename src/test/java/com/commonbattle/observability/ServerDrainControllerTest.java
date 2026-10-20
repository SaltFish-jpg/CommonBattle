package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigPublishStatus;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.game.chat.ChatPersistenceStats;
import com.commonbattle.game.chat.ChatRuntimeView;
import com.commonbattle.game.chat.ChatServiceStats;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.player.InMemoryPlayerStateRepository;
import com.commonbattle.game.player.PlayerAgentDrainService;
import com.commonbattle.game.player.PlayerGameAgentManager;
import com.commonbattle.game.player.PlayerStateRepository;
import com.commonbattle.game.player.PlayerStateSnapshot;
import com.commonbattle.game.player.NettyPlayerGatewayStats;
import com.commonbattle.game.player.PlayerGatewayView;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.runtime.DrainableComponent;
import com.commonbattle.runtime.DrainPhase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerDrainControllerTest {
    @Test
    void idleRuntimeDrainsImmediately() throws InterruptedException {
        MutableClock clock = new MutableClock();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        ServerDrainController controller = new ServerDrainController(probe(clock, outbox), clock);

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10)));

        assertTrue(result.drained());
        assertEquals(0, result.elapsed().toMillis());
    }

    @Test
    void beginDrainClosesRegisteredIngressBeforeWaiting() throws InterruptedException {
        MutableClock clock = new MutableClock();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        RecordingDrainable drainable = new RecordingDrainable();
        ServerDrainController controller = new ServerDrainController(
                probe(clock, outbox),
                clock,
                duration -> {
                },
                java.util.List.of(drainable)
        );

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10)));

        assertTrue(result.drained());
        assertTrue(drainable.isDraining());
    }

    @Test
    void externalDrainIsPublishedBeforePropagationThenLocalIngressCloses() throws InterruptedException {
        MutableClock clock = new MutableClock();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        List<String> events = new ArrayList<>();
        PhasedRecordingDrainable external = new PhasedRecordingDrainable(
                DrainPhase.EXTERNAL_ADVERTISEMENT,
                "external",
                events
        );
        PhasedRecordingDrainable local = new PhasedRecordingDrainable(
                DrainPhase.LOCAL_INGRESS,
                "local",
                events
        );
        ServerDrainController controller = new ServerDrainController(
                probe(clock, outbox),
                clock,
                duration -> {
                    events.add("sleep:" + duration.toMillis());
                    clock.advance(duration);
                },
                List.of(local, external)
        );

        DrainResult result = controller.awaitDrained(new DrainConfig(
                Duration.ofSeconds(1),
                Duration.ofMillis(10),
                Duration.ofMillis(25)
        ));

        assertTrue(result.drained());
        assertEquals(25, result.elapsed().toMillis());
        assertEquals(List.of("external", "sleep:25", "local"), events);
        assertTrue(external.isDraining());
        assertTrue(local.isDraining());
    }

    @Test
    void returnsFailureWhenBeginDrainFails() throws InterruptedException {
        MutableClock clock = new MutableClock();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        ServerDrainController controller = new ServerDrainController(
                probe(clock, outbox),
                clock,
                duration -> {
                },
                List.of(new FailingDrainable())
        );

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10)));

        assertFalse(result.drained());
        assertEquals(0, result.elapsed().toMillis());
        assertEquals("begin_drain_failed:IllegalStateException", result.reason());
    }

    @Test
    void waitsUntilOutboxIsCleared() throws InterruptedException {
        MutableClock clock = new MutableClock();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        outbox.append(profileEvent(clock));
        ServerDrainController controller = new ServerDrainController(probe(clock, outbox), clock, duration -> {
            clock.advance(duration);
            outbox.markPublished(1);
        });

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10)));

        assertTrue(result.drained());
        assertEquals(10, result.elapsed().toMillis());
    }

    @Test
    void waitsForRunningMailboxTaskEvenWhenQueueIsEmpty() throws InterruptedException {
        MutableClock clock = new MutableClock();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        try (ActorSystem actors = new ActorSystem(1)) {
            actors.send(actors.actor("busy"), ignored -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completed.countDown();
                }
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            ServerDrainController controller = new ServerDrainController(
                    probe(clock, new InMemoryVersionedEventOutbox(clock), actors, new RuntimeHealthRegistry()),
                    clock,
                    duration -> {
                        clock.advance(duration);
                        release.countDown();
                        assertTrue(completed.await(1, TimeUnit.SECONDS));
                    }
            );

            DrainResult result = controller.awaitDrained(
                    new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10))
            );

            assertTrue(result.drained());
            assertTrue(result.elapsed().toMillis() >= 10);
            assertEquals(0, result.lastSnapshot().actorSystem().runningMailboxes());
        }
    }

    @Test
    void waitsUntilRemoteCallbackCompletesOnRequesterMailbox() throws InterruptedException {
        MutableClock clock = new MutableClock();
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        CapturingRpcGateway rpc = new CapturingRpcGateway();
        DefaultAgentMessagePort port = new DefaultAgentMessagePort(actors, rpc);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(port);
        AtomicBoolean callbackExecuted = new AtomicBoolean();
        port.callRemote(
                actors.actor("requester"),
                new RpcRequest<>("SCENE", "scene.enter", "payload", String.class),
                new com.commonbattle.actor.message.RemoteActorCallback<>() {
                    @Override
                    public void success(com.commonbattle.actor.ActorContext context, String response) {
                        callbackExecuted.set(true);
                    }

                    @Override
                    public void failure(
                            com.commonbattle.actor.ActorContext context,
                            com.commonbattle.actor.message.AgentDeliveryResult delivery,
                            Throwable error
                    ) {
                        throw new AssertionError(error);
                    }
                }
        );
        AtomicInteger polls = new AtomicInteger();
        ServerDrainController controller = new ServerDrainController(
                probe(clock, new InMemoryVersionedEventOutbox(clock), actors, registry),
                clock,
                duration -> {
                    clock.advance(duration);
                    if (polls.getAndIncrement() == 0) {
                        rpc.success("entered");
                    } else {
                        executor.runAll();
                    }
                }
        );

        DrainResult result = controller.awaitDrained(
                new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10))
        );

        assertTrue(result.drained());
        assertTrue(callbackExecuted.get());
        assertEquals(20, result.elapsed().toMillis());
        assertEquals(0, result.lastSnapshot().agentMessagePorts().currentInFlightRemoteCalls());
        port.close();
    }

    @Test
    void waitsUntilPlayerGatewayFlushesOutboundWrites() throws InterruptedException {
        MutableClock clock = new MutableClock();
        AtomicLong inFlightWrites = new AtomicLong(1);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register((PlayerGatewayView) () -> gatewayStats(inFlightWrites.get()));
        ServerDrainController controller = new ServerDrainController(
                probe(clock, new InMemoryVersionedEventOutbox(clock),
                        new ActorSystem(new InlineExecutor(), 64), registry),
                clock,
                duration -> {
                    clock.advance(duration);
                    inFlightWrites.set(0);
                }
        );

        DrainResult result = controller.awaitDrained(
                new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10))
        );

        assertTrue(result.drained());
        assertEquals(10, result.elapsed().toMillis());
        assertEquals(0, result.lastSnapshot().playerGateways().inFlightWrites());
    }

    @Test
    void waitsUntilChatPersistenceFinishesInFlightSaveAndPendingCommands() throws InterruptedException {
        MutableClock clock = new MutableClock();
        AtomicLong savingAgents = new AtomicLong(1);
        AtomicLong pendingCommands = new AtomicLong(2);
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register((ChatRuntimeView) () -> ChatServiceStats.empty().withPersistenceStats(
                new ChatPersistenceStats(
                        1, 0, savingAgents.get(), 0, pendingCommands.get(),
                        1, 0, 0, 0, 0, 0, 0
                )
        ));
        ServerDrainController controller = new ServerDrainController(
                probe(clock, new InMemoryVersionedEventOutbox(clock),
                        new ActorSystem(new InlineExecutor(), 64), registry),
                clock,
                duration -> {
                    clock.advance(duration);
                    savingAgents.set(0);
                    pendingCommands.set(0);
                }
        );

        DrainResult result = controller.awaitDrained(
                new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10))
        );

        assertTrue(result.drained());
        assertEquals(10, result.elapsed().toMillis());
        assertEquals(0, result.lastSnapshot().chatRuntimes().persistence().inFlightAgents());
        assertEquals(0, result.lastSnapshot().chatRuntimes().persistence().pendingCommands());
    }

    @Test
    void pollsDrainableComponentsBetweenHealthSnapshots() throws InterruptedException {
        MutableClock clock = new MutableClock();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        outbox.append(profileEvent(clock));
        PollingDrainable drainable = new PollingDrainable(outbox);
        ServerDrainController controller = new ServerDrainController(
                probe(clock, outbox),
                clock,
                duration -> clock.advance(duration),
                List.of(drainable)
        );

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10)));

        assertTrue(result.drained());
        assertEquals(10, result.elapsed().toMillis());
        assertEquals(1, drainable.polls);
    }


    @Test
    void returnsTimeoutWhenRuntimeNeverDrains() throws InterruptedException {
        MutableClock clock = new MutableClock();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        outbox.append(profileEvent(clock));
        ServerDrainController controller = new ServerDrainController(probe(clock, outbox), clock,
                duration -> clock.advance(duration));

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofMillis(30), Duration.ofMillis(10)));

        assertFalse(result.drained());
        assertEquals(30, result.elapsed().toMillis());
        assertEquals(1, result.lastSnapshot().outbox().pendingEvents());
    }

    @Test
    void waitsUntilPlayerAgentDrainCompletes() throws InterruptedException {
        MutableClock clock = new MutableClock();
        PlayerRuntimeFixture fixture = PlayerRuntimeFixture.create(clock, new InMemoryPlayerStateRepository());
        fixture.manager().getOrCreate(10001L);
        fixture.manager().getOrCreate(10002L);
        fixture.executor().runAll();
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(fixture.manager());
        registry.register(fixture.drain());
        ServerDrainController controller = new ServerDrainController(
                playerProbe(clock, fixture, registry),
                clock,
                duration -> {
                    clock.advance(duration);
                    fixture.executor().runAll();
                },
                List.of(fixture.drain())
        );

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10)));

        assertTrue(result.drained());
        assertEquals(10, result.elapsed().toMillis());
        assertEquals(2, result.lastSnapshot().playerAgents().drainCompleted());
        assertEquals(0, result.lastSnapshot().playerAgents().drainPending());
        assertEquals(0, fixture.manager().loadedAgents());
    }

    @Test
    void playerDrainSaveFailurePreventsSuccessfulDrain() throws InterruptedException {
        MutableClock clock = new MutableClock();
        PlayerRuntimeFixture fixture = PlayerRuntimeFixture.create(clock, new FailingPlayerStateRepository());
        fixture.manager().getOrCreate(10001L);
        fixture.executor().runAll();
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(fixture.manager());
        registry.register(fixture.drain());
        ServerDrainController controller = new ServerDrainController(
                playerProbe(clock, fixture, registry),
                clock,
                duration -> {
                    clock.advance(duration);
                    fixture.executor().runAll();
                },
                List.of(fixture.drain())
        );

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofMillis(30), Duration.ofMillis(10)));

        assertFalse(result.drained());
        assertEquals(30, result.elapsed().toMillis());
        assertEquals(1, result.lastSnapshot().playerAgents().drainFailedSaves());
        assertEquals(1, result.lastSnapshot().playerAgents().loadedAgents());
    }

    private static RuntimeHealthProbe probe(Clock clock, InMemoryVersionedEventOutbox outbox) {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        return probe(clock, outbox, actors, new RuntimeHealthRegistry());
    }

    private static RuntimeHealthProbe probe(
            Clock clock,
            InMemoryVersionedEventOutbox outbox,
            ActorSystem actors,
            RuntimeHealthRegistry registry
    ) {
        return new RuntimeHealthProbe(
                clock,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        clock
                ),
                outbox,
                new ClusterDirectory(new InMemoryServiceRegistry()),
                registry,
                RuntimeHealthPolicy.defaults()
        );
    }

    private static RuntimeHealthProbe playerProbe(
            Clock clock,
            PlayerRuntimeFixture fixture,
            RuntimeHealthRegistry registry
    ) {
        return new RuntimeHealthProbe(
                clock,
                fixture.actors(),
                fixture.lifecycles(),
                new InMemoryVersionedEventOutbox(clock),
                new ClusterDirectory(new InMemoryServiceRegistry()),
                registry,
                RuntimeHealthPolicy.defaults()
        );
    }

    private record PlayerRuntimeFixture(
            RecordingExecutor executor,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            PlayerGameAgentManager manager,
            PlayerAgentDrainService drain
    ) {
        private static PlayerRuntimeFixture create(Clock clock, PlayerStateRepository repository) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                    ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                    actors,
                    directory,
                    clock
            );
            InMemoryGameConfigRegistry configs = new InMemoryGameConfigRegistry(new GameConfigValidator(), clock);
            assertEquals(GameConfigPublishStatus.PUBLISHED,
                    configs.publish(ExampleGameConfigs.basic(7, clock.instant())).status());
            PlayerGameAgentManager manager = new PlayerGameAgentManager(
                    actors,
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    repository,
                    configs,
                    lifecycles,
                    clock,
                    Instant.parse("2026-08-01T00:00:00Z")
            );
            return new PlayerRuntimeFixture(executor, actors, lifecycles, manager, new PlayerAgentDrainService(manager));
        }
    }

    private static ProfileChangedEvent profileEvent(Clock clock) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.NAME),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        10,
                        AppearanceSummary.defaults(),
                        AllianceBrief.none(),
                        new FriendBrief(0, 0),
                        1,
                        clock.instant()
                )
        );
    }

    private static final class InlineExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runAll() {
            while (!commands.isEmpty()) {
                commands.removeFirst().run();
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static final class RecordingDrainable implements DrainableComponent {
        private final AtomicBoolean draining = new AtomicBoolean();

        @Override
        public void beginDrain() {
            draining.set(true);
        }

        @Override
        public void resumeAccepting() {
            draining.set(false);
        }

        @Override
        public boolean isDraining() {
            return draining.get();
        }
    }

    private static final class PhasedRecordingDrainable implements DrainableComponent {
        private final DrainPhase phase;
        private final String event;
        private final List<String> events;
        private final AtomicBoolean draining = new AtomicBoolean();

        private PhasedRecordingDrainable(DrainPhase phase, String event, List<String> events) {
            this.phase = phase;
            this.event = event;
            this.events = events;
        }

        @Override
        public DrainPhase phase() {
            return phase;
        }

        @Override
        public void beginDrain() {
            events.add(event);
            draining.set(true);
        }

        @Override
        public void resumeAccepting() {
            draining.set(false);
        }

        @Override
        public boolean isDraining() {
            return draining.get();
        }
    }

    private static NettyPlayerGatewayStats gatewayStats(long inFlightWrites) {
        return new NettyPlayerGatewayStats(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                inFlightWrites,
                Map.of()
        );
    }

    private static final class PollingDrainable implements DrainableComponent {
        private final InMemoryVersionedEventOutbox outbox;
        private int polls;

        private PollingDrainable(InMemoryVersionedEventOutbox outbox) {
            this.outbox = outbox;
        }

        @Override
        public void beginDrain() {
        }

        @Override
        public void onDrainPoll() {
            polls++;
            outbox.markPublished(1);
        }

        @Override
        public void resumeAccepting() {
        }

        @Override
        public boolean isDraining() {
            return true;
        }
    }

    private static final class FailingDrainable implements DrainableComponent {
        @Override
        public void beginDrain() {
            throw new IllegalStateException("registry unavailable");
        }

        @Override
        public void resumeAccepting() {
        }

        @Override
        public boolean isDraining() {
            return false;
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static final class CapturingRpcGateway implements RpcGateway {
        private RpcCallback<Object> callback;

        @Override
        @SuppressWarnings("unchecked")
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            this.callback = (RpcCallback<Object>) callback;
        }

        private void success(Object response) {
            callback.success(response);
        }
    }

    private static final class FailingPlayerStateRepository implements PlayerStateRepository {
        @Override
        public Optional<PlayerStateSnapshot> load(Long key) {
            return Optional.empty();
        }

        @Override
        public void save(Long key, PlayerStateSnapshot snapshot) {
            throw new IllegalStateException("player state store unavailable");
        }
    }
}
