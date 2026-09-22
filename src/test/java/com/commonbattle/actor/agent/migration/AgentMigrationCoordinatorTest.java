package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentMigrationCoordinatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void coordinatorMovesDirectoryAndRestoresStateOnTarget() {
        Harness harness = new Harness(true);
        AgentIdentity player = AgentIdentity.player(10001L);
        AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
        harness.sourceLifecycles.activate(player, "player-10001");
        harness.sourceExecutor.runNext();

        harness.coordinator.migrate(player, harness.targetLocation(player), (identity, target, context) ->
                new AgentMigrationSnapshot("player.snapshot.v1", "hp=100".getBytes(StandardCharsets.UTF_8)),
                result::set
        );
        harness.sourceExecutor.runNext();

        assertEquals(harness.targetGame.id(), harness.agents.locate(player).orElseThrow().serviceId());
        assertEquals(AgentLifecycleState.MIGRATED, harness.sourceLifecycles.record(player).orElseThrow().state());
        assertEquals(AgentLifecycleState.ACTIVE, harness.targetLifecycles.record(player).orElseThrow().state());
        assertEquals("player.snapshot.v1:hp=100", harness.restored.get());
        assertEquals(AgentMigrationResultStatus.TARGET_ACCEPTED, result.get().status());
        assertEquals(new AgentMigrationCoordinatorStats(1, 1, 0, 1, 0, 0, 0, 0, 0, 0), harness.coordinator.stats());
    }

    @Test
    void coordinatorStoresPendingTaskBeforeSubmittingTargetAccept() {
        RecordingExecutor completionExecutor = new RecordingExecutor();
        Harness harness = new Harness(true, completionExecutor);
        AgentIdentity player = AgentIdentity.player(10001L);
        harness.sourceLifecycles.activate(player, "player-10001");
        harness.sourceExecutor.runNext();

        harness.coordinator.migrate(player, harness.targetLocation(player), (identity, target, context) ->
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1})
        );
        harness.sourceExecutor.runNext();

        assertEquals(harness.targetGame.id(), harness.agents.locate(player).orElseThrow().serviceId());
        assertEquals(1, harness.store.pendingTasks().size());
        assertEquals(1, completionExecutor.pending());
    }

    @Test
    void coordinatorSendsTaskIdToTargetAcceptRequest() {
        CapturingAcceptClient client = new CapturingAcceptClient();
        Harness harness = new Harness(false, Runnable::run, client);
        AgentIdentity player = AgentIdentity.player(10001L);
        AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
        harness.sourceLifecycles.activate(player, "player-10001");
        harness.sourceExecutor.runNext();

        harness.coordinator.migrate(
                player,
                harness.targetLocation(player),
                (identity, target, context) -> new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                result::set
        );
        harness.sourceExecutor.runNext();

        assertEquals(AgentMigrationResultStatus.TARGET_ACCEPTED, result.get().status());
        assertEquals(1, harness.store.stats(CLOCK.instant()).totalTasks());
        assertEquals("player:10001|game:r1:game-1|game:r1:game-2|1788220800000", client.request.taskId());
    }

    @Test
    void recoverySendsTaskIdToTargetAcceptRequest() {
        CapturingAcceptClient client = new CapturingAcceptClient();
        Harness harness = new Harness(false, Runnable::run, client);
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentMigrationTask task = new AgentMigrationTask(
                "migration-recovery-10001",
                player,
                new AgentLocation(harness.sourceGame.id(), new ActorRef("player-10001")),
                harness.targetLocation(player),
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                AgentMigrationTaskStatus.MOVED,
                "",
                CLOCK.instant()
        );
        harness.store.save(task);
        harness.agents.claim(player, task.target());
        AgentMigrationRecoveryService recovery = new AgentMigrationRecoveryService(
                harness.store,
                harness.agents,
                harness.sourceLifecycles,
                client,
                Runnable::run,
                AgentMigrationPolicy.defaults(),
                CLOCK
        );

        recovery.recoverPending(ignored -> {
        });

        assertEquals("migration-recovery-10001", client.request.taskId());
    }

    @Test
    void coordinatorStoresPreparedTaskBeforeDirectoryMove() {
        RecordingExecutor sourceExecutor = new RecordingExecutor();
        ActorSystem sourceActors = new ActorSystem(sourceExecutor, 64);
        InMemoryAgentMigrationTaskStore store = new InMemoryAgentMigrationTaskStore();
        AgentIdentity player = AgentIdentity.player(10003L);
        ServiceId sourceService = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceId targetService = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
        AgentLocation target = new AgentLocation(targetService, new ActorRef("player-10003"));
        AtomicReference<AgentMigrationTaskStatus> statusSeenInMove = new AtomicReference<>();
        ProbingDirectory directory = new ProbingDirectory(() ->
                statusSeenInMove.set(store.pendingTasks().getFirst().status())
        );
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(sourceService, sourceActors, directory, CLOCK);
        AgentMigrationCoordinator coordinator = new AgentMigrationCoordinator(
                lifecycles,
                directory,
                (targetServiceId, request) -> AgentMigrationAcceptResponse.success(),
                new RecordingExecutor(),
                AgentMigrationPolicy.defaults(),
                store,
                AgentMigrationTaskIdGenerator.defaultGenerator(),
                CLOCK
        );
        lifecycles.activate(player, "player-10003");
        sourceExecutor.runNext();

        coordinator.migrate(player, target, (identity, next, context) ->
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1})
        );
        sourceExecutor.runNext();

        assertEquals(AgentMigrationTaskStatus.PREPARED, statusSeenInMove.get());
        assertEquals(AgentMigrationTaskStatus.MOVED, store.pendingTasks().getFirst().status());
    }

    @Test
    void coordinatorRollsDirectoryBackWhenTargetCannotAccept() {
        Harness harness = new Harness(false);
        AgentIdentity player = AgentIdentity.player(10001L);
        AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
        harness.sourceLifecycles.activate(player, "player-10001");
        harness.sourceExecutor.runNext();

        harness.coordinator.migrate(player, harness.targetLocation(player), (identity, target, context) ->
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                result::set
        );
        harness.sourceExecutor.runNext();
        LifecycleAwareAgentRouter sourceRouter = new LifecycleAwareAgentRouter(
                harness.sourceLifecycles,
                new DefaultAgentMessagePort(harness.sourceActors, new NoopRpcGateway())
        );

        assertEquals(harness.sourceGame.id(), harness.agents.locate(player).orElseThrow().serviceId());
        assertEquals(AgentLifecycleState.ACTIVE, harness.sourceLifecycles.record(player).orElseThrow().state());
        assertEquals(AgentRouteType.LOCAL, sourceRouter.resolve(player).type());
        assertEquals(AgentMigrationResultStatus.TARGET_FAILED_ROLLED_BACK, result.get().status());
        assertEquals(new AgentMigrationCoordinatorStats(1, 1, 0, 0, 0, 1, 0, 0, 1, 0), harness.coordinator.stats());
    }

    @Test
    void coordinatorRollsDirectoryBackWhenCompletionExecutorRejects() {
        Harness harness = new Harness(true, command -> {
            throw new RejectedExecutionException("migration executor full");
        });
        AgentIdentity player = AgentIdentity.player(10001L);
        AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
        harness.sourceLifecycles.activate(player, "player-10001");
        harness.sourceExecutor.runNext();

        harness.coordinator.migrate(player, harness.targetLocation(player), (identity, target, context) ->
                new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                result::set
        );
        harness.sourceExecutor.runNext();

        assertEquals(harness.sourceGame.id(), harness.agents.locate(player).orElseThrow().serviceId());
        assertEquals(AgentLifecycleState.ACTIVE, harness.sourceLifecycles.record(player).orElseThrow().state());
        assertEquals(AgentMigrationResultStatus.COMPLETION_REJECTED_ROLLED_BACK, result.get().status());
        assertEquals(new AgentMigrationCoordinatorStats(1, 1, 0, 0, 0, 0, 0, 1, 1, 0), harness.coordinator.stats());
    }

    @Test
    void coordinatorRetriesTransientTargetFailure() {
        Harness harness = new Harness(true, Runnable::run, new FlakyClient());
        AgentIdentity player = AgentIdentity.player(10001L);
        AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
        harness.sourceLifecycles.activate(player, "player-10001");
        harness.sourceExecutor.runNext();
        AgentMigrationCoordinator coordinator = harness.coordinatorWithPolicy(new AgentMigrationPolicy(2));

        coordinator.migrate(
                player,
                harness.targetLocation(player),
                (identity, target, context) -> new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                result::set
        );
        harness.sourceExecutor.runNext();

        assertEquals(AgentMigrationResultStatus.TARGET_ACCEPTED, result.get().status());
        assertEquals(new AgentMigrationCoordinatorStats(1, 1, 0, 1, 0, 0, 1, 0, 0, 0),
                coordinator.stats());
    }

    @Test
    void coordinatorDoesNotRetryExplicitTargetRejection() {
        CountingRejectClient client = new CountingRejectClient();
        Harness harness = new Harness(true, Runnable::run, client);
        AgentIdentity player = AgentIdentity.player(10001L);
        AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
        harness.sourceLifecycles.activate(player, "player-10001");
        harness.sourceExecutor.runNext();
        AgentMigrationCoordinator coordinator = harness.coordinatorWithPolicy(new AgentMigrationPolicy(3));

        coordinator.migrate(
                player,
                harness.targetLocation(player),
                (identity, target, context) -> new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                result::set
        );
        harness.sourceExecutor.runNext();

        assertEquals(1, client.calls);
        assertEquals(AgentMigrationResultStatus.TARGET_REJECTED_ROLLED_BACK, result.get().status());
    }

    @Test
    void coordinatorCallsSourceHookAfterMoveAndRollbackRestore() {
        RecordingSourceHook hook = new RecordingSourceHook();
        Harness harness = new Harness(false, Runnable::run, null, hook);
        AgentIdentity player = AgentIdentity.player(10001L);
        AtomicReference<AgentMigrationResult> result = new AtomicReference<>();
        harness.sourceLifecycles.activate(player, "player-10001");
        harness.sourceExecutor.runNext();

        harness.coordinator.migrate(
                player,
                harness.targetLocation(player),
                (identity, target, context) -> new AgentMigrationSnapshot("player.snapshot.v1", new byte[]{1}),
                result::set
        );
        harness.sourceExecutor.runNext();

        assertEquals(1, hook.sourceMoved);
        assertEquals(1, hook.rollbackRestored);
        assertEquals(AgentMigrationResultStatus.TARGET_FAILED_ROLLED_BACK, result.get().status());
    }

    private static final class Harness {
        private final InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        private final LocalClusterTransport transport = new LocalClusterTransport();
        private final ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        private final ServiceDescriptor sourceGame = descriptor("game-1", 9001);
        private final ServiceDescriptor targetGame = descriptor("game-2", 9002);
        private final RecordingExecutor sourceExecutor = new RecordingExecutor();
        private final ActorSystem sourceActors = new ActorSystem(sourceExecutor, 64);
        private final ActorSystem targetActors = new ActorSystem(Runnable::run, 64);
        private final InMemoryAgentDirectory agents = new InMemoryAgentDirectory();
        private final InMemoryAgentMigrationTaskStore store = new InMemoryAgentMigrationTaskStore();
        private final AgentLifecycleManager sourceLifecycles =
                new AgentLifecycleManager(sourceGame.id(), sourceActors, agents, CLOCK);
        private final AgentLifecycleManager targetLifecycles =
                new AgentLifecycleManager(targetGame.id(), targetActors, agents, CLOCK);
        private final AtomicReference<String> restored = new AtomicReference<>();
        private final AgentMigrationCoordinator coordinator;
        private final AgentMigrationClient client;

        private Harness(boolean bindTargetEndpoint) {
            this(bindTargetEndpoint, Runnable::run);
        }

        private Harness(boolean bindTargetEndpoint, Executor completionExecutor) {
            this(bindTargetEndpoint, completionExecutor, null);
        }

        private Harness(
                boolean bindTargetEndpoint,
                Executor completionExecutor,
                AgentMigrationClient overrideClient
        ) {
            this(bindTargetEndpoint, completionExecutor, overrideClient, AgentMigrationSourceHook.noop());
        }

        private Harness(
                boolean bindTargetEndpoint,
                Executor completionExecutor,
                AgentMigrationClient overrideClient,
                AgentMigrationSourceHook sourceHook
        ) {
            registry.register(sourceGame);
            registry.register(targetGame);
            ClusterDirectory sourceDirectory = new ClusterDirectory(registry);
            sourceDirectory.watch(ServiceKind.GAME);
            ClusterDirectory targetDirectory = new ClusterDirectory(registry);
            targetDirectory.watch(ServiceKind.GAME);
            ClusterRpcGateway targetGateway = new ClusterRpcGateway(targetGame, targetDirectory, topology, transport);
            if (bindTargetEndpoint) {
                new AgentMigrationTargetEndpoint(targetLifecycles, (request, context) ->
                        restored.set(request.stateType() + ":"
                                + new String(request.stateBytes(), StandardCharsets.UTF_8))
                ).bind(targetGateway);
            }
            ClusterRpcGateway sourceGateway = new ClusterRpcGateway(sourceGame, sourceDirectory, topology, transport);
            client = overrideClient == null ? new RemoteAgentMigrationClient(sourceGateway) : overrideClient;
            coordinator = new AgentMigrationCoordinator(
                    sourceLifecycles,
                    agents,
                    client,
                    completionExecutor,
                    AgentMigrationPolicy.defaults(),
                    store,
                    AgentMigrationTaskIdGenerator.defaultGenerator(),
                    CLOCK,
                    sourceHook
            );
        }

        private AgentMigrationCoordinator coordinatorWithPolicy(AgentMigrationPolicy policy) {
            return new AgentMigrationCoordinator(
                    sourceLifecycles,
                    agents,
                    client,
                    Runnable::run,
                    policy,
                    store,
                    AgentMigrationTaskIdGenerator.defaultGenerator(),
                    CLOCK
            );
        }

        private AgentLocation targetLocation(AgentIdentity identity) {
            return new AgentLocation(targetGame.id(), new ActorRef(identity.type() + "-" + identity.key()));
        }
    }

    private static ServiceDescriptor descriptor(String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(AgentMigrationOperations.ACCEPT),
                Map.of()
        );
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.removeFirst().run();
        }

        int pending() {
            return commands.size();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static final class ProbingDirectory implements com.commonbattle.actor.agent.AgentDirectory {
        private final InMemoryAgentDirectory delegate = new InMemoryAgentDirectory();
        private final Runnable beforeMove;

        private ProbingDirectory(Runnable beforeMove) {
            this.beforeMove = beforeMove;
        }

        @Override
        public boolean claim(AgentIdentity identity, AgentLocation location) {
            return delegate.claim(identity, location);
        }

        @Override
        public boolean move(AgentIdentity identity, AgentLocation expectedCurrent, AgentLocation next) {
            beforeMove.run();
            return delegate.move(identity, expectedCurrent, next);
        }

        @Override
        public void unbind(AgentIdentity identity, AgentLocation location) {
            delegate.unbind(identity, location);
        }

        @Override
        public Optional<AgentLocation> locate(AgentIdentity identity) {
            return delegate.locate(identity);
        }
    }

    private static final class FlakyClient implements AgentMigrationClient {
        private int calls;

        @Override
        public AgentMigrationAcceptResponse accept(ServiceId targetServiceId, AgentMigrationAcceptRequest request) {
            calls++;
            if (calls == 1) {
                throw new IllegalStateException("temporary unavailable");
            }
            return AgentMigrationAcceptResponse.success();
        }
    }

    private static final class CountingRejectClient implements AgentMigrationClient {
        private int calls;

        @Override
        public AgentMigrationAcceptResponse accept(ServiceId targetServiceId, AgentMigrationAcceptRequest request) {
            calls++;
            return AgentMigrationAcceptResponse.rejected("assigned target not ready");
        }
    }

    private static final class CapturingAcceptClient implements AgentMigrationClient {
        private AgentMigrationAcceptRequest request;

        @Override
        public AgentMigrationAcceptResponse accept(ServiceId targetServiceId, AgentMigrationAcceptRequest request) {
            this.request = request;
            return AgentMigrationAcceptResponse.success();
        }
    }

    private static final class RecordingSourceHook implements AgentMigrationSourceHook {
        private int sourceMoved;
        private int rollbackRestored;

        @Override
        public void sourceMoved(AgentMigrationTask task) {
            sourceMoved++;
        }

        @Override
        public void rollbackRestored(AgentMigrationTask task) {
            rollbackRestored++;
        }
    }
}
