package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystemConfig;
import com.commonbattle.actor.ActorOverflowStrategy;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentMessagePortTest {
    @Test
    void localAgentMessageUsesMailboxWithoutRpc() throws InterruptedException {
        try (ActorSystem actors = new ActorSystem(1)) {
            CountingRpcGateway rpc = new CountingRpcGateway();
            DefaultAgentMessagePort port = new DefaultAgentMessagePort(actors, rpc);
            ActorRef target = actors.actor("agent-1");
            CountDownLatch done = new CountDownLatch(1);
            AtomicInteger value = new AtomicInteger();

            port.tellLocal(target, ignored -> {
                value.incrementAndGet();
                done.countDown();
            });

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals(1, value.get());
            assertEquals(0, rpc.calls.get());
        }
    }

    @Test
    void askLocalReadsTargetStateAndReturnsOnRequesterMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ManualAskTimeoutScheduler timeouts = new ManualAskTimeoutScheduler();
        ActorSystem actors = new ActorSystem(executor, 64);
        DefaultAgentMessagePort port = new DefaultAgentMessagePort(actors, new CountingRpcGateway(), timeouts);
        ActorRef requester = actors.actor("requester");
        ActorRef target = actors.actor("target");
        AtomicReference<String> response = new AtomicReference<>();

        port.askLocal(
                requester,
                target,
                Duration.ofSeconds(1),
                context -> "state-from-" + context.self().id(),
                new LocalAskCallback<>() {
                    @Override
                    public void success(com.commonbattle.actor.ActorContext context, String value) {
                        response.set(context.self().id() + ":" + value);
                    }

                    @Override
                    public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                        throw new AssertionError(error);
                    }
                }
        );

        executor.runNext();
        assertEquals(null, response.get());
        executor.runNext();

        assertEquals("requester:state-from-target", response.get());
        assertTrue(timeouts.cancelled());
    }

    @Test
    void askLocalTargetExceptionReturnsFailureOnRequesterMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        DefaultAgentMessagePort port = new DefaultAgentMessagePort(
                actors,
                new CountingRpcGateway(),
                new ManualAskTimeoutScheduler()
        );
        ActorRef requester = actors.actor("requester");
        ActorRef target = actors.actor("target");
        AtomicReference<Throwable> failure = new AtomicReference<>();

        port.askLocal(
                requester,
                target,
                Duration.ofSeconds(1),
                ignored -> {
                    throw new IllegalStateException("query failed");
                },
                new RecordingAskCallback<>(ignored -> {
                }, failure::set)
        );

        executor.runNext();
        executor.runNext();

        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals("query failed", failure.get().getMessage());
    }

    @Test
    void askLocalTimeoutWinsOverLateTargetResponse() {
        RecordingExecutor executor = new RecordingExecutor();
        ManualAskTimeoutScheduler timeouts = new ManualAskTimeoutScheduler();
        ActorSystem actors = new ActorSystem(executor, 64);
        DefaultAgentMessagePort port = new DefaultAgentMessagePort(actors, new CountingRpcGateway(), timeouts);
        ActorRef requester = actors.actor("requester");
        ActorRef target = actors.actor("target");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> success = new AtomicReference<>();

        port.askLocal(
                requester,
                target,
                Duration.ofMillis(1),
                ignored -> "late",
                new RecordingAskCallback<>(success::set, failure::set)
        );
        timeouts.fire();
        executor.runNext();
        executor.runNext();

        assertEquals(null, success.get());
        assertInstanceOf(AskTimeoutException.class, failure.get());
    }

    @Test
    void askLocalDeliveryFailureReturnsFailureOnRequesterMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(
                executor,
                new ActorSystemConfig(1, 64, 1, ActorOverflowStrategy.REJECT, Duration.ZERO),
                ignored -> {
                },
                ignored -> {
                }
        );
        DefaultAgentMessagePort port = new DefaultAgentMessagePort(
                actors,
                new CountingRpcGateway(),
                new ManualAskTimeoutScheduler()
        );
        ActorRef requester = actors.actor("requester");
        ActorRef target = actors.actor("target");
        AtomicReference<Throwable> failure = new AtomicReference<>();

        actors.send(target, ignored -> {
        });
        port.askLocal(
                requester,
                target,
                Duration.ofSeconds(1),
                ignored -> "unreachable",
                new RecordingAskCallback<>(ignored -> {
                }, failure::set)
        );

        executor.runNext();
        executor.runNext();

        assertInstanceOf(LocalAskDeliveryException.class, failure.get());
    }

    private static final class CountingRpcGateway implements RpcGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            calls.incrementAndGet();
        }
    }

    private static final class RecordingAskCallback<T> implements LocalAskCallback<T> {
        private final java.util.function.Consumer<T> success;
        private final java.util.function.Consumer<Throwable> failure;

        private RecordingAskCallback(java.util.function.Consumer<T> success, java.util.function.Consumer<Throwable> failure) {
            this.success = success;
            this.failure = failure;
        }

        @Override
        public void success(com.commonbattle.actor.ActorContext context, T response) {
            success.accept(response);
        }

        @Override
        public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
            failure.accept(error);
        }
    }

    private static final class ManualAskTimeoutScheduler implements AskTimeoutScheduler {
        private final List<ManualTimeout> timeouts = new ArrayList<>();

        @Override
        public ScheduledAskTimeout schedule(Duration timeout, Runnable action) {
            ManualTimeout scheduled = new ManualTimeout(action);
            timeouts.add(scheduled);
            return scheduled;
        }

        void fire() {
            timeouts.forEach(ManualTimeout::fire);
        }

        boolean cancelled() {
            return timeouts.stream().allMatch(ManualTimeout::cancelled);
        }
    }

    private static final class ManualTimeout implements ScheduledAskTimeout {
        private final Runnable action;
        private boolean cancelled;

        private ManualTimeout(Runnable action) {
            this.action = action;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        void fire() {
            if (!cancelled) {
                action.run();
            }
        }

        boolean cancelled() {
            return cancelled;
        }
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
}
