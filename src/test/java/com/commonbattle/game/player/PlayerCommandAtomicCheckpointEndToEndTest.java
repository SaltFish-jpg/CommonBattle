package com.commonbattle.game.player;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionControlledAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigPublishStatus;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandResult;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCommandAtomicCheckpointEndToEndTest {
    private static final long PLAYER_ID = 10001L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void restartRestoresCommandWatermarkAndOriginalResponseFromSameMutationRecord() {
        SerializedPlayerMutationRepository mutations = new SerializedPlayerMutationRepository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository(),
                new ProtostuffPlayerStateSnapshotSerializer()
        );
        MutationPlayerBusinessResponseStore responses = new MutationPlayerBusinessResponseStore(mutations);
        PlayerCheckpointRepository checkpoints = new PlayerCheckpointRepository(mutations, responses);
        Runtime first = runtime("game-1", checkpoints, responses);
        PlayerCommand command = command();

        PlayerCommandResult accepted = first.dispatcher.dispatch(command);
        first.executor.runAll();

        assertEquals(PlayerCommandStatus.ACCEPTED, accepted.status());
        PlayerMutationRecord committed = mutations.loadRecord(PLAYER_ID).orElseThrow();
        assertEquals(1, committed.snapshot().commandSequencer().watermarks().getFirst().lastSequence());
        assertEquals(1, committed.responses().responses().size());
        assertEquals(1, committed.snapshot().activities().progress().get("battle-win-1").value());

        Runtime rebuilt = runtime("game-2", checkpoints, responses);
        rebuilt.manager.getOrCreate(PLAYER_ID);
        rebuilt.executor.runAll();

        assertEquals(PlayerCommandStatus.DUPLICATE, rebuilt.sequencer.inspect(command));
        assertTrue(rebuilt.responses.cachedResponse(PlayerBusinessResponseQuery.from(command)).isPresent());
        assertEquals(1, rebuilt.manager.getOrCreate(PLAYER_ID)
                .profile().activities().progress("battle-win-1").value());
    }

    @Test
    void acceptedCommandFailurePersistsFailureResponseWithWatermark() {
        SerializedPlayerMutationRepository mutations = new SerializedPlayerMutationRepository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository(),
                new ProtostuffPlayerStateSnapshotSerializer()
        );
        MutationPlayerBusinessResponseStore responses = new MutationPlayerBusinessResponseStore(mutations);
        PlayerCheckpointRepository checkpoints = new PlayerCheckpointRepository(mutations, responses);
        Runtime runtime = runtime("game-failure", checkpoints, responses);
        ConsumeBagItemCommand payload = new ConsumeBagItemCommand("gold", 1);
        PlayerCommand command = new PlayerCommand(
                PLAYER_ID, "session-1", 1, 1, payload.operation(), payload
        );

        assertEquals(PlayerCommandStatus.ACCEPTED, runtime.dispatcher.dispatch(command).status());
        runtime.executor.runAll();

        PlayerMutationRecord committed = mutations.loadRecord(PLAYER_ID).orElseThrow();
        assertEquals(1, committed.snapshot().commandSequencer().watermarks().getFirst().lastSequence());
        assertEquals(PlayerBusinessResponseStatus.FAILED,
                committed.responses().responses().getFirst().status());
        PlayerBusinessResponseHub rebuilt = new PlayerBusinessResponseHub(
                PlayerBusinessResultSink.NOOP,
                CLOCK,
                PlayerBusinessResponseHub.DEFAULT_COMPLETED_RESPONSE_WINDOW,
                responses
        );
        assertEquals(PlayerBusinessResponseStatus.FAILED,
                rebuilt.cachedResponse(PlayerBusinessResponseQuery.from(command)).orElseThrow().status());
    }

    private static Runtime runtime(
            String node,
            PlayerCheckpointRepository repository,
            PlayerBusinessResponseStore responseStore
    ) {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", node);
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
        InMemoryGameConfigRegistry configs = new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
        assertEquals(GameConfigPublishStatus.PUBLISHED,
                configs.publish(ExampleGameConfigs.basic(1, CLOCK.instant())).status());
        PlayerCommandSequencer sequencer = new PlayerCommandSequencer();
        PlayerBusinessResponseHub responseHub = new PlayerBusinessResponseHub(
                PlayerBusinessResultSink.NOOP,
                CLOCK,
                PlayerBusinessResponseHub.DEFAULT_COMPLETED_RESPONSE_WINDOW,
                responseStore
        );
        PlayerGameAgentManager manager = new PlayerGameAgentManager(
                actors,
                messages,
                repository,
                configs,
                lifecycles,
                CLOCK,
                Instant.parse("2026-10-01T00:00:00Z"),
                null,
                null,
                PlayerStateSaveListener.ignore(),
                null,
                PlayerPushPort.NOOP,
                new InMemoryPlayerCommandAuditLog(),
                sequencer,
                null,
                false,
                Duration.ZERO,
                Duration.ofMinutes(5)
        );
        manager.attachBusinessResponseSnapshots(responseHub::snapshotForPlayer);
        PlayerBusinessCommandHandler handler = new PlayerBusinessCommandHandler(manager::getOrCreate, responseHub);
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        sessions.bind(PLAYER_ID, "session-1");
        PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                sessions,
                sequencer,
                new AdmissionControlledAgentRouter(
                        (target, operation) -> AdmissionDecision.accept(),
                        new LifecycleAwareAgentRouter(lifecycles, messages)
                ),
                new InMemoryPlayerCommandAuditLog(),
                ignored -> 1,
                CLOCK
        );
        PlayerBusinessCommandBinder.registerExamples(dispatcher, handler);
        manager.getOrCreate(PLAYER_ID);
        executor.runAll();
        return new Runtime(executor, manager, sequencer, responseHub, dispatcher);
    }

    private static PlayerCommand command() {
        ActivityProgressCommand payload = new ActivityProgressCommand("battle-win-1", 1);
        return new PlayerCommand(PLAYER_ID, "session-1", 1, 1, payload.operation(), payload);
    }

    private record Runtime(
            RecordingExecutor executor,
            PlayerGameAgentManager manager,
            PlayerCommandSequencer sequencer,
            PlayerBusinessResponseHub responses,
            PlayerCommandDispatcher dispatcher
    ) {
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            commands.add(command);
        }

        private synchronized void runAll() {
            while (!commands.isEmpty()) {
                commands.removeFirst().run();
            }
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
