package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.observability.ActorHotspotAction;
import com.commonbattle.observability.ActorHotspotCandidate;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 把热点 Actor 候选转换成 Agent 迁移提交。
 * 它只做计划层判断，真正的邮箱打包、目录 CAS、目标恢复和回滚仍交给 AgentMigrationCoordinator。
 */
public final class ActorHotspotMigrationPlanner {
    private final AgentDirectory agents;
    private final ClusterDirectory services;
    private final AgentMigrationTaskStore taskStore;
    private final ActorHotspotMigrationSubmitter submitter;

    public ActorHotspotMigrationPlanner(
            AgentDirectory agents,
            ClusterDirectory services,
            AgentMigrationTaskStore taskStore,
            AgentMigrationCoordinator coordinator
    ) {
        this(agents, services, taskStore, coordinator::migrate);
    }

    public ActorHotspotMigrationPlanner(
            AgentDirectory agents,
            ClusterDirectory services,
            AgentMigrationTaskStore taskStore,
            ActorHotspotMigrationSubmitter submitter
    ) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.services = Objects.requireNonNull(services, "services");
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
    }

    public List<ActorHotspotMigrationResult> submitCandidates(
            List<ActorHotspotCandidate> candidates,
            AgentMigrationStatePacker packer,
            int maxSubmissions
    ) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(packer, "packer");
        if (maxSubmissions < 0) {
            throw new IllegalArgumentException("maxSubmissions must not be negative");
        }
        java.util.ArrayList<ActorHotspotMigrationResult> results = new java.util.ArrayList<>();
        int submitted = 0;
        for (ActorHotspotCandidate candidate : candidates) {
            if (submitted >= maxSubmissions) {
                break;
            }
            ActorHotspotMigrationResult result = submitCandidate(candidate, packer);
            results.add(result);
            if (result.status() == ActorHotspotMigrationStatus.SUBMITTED) {
                submitted++;
            }
        }
        return List.copyOf(results);
    }

    public ActorHotspotMigrationResult submitCandidate(
            ActorHotspotCandidate candidate,
            AgentMigrationStatePacker packer
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(packer, "packer");
        if (candidate.action() != ActorHotspotAction.MIGRATION_CANDIDATE) {
            return ActorHotspotMigrationResult.of(candidate.actorId(), Optional.empty(), Optional.empty(),
                    ActorHotspotMigrationStatus.NOT_MIGRATION_CANDIDATE, "candidate_action_not_migration");
        }
        Optional<AgentIdentity> identity = identityOf(candidate.actorId());
        if (identity.isEmpty()) {
            return ActorHotspotMigrationResult.of(candidate.actorId(), Optional.empty(), Optional.empty(),
                    ActorHotspotMigrationStatus.UNSUPPORTED_ACTOR_ID, "unsupported_actor_id");
        }
        Optional<AgentLocation> source = agents.locate(identity.orElseThrow());
        if (source.isEmpty()) {
            return ActorHotspotMigrationResult.of(candidate.actorId(), identity, Optional.empty(),
                    ActorHotspotMigrationStatus.SOURCE_NOT_FOUND, "source_not_found");
        }
        if (hasPendingTask(identity.orElseThrow())) {
            return ActorHotspotMigrationResult.of(candidate.actorId(), identity, Optional.empty(),
                    ActorHotspotMigrationStatus.ALREADY_PENDING, "migration_task_already_pending");
        }
        Optional<AgentLocation> target = targetOf(candidate.actorId(), identity.orElseThrow(), source.orElseThrow());
        if (target.isEmpty()) {
            return ActorHotspotMigrationResult.of(candidate.actorId(), identity, Optional.empty(),
                    ActorHotspotMigrationStatus.NO_TARGET, "no_routable_target");
        }
        try {
            boolean accepted = submitter.migrate(identity.orElseThrow(), target.orElseThrow(), packer);
            if (!accepted) {
                return ActorHotspotMigrationResult.of(candidate.actorId(), identity, target,
                        ActorHotspotMigrationStatus.SUBMIT_REJECTED, "source_lifecycle_rejected");
            }
            return ActorHotspotMigrationResult.of(candidate.actorId(), identity, target,
                    ActorHotspotMigrationStatus.SUBMITTED, "");
        } catch (RuntimeException e) {
            return ActorHotspotMigrationResult.of(candidate.actorId(), identity, target,
                    ActorHotspotMigrationStatus.SUBMIT_FAILED, e.getClass().getSimpleName() + ":" + e.getMessage());
        }
    }

    private boolean hasPendingTask(AgentIdentity identity) {
        return taskStore.pendingTasks().stream()
                .anyMatch(task -> task.identity().equals(identity));
    }

    private Optional<AgentLocation> targetOf(String actorId, AgentIdentity identity, AgentLocation source) {
        ServiceKind kind = targetKind(identity).orElse(null);
        if (kind == null) {
            return Optional.empty();
        }
        return services.routable(kind).stream()
                .filter(service -> !service.id().equals(source.serviceId()))
                .filter(service -> service.id().region().equals(source.serviceId().region()))
                .filter(service -> service.supports(AgentMigrationOperations.ACCEPT))
                .sorted(Comparator.comparingLong(ServiceDescriptor::loadScore)
                        .thenComparing(service -> service.id().wireName()))
                .findFirst()
                .map(service -> new AgentLocation(service.id(), new ActorRef(actorId)));
    }

    private static Optional<ServiceKind> targetKind(AgentIdentity identity) {
        return switch (identity.type()) {
            case AgentIdentity.PLAYER, AgentIdentity.PROFILE, AgentIdentity.FRIEND, AgentIdentity.ALLIANCE ->
                    Optional.of(ServiceKind.GAME);
            case AgentIdentity.SCENE -> Optional.of(ServiceKind.SCENE);
            default -> Optional.empty();
        };
    }

    static Optional<AgentIdentity> identityOf(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return Optional.empty();
        }
        String normalized = actorId.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("player-")) {
            return identity(AgentIdentity.PLAYER, actorId.substring("player-".length()));
        }
        if (normalized.startsWith("profile-")) {
            return identity(AgentIdentity.PROFILE, actorId.substring("profile-".length()));
        }
        if (normalized.startsWith("friend-")) {
            return identity(AgentIdentity.FRIEND, actorId.substring("friend-".length()));
        }
        if (normalized.startsWith("alliance-")) {
            return identity(AgentIdentity.ALLIANCE, actorId.substring("alliance-".length()));
        }
        if (normalized.startsWith("scene:")) {
            return identity(AgentIdentity.SCENE, actorId.substring("scene:".length()));
        }
        if (normalized.startsWith("scene-shard:")) {
            return identity(AgentIdentity.SCENE, actorId.substring("scene-shard:".length()));
        }
        return Optional.empty();
    }

    private static Optional<AgentIdentity> identity(String type, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AgentIdentity(type, key));
    }
}
