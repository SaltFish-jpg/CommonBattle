package com.commonbattle.actor.rpc;

import com.commonbattle.actor.ActorOverflowStrategy;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorSystemConfig;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActorRpcClientTest {
    @Test
    void callbackReturnsToOwnerMailboxAsRpcCallbackTask() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorRef owner = actors.actor("player-1");
        ActorRpcClient client = new ActorRpcClient(actors, owner, new ImmediateSuccessGateway());
        AtomicReference<String> result = new AtomicReference<>();

        client.call(new RpcRequest<>("scene", "scene.enter", "request", String.class),
                (context, response) -> result.set(context.self().id() + ":" + response),
                (context, error) -> {
                    throw new AssertionError(error);
                });

        assertEquals(1, actors.stats().queuedTasksByCategory().get(ActorTaskCategory.RPC_CALLBACK));
        assertEquals(1, client.stats().calls());
        assertEquals(1, client.stats().succeededResponses());
        executor.runNext();
        assertEquals("player-1:ok", result.get());
    }

    @Test
    void callbackMailboxFullDoesNotThrowOnRpcThread() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(
                executor,
                new ActorSystemConfig(1, 64, 1, ActorOverflowStrategy.REJECT, Duration.ZERO),
                ignored -> {
                },
                ignored -> {
                }
        );
        ActorRef owner = actors.actor("player-1");
        ActorRpcClient client = new ActorRpcClient(actors, owner, new ImmediateSuccessGateway());

        actors.send(owner, ignored -> {
        });
        client.call(new RpcRequest<>("scene", "scene.enter", "request", String.class),
                (context, response) -> {
                    throw new AssertionError("callback must not run when owner mailbox is full");
                },
                (context, error) -> {
                    throw new AssertionError(error);
                });

        assertEquals(1, actors.stats().rejectedTasksByCategory().get(ActorTaskCategory.RPC_CALLBACK));
        assertEquals(1, client.stats().callbackDeliveryFailures());
    }

    @Test
    void handlerFailureReceivesMappedDeliveryResultOnOwnerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        RuntimeException failure = new RuntimeException("timeout");
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorRef owner = actors.actor("player-1");
        ActorRpcClient client = new ActorRpcClient(
                actors,
                owner,
                new FailingGateway(failure),
                error -> AgentDeliveryResult.timeout("mapped_timeout")
        );
        AtomicReference<AgentDeliveryStatus> status = new AtomicReference<>();
        AtomicReference<String> reason = new AtomicReference<>();

        client.call(new RpcRequest<>("scene", "scene.enter", "request", String.class), new ActorRpcHandler<>() {
            @Override
            public void success(com.commonbattle.actor.ActorContext context, String response) {
                throw new AssertionError("call must fail");
            }

            @Override
            public void failure(com.commonbattle.actor.ActorContext context, AgentDeliveryResult delivery, Throwable error) {
                status.set(delivery.status());
                reason.set(delivery.reason());
            }
        });

        executor.runNext();

        assertEquals(AgentDeliveryStatus.TIMEOUT, status.get());
        assertEquals("mapped_timeout", reason.get());
        assertEquals(1, client.stats().failedResponses());
        assertEquals(1, client.stats().failedResponsesByStatus().get(AgentDeliveryStatus.TIMEOUT));
    }

    private static final class ImmediateSuccessGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            callback.success(request.responseType().cast("ok"));
        }
    }

    private record FailingGateway(RuntimeException failure) implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            callback.failure(failure);
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
