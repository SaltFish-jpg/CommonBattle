package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentLeaseSupervisor;
import com.commonbattle.actor.agent.SerializedAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentAlreadyOwnedException;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneDistributedOwnershipTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void sameSmallSceneCannotBeMaterializedOnTwoSceneServices() {
        SerializedAgentDirectory directory = new SerializedAgentDirectory(new InMemoryAtomicBytesStore(), CLOCK);
        try (ActorSystem firstActors = new ActorSystem(1);
             ActorSystem secondActors = new ActorSystem(1);
             LeaseFixture first = lifecycles("scene-1", firstActors, directory);
             LeaseFixture second = lifecycles("scene-2", secondActors, directory)) {
            MultiSmallSceneService firstService = MultiSmallSceneService.create(
                    firstActors, "r1", "scene-1", new ServiceEndpoint("127.0.0.1", 9101), 10
            );
            MultiSmallSceneService secondService = MultiSmallSceneService.create(
                    secondActors, "r1", "scene-2", new ServiceEndpoint("127.0.0.1", 9102), 10
            );
            firstService.attachLifecycles(first.lifecycles());
            secondService.attachLifecycles(second.lifecycles());

            firstService.place("dungeon-100", 0, 0);

            assertThrows(AgentAlreadyOwnedException.class,
                    () -> secondService.place("dungeon-100", 0, 0));
        }
    }

    @Test
    void largeSceneShardsAreGloballyExclusive() {
        SerializedAgentDirectory directory = new SerializedAgentDirectory(new InMemoryAtomicBytesStore(), CLOCK);
        try (ActorSystem firstActors = new ActorSystem(1);
             ActorSystem secondActors = new ActorSystem(1);
             LeaseFixture first = lifecycles("scene-1", firstActors, directory);
             LeaseFixture second = lifecycles("scene-2", secondActors, directory)) {
            LargeSceneShardService firstService = LargeSceneShardService.create(
                    firstActors, "r1", "scene-1", new ServiceEndpoint("127.0.0.1", 9101),
                    "world-1", 4, 16, CLOCK
            );
            LargeSceneShardService secondService = LargeSceneShardService.create(
                    secondActors, "r1", "scene-2", new ServiceEndpoint("127.0.0.1", 9102),
                    "world-1", 4, 16, CLOCK
            );
            firstService.attachLifecycles(first.lifecycles());

            assertThrows(AgentAlreadyOwnedException.class,
                    () -> secondService.attachLifecycles(second.lifecycles()));
        }
    }

    @Test
    void leaseLossEvictsCachedSmallScene() throws InterruptedException {
        SerializedAgentDirectory directory = new SerializedAgentDirectory(new InMemoryAtomicBytesStore(), CLOCK);
        try (ActorSystem actors = new ActorSystem(1);
             LeaseFixture fixture = lifecycles("scene-1", actors, directory)) {
            MultiSmallSceneService service = MultiSmallSceneService.create(
                    actors, "r1", "scene-1", new ServiceEndpoint("127.0.0.1", 9101), 10
            );
            service.attachLifecycles(fixture.lifecycles());
            var identity = com.commonbattle.actor.agent.AgentIdentity.scene("dungeon-100");
            CountDownLatch invalidated = new CountDownLatch(1);
            fixture.lifecycles().addInvalidationListener(record -> {
                if (record.identity().equals(identity)) {
                    invalidated.countDown();
                }
            });
            service.place("dungeon-100", 0, 0);
            assertEquals("1", service.descriptor().metadata(ServiceMetadata.LOAD_USED));

            fixture.lifecycles().handleLeaseLost(fixture.supervisor().lease(identity).orElseThrow());

            assertTrue(invalidated.await(1, TimeUnit.SECONDS));
            assertEquals("0", service.descriptor().metadata(ServiceMetadata.LOAD_USED));
        }
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
                ServiceId.of(ServiceKind.SCENE, "r1", node), actors, directory, CLOCK, supervisor
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
}
