package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.observability.ActorHotspotAction;
import com.commonbattle.observability.ActorHotspotCandidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorHotspotMigrationPlannerTest {
    @Test
    void submitsMigrationCandidateToLowestLoadTargetInSameRegion() {
        Harness harness = new Harness();
        AgentIdentity player = AgentIdentity.player(10001L);
        harness.agents.claim(player, new AgentLocation(harness.game1.id(), new ActorRef("player-10001")));
        harness.directory.seed(harness.game1);
        harness.directory.seed(ServiceMetadata.withLoad(harness.game2, 80, 100));
        harness.directory.seed(ServiceMetadata.withLoad(harness.game3, 10, 100));
        ActorHotspotMigrationPlanner planner = harness.planner();

        ActorHotspotMigrationResult result = planner.submitCandidate(
                candidate("player-10001", ActorHotspotAction.MIGRATION_CANDIDATE),
                snapshotPacker()
        );

        assertEquals(ActorHotspotMigrationStatus.SUBMITTED, result.status());
        assertEquals(Optional.of(player), result.identity());
        assertEquals(harness.game3.id(), result.target().orElseThrow().serviceId());
        assertEquals("player-10001", result.target().orElseThrow().actorRef().id());
        assertEquals(1, harness.submissions.size());
        assertEquals(player, harness.submissions.getFirst().identity);
    }

    @Test
    void skipsCandidateWhenActionIsOnlyThrottle() {
        Harness harness = new Harness();
        ActorHotspotMigrationPlanner planner = harness.planner();

        ActorHotspotMigrationResult result = planner.submitCandidate(
                candidate("player-10001", ActorHotspotAction.THROTTLE),
                snapshotPacker()
        );

        assertEquals(ActorHotspotMigrationStatus.NOT_MIGRATION_CANDIDATE, result.status());
        assertTrue(harness.submissions.isEmpty());
    }

    @Test
    void skipsWhenMigrationTaskAlreadyPendingForSameIdentity() {
        Harness harness = new Harness();
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation source = new AgentLocation(harness.game1.id(), new ActorRef("player-10001"));
        harness.agents.claim(player, source);
        harness.directory.seed(harness.game1);
        harness.directory.seed(harness.game2);
        harness.taskStore.save(new AgentMigrationTask(
                "task-1",
                player,
                source,
                new AgentLocation(harness.game2.id(), new ActorRef("player-10001")),
                new AgentMigrationSnapshot("test", new byte[]{1}),
                AgentMigrationTaskStatus.PREPARED,
                "",
                Instant.parse("2026-09-02T00:00:00Z")
        ));

        ActorHotspotMigrationResult result = harness.planner().submitCandidate(
                candidate("player-10001", ActorHotspotAction.MIGRATION_CANDIDATE),
                snapshotPacker()
        );

        assertEquals(ActorHotspotMigrationStatus.ALREADY_PENDING, result.status());
        assertTrue(harness.submissions.isEmpty());
    }

    @Test
    void skipsWhenNoTargetCanAcceptMigration() {
        Harness harness = new Harness();
        AgentIdentity player = AgentIdentity.player(10001L);
        harness.agents.claim(player, new AgentLocation(harness.game1.id(), new ActorRef("player-10001")));
        harness.directory.seed(harness.game1);
        harness.directory.seed(harness.gameWithoutMigrationTopic);

        ActorHotspotMigrationResult result = harness.planner().submitCandidate(
                candidate("player-10001", ActorHotspotAction.MIGRATION_CANDIDATE),
                snapshotPacker()
        );

        assertEquals(ActorHotspotMigrationStatus.NO_TARGET, result.status());
        assertTrue(harness.submissions.isEmpty());
    }

    @Test
    void parsesKnownActorIds() {
        assertEquals(Optional.of(AgentIdentity.player(10001L)),
                ActorHotspotMigrationPlanner.identityOf("player-10001"));
        assertEquals(Optional.of(AgentIdentity.friend(10002L)),
                ActorHotspotMigrationPlanner.identityOf("friend-10002"));
        assertEquals(Optional.of(AgentIdentity.alliance(7L)),
                ActorHotspotMigrationPlanner.identityOf("alliance-7"));
        assertEquals(Optional.of(AgentIdentity.scene("world-1")),
                ActorHotspotMigrationPlanner.identityOf("scene:world-1"));
        assertEquals(Optional.of(AgentIdentity.scene("world-1:0")),
                ActorHotspotMigrationPlanner.identityOf("scene-shard:world-1:0"));
        assertTrue(ActorHotspotMigrationPlanner.identityOf("chat-world").isEmpty());
    }

    private static ActorHotspotCandidate candidate(String actorId, ActorHotspotAction action) {
        return new ActorHotspotCandidate(actorId, "player", 512, 0, 0, action);
    }

    private static AgentMigrationStatePacker snapshotPacker() {
        return (identity, target, context) -> new AgentMigrationSnapshot("test", new byte[]{1});
    }

    private static ServiceDescriptor descriptor(String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
                Map.of()
        );
    }

    private static final class Harness {
        private final InMemoryAgentDirectory agents = new InMemoryAgentDirectory();
        private final InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        private final ClusterDirectory directory = new ClusterDirectory(registry);
        private final InMemoryAgentMigrationTaskStore taskStore = new InMemoryAgentMigrationTaskStore();
        private final List<Submission> submissions = new ArrayList<>();
        private final ServiceDescriptor game1 = descriptor("game-1", 9001, Set.of(AgentMigrationOperations.ACCEPT));
        private final ServiceDescriptor game2 = descriptor("game-2", 9002, Set.of(AgentMigrationOperations.ACCEPT));
        private final ServiceDescriptor game3 = descriptor("game-3", 9003, Set.of(AgentMigrationOperations.ACCEPT));
        private final ServiceDescriptor gameWithoutMigrationTopic = descriptor("game-4", 9004, Set.of());

        private Harness() {
            directory.watch(ServiceKind.GAME);
        }

        private ActorHotspotMigrationPlanner planner() {
            return new ActorHotspotMigrationPlanner(agents, directory, taskStore, (identity, target, packer) -> {
                submissions.add(new Submission(identity, target));
                return true;
            });
        }
    }

    private record Submission(AgentIdentity identity, AgentLocation target) {
    }
}
