package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.config.GameConfigPackage;
import com.commonbattle.game.config.GameConfigRegistry;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.GrowthTuning;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerGameAgentConfigRuntimeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void businessMessageBindsConfigRuntimeOnceEvenWhenConfigPublishesInsideHandler() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(config(1, 60));
        PlayerGameAgent agent = createAgent(executor, registry);
        AtomicLong boundVersion = new AtomicLong();
        AtomicReference<Integer> expAfterFirstMessage = new AtomicReference<>();

        agent.execute(execution -> {
            boundVersion.set(execution.runtime().version());
            execution.runtime().requireBagService().grant(
                    execution.profile().bag(),
                    Reward.of(new ItemStack("exp_potion", 1))
            );
            registry.publish(config(2, 120));
            execution.runtime().growthService().useExpItems(
                    execution.profile().bag(),
                    execution.profile().growth(),
                    1
            );
            expAfterFirstMessage.set(execution.profile().growth().exp());
        });
        executor.runNext();

        assertEquals(1, boundVersion.get());
        assertEquals(60, expAfterFirstMessage.get());
        agent.execute(execution -> {
            execution.runtime().requireBagService().grant(
                    execution.profile().bag(),
                    Reward.of(new ItemStack("exp_potion", 1))
            );
            execution.runtime().growthService().useExpItems(
                    execution.profile().bag(),
                    execution.profile().growth(),
                    1
            );
        });
        executor.runNext();
        assertEquals(80, agent.profile().growth().exp());
        assertEquals(2, agent.profile().growth().level());
    }

    @Test
    void grayRuntimeIsSelectedByPlayerWhenCommandRuns() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(config(1, 60));
        registry.publishGray(config(2, 120), 100);
        PlayerGameAgent agent = createAgent(executor, registry);
        AtomicLong version = new AtomicLong();

        agent.execute(execution -> version.set(execution.runtime().version()));
        executor.runNext();

        assertEquals(2, version.get());
    }

    @Test
    void rollbackChangesRuntimeSeenByLaterMessages() {
        RecordingExecutor executor = new RecordingExecutor();
        GameConfigRegistry registry = registry();
        registry.publish(config(1, 60));
        registry.publish(config(2, 120));
        PlayerGameAgent agent = createAgent(executor, registry);
        AtomicLong first = new AtomicLong();
        AtomicLong second = new AtomicLong();

        agent.execute(execution -> first.set(execution.runtime().version()));
        executor.runNext();
        registry.rollback(1);
        agent.execute(execution -> second.set(execution.runtime().version()));
        executor.runNext();

        assertEquals(2, first.get());
        assertEquals(1, second.get());
    }

    private static PlayerGameAgent createAgent(Executor executor, GameConfigRegistry registry) {
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorRef self = actors.actor("player-10001");
        return new PlayerGameAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                self,
                new PlayerProfile(10001L),
                registry,
                CLOCK,
                Instant.parse("2026-08-01T00:00:00Z")
        );
    }

    private static InMemoryGameConfigRegistry registry() {
        return new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
    }

    private static GameConfigPackage config(long version, int expPerItem) {
        return new GameConfigPackage(
                version,
                List.of(
                        new ItemDefinition("gold", "currency", 999999),
                        new ItemDefinition("exp_potion", "growth", 999)
                ),
                List.of(new ActivityDefinition(
                        "daily-login",
                        ActivityType.LOGIN,
                        1,
                        Reward.of(new ItemStack("gold", 100), new ItemStack("exp_potion", 1))
                )),
                new GrowthTuning("exp_potion", expPerItem, 100),
                CLOCK.instant()
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
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
