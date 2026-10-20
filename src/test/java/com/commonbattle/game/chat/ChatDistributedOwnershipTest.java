package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentLeaseSupervisor;
import com.commonbattle.actor.agent.SerializedAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentAlreadyOwnedException;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDistributedOwnershipTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void sameDirectSessionCannotBeMaterializedOnTwoChatServices() {
        SerializedAgentDirectory directory = new SerializedAgentDirectory(new InMemoryAtomicBytesStore(), CLOCK);
        try (ActorSystem firstActors = new ActorSystem(1);
             ActorSystem secondActors = new ActorSystem(1);
             LeaseFixture first = lifecycles("chat-1", firstActors, directory);
             LeaseFixture second = lifecycles("chat-2", secondActors, directory)) {
            DirectChatSessionManager firstManager = manager(firstActors, first.lifecycles());
            DirectChatSessionManager secondManager = manager(secondActors, second.lifecycles());

            firstManager.session(10001L, 10002L);

            assertThrows(AgentAlreadyOwnedException.class,
                    () -> secondManager.session(10001L, 10002L));
        }
    }

    @Test
    void leaseLossEvictsCachedDirectSession() throws InterruptedException {
        SerializedAgentDirectory directory = new SerializedAgentDirectory(new InMemoryAtomicBytesStore(), CLOCK);
        try (ActorSystem actors = new ActorSystem(1);
             LeaseFixture fixture = lifecycles("chat-1", actors, directory)) {
            DirectChatSessionManager manager = manager(actors, fixture.lifecycles());
            var identity = ChatActorIds.directIdentity(10001L, 10002L);
            CountDownLatch invalidated = new CountDownLatch(1);
            fixture.lifecycles().addInvalidationListener(record -> {
                if (record.identity().equals(identity)) {
                    invalidated.countDown();
                }
            });
            manager.session(10001L, 10002L);
            assertEquals(1, manager.activeSessions());

            fixture.lifecycles().handleLeaseLost(fixture.supervisor().lease(identity).orElseThrow());

            assertTrue(invalidated.await(1, TimeUnit.SECONDS));
            assertEquals(0, manager.activeSessions());
        }
    }

    private static DirectChatSessionManager manager(ActorSystem actors, AgentLifecycleManager lifecycles) {
        return new DirectChatSessionManager(
                actors,
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                DirectChatAccessPolicy.allowAll(),
                null,
                request -> ChatMessageDecision.sent("player", request.text().trim()),
                ChatDeliverySink.noop(),
                CLOCK,
                20,
                (target, operation) -> com.commonbattle.actor.backpressure.AdmissionDecision.accept(),
                lifecycles
        );
    }

    private static LeaseFixture lifecycles(
            String node,
            ActorSystem actors,
            SerializedAgentDirectory directory
    ) {
        AtomicReference<AgentLifecycleManager> reference = new AtomicReference<>();
        AgentLeaseSupervisor supervisor = new AgentLeaseSupervisor(
                directory,
                node + "-session",
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                CLOCK,
                lease -> reference.get().handleLeaseLost(lease)
        );
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                ServiceId.of(ServiceKind.CHAT, "r1", node), actors, directory, CLOCK, supervisor
        );
        reference.set(lifecycles);
        return new LeaseFixture(lifecycles, supervisor);
    }

    private record LeaseFixture(
            AgentLifecycleManager lifecycles,
            AgentLeaseSupervisor supervisor
    ) implements AutoCloseable {
        @Override
        public void close() {
            supervisor.close();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
