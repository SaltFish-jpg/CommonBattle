package com.commonbattle.observability;

import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.session.PlayerClientErrorCode;
import com.commonbattle.game.session.PlayerOutboundTopicDeliveryStats;
import com.commonbattle.game.session.PlayerCommandStatus;

import java.util.Map;
import java.util.Objects;

/**
 * 运行时健康快照 JSON 渲染器。
 * 运维探活不依赖 Web 框架，避免基础设施端点被业务依赖链拖慢或拖垮。
 */
public final class RuntimeHealthJsonFormatter {
    private RuntimeHealthJsonFormatter() {
    }

    public static String format(RuntimeHealthSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder json = new StringBuilder(1024);
        json.append('{');
        field(json, "timestamp", snapshot.timestamp().toString()).append(',');
        field(json, "status", snapshot.status().name()).append(',');
        object(json, "actorSystem", actorSystem(snapshot)).append(',');
        object(json, "actorMailboxes", actorMailboxes(snapshot)).append(',');
        object(json, "actorMailboxPressures", actorMailboxPressures(snapshot)).append(',');
        object(json, "actorHotspotAdmissions", actorHotspotAdmissions(snapshot)).append(',');
        object(json, "actorIncidents", actorIncidents(snapshot)).append(',');
        object(json, "actorSlowTasks", actorSlowTasks(snapshot)).append(',');
        object(json, "actorSchedules", actorSchedules(snapshot)).append(',');
        object(json, "rpc", rpc(snapshot)).append(',');
        object(json, "rpcResilience", rpcResilience(snapshot)).append(',');
        object(json, "rpcRoutes", rpcRoutes(snapshot)).append(',');
        object(json, "actorRpc", actorRpc(snapshot)).append(',');
        object(json, "businessAgentMessages", businessAgentMessages(snapshot)).append(',');
        object(json, "businessAgentRpcEndpoints", businessAgentRpcEndpoints(snapshot)).append(',');
        object(json, "commands", commands(snapshot)).append(',');
        object(json, "businessResponses", businessResponses(snapshot)).append(',');
        object(json, "playerOutboundDeliveries", playerOutboundDeliveries(snapshot)).append(',');
        object(json, "playerGateways", playerGateways(snapshot)).append(',');
        object(json, "asyncShopPurchases", asyncShopPurchases(snapshot)).append(',');
        object(json, "agents", agents(snapshot)).append(',');
        object(json, "playerAgents", playerAgents(snapshot)).append(',');
        object(json, "agentMigrations", agentMigrations(snapshot)).append(',');
        object(json, "agentMigrationExecutors", agentMigrationExecutors(snapshot)).append(',');
        object(json, "agentMigrationRecoveries", agentMigrationRecoveries(snapshot)).append(',');
        object(json, "agentMigrationRecoverySchedulers", agentMigrationRecoverySchedulers(snapshot)).append(',');
        object(json, "agentMigrationTaskRetentions", agentMigrationTaskRetentions(snapshot)).append(',');
        object(json, "agentMigrationTaskStores", agentMigrationTaskStores(snapshot)).append(',');
        object(json, "outbox", outbox(snapshot)).append(',');
        object(json, "cluster", cluster(snapshot)).append(',');
        object(json, "registryLeases", registryLeases(snapshot)).append(',');
        object(json, "registryHistory", registryHistory(snapshot)).append(',');
        object(json, "registrySubscriptions", registrySubscriptions(snapshot)).append(',');
        object(json, "remoteRegistryRecoveries", remoteRegistryRecoveries(snapshot)).append(',');
        object(json, "serviceDescriptorPublishers", serviceDescriptorPublishers(snapshot)).append(',');
        object(json, "networkTransports", networkTransports(snapshot)).append(',');
        object(json, "configCaches", configCaches(snapshot)).append(',');
        object(json, "configRecoveries", configRecoveries(snapshot)).append(',');
        object(json, "eventCenters", eventCenters(snapshot)).append(',');
        object(json, "eventSubscriptions", eventSubscriptions(snapshot)).append(',');
        object(json, "actorEventSubscribers", actorEventSubscribers(snapshot)).append(',');
        object(json, "ownerActorEventSubscriptions", ownerActorEventSubscriptions(snapshot)).append(',');
        object(json, "ownerEventRepairSchedulers", ownerEventRepairSchedulers(snapshot)).append(',');
        object(json, "ownerEventRepairDispatchers", ownerEventRepairDispatchers(snapshot)).append(',');
        object(json, "ownerRepairOps", ownerRepairOps(snapshot)).append(',');
        object(json, "profileInterests", profileInterests(snapshot)).append(',');
        object(json, "profileRuntimes", profileRuntimes(snapshot)).append(',');
        object(json, "chatRuntimes", chatRuntimes(snapshot)).append(',');
        object(json, "sceneRuntimes", sceneRuntimes(snapshot)).append(',');
        object(json, "shopRuntimes", shopRuntimes(snapshot)).append(',');
        object(json, "commandAudits", commandAudits(snapshot));
        json.append('}');
        return json.toString();
    }

    private static StringBuilder actorSystem(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "submittedTasks", snapshot.actorSystem().submittedTasks()).append(',');
        number(json, "completedTasks", snapshot.actorSystem().completedTasks()).append(',');
        number(json, "failedTasks", snapshot.actorSystem().failedTasks()).append(',');
        number(json, "rejectedTasks", snapshot.actorSystem().rejectedTasks()).append(',');
        number(json, "droppedTasks", snapshot.actorSystem().droppedTasks()).append(',');
        number(json, "queuedTasks", snapshot.actorSystem().queuedTasks()).append(',');
        number(json, "runningMailboxes", snapshot.actorSystem().runningMailboxes()).append(',');
        number(json, "activeMailboxes", snapshot.actorSystem().activeMailboxes()).append(',');
        number(json, "largestMailboxQueuedTasks", snapshot.actorSystem().largestMailboxQueuedTasks()).append(',');
        field(json, "largestMailboxActorId", snapshot.actorSystem().largestMailboxActorId()).append(',');
        number(json, "peakQueuedTasks", snapshot.actorSystem().peakQueuedTasks()).append(',');
        number(json, "peakRunningMailboxes", snapshot.actorSystem().peakRunningMailboxes()).append(',');
        number(json, "slowTasks", snapshot.actorSystem().slowTasks()).append(',');
        number(json, "slowestTaskMillis", snapshot.actorSystem().slowestTaskMillis()).append(',');
        field(json, "slowestTaskActorId", snapshot.actorSystem().slowestTaskActorId()).append(',');
        field(json, "slowestTaskCategory", snapshot.actorSystem().slowestTaskCategory().name()).append(',');
        object(json, "queuedTasksByCategory", enumMap(snapshot.actorSystem().queuedTasksByCategory(),
                ActorTaskCategory.values())).append(',');
        object(json, "rejectedTasksByCategory", enumMap(snapshot.actorSystem().rejectedTasksByCategory(),
                ActorTaskCategory.values())).append(',');
        object(json, "droppedTasksByCategory", enumMap(snapshot.actorSystem().droppedTasksByCategory(),
                ActorTaskCategory.values()));
        json.append('}');
        return json;
    }

    private static StringBuilder actorMailboxes(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        ActorMailboxDiagnostics diagnostics = snapshot.actorMailboxes();
        number(json, "activeMailboxes", diagnostics.activeMailboxes()).append(',');
        number(json, "queuedTasks", diagnostics.queuedTasks()).append(',');
        number(json, "largestMailboxQueuedTasks", diagnostics.largestMailboxQueuedTasks()).append(',');
        field(json, "largestMailboxActorId", diagnostics.largestMailboxActorId()).append(',');
        name(json, "groups").append('[');
        for (int i = 0; i < diagnostics.groups().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            actorMailboxGroup(json, diagnostics.groups().get(i));
        }
        json.append("],");
        name(json, "hottestMailboxes").append('[');
        for (int i = 0; i < diagnostics.hottestMailboxes().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            var mailbox = diagnostics.hottestMailboxes().get(i);
            json.append('{');
            field(json, "actorId", mailbox.actor().id()).append(',');
            number(json, "queuedTasks", mailbox.queuedTasks()).append(',');
            object(json, "queuedTasksByCategory", enumMap(mailbox.queuedTasksByCategory(),
                    ActorTaskCategory.values()));
            json.append('}');
        }
        json.append(']');
        json.append('}');
        return json;
    }

    private static void actorMailboxGroup(StringBuilder json, ActorMailboxGroupStats group) {
        json.append('{');
        field(json, "group", group.group()).append(',');
        number(json, "activeMailboxes", group.activeMailboxes()).append(',');
        number(json, "queuedTasks", group.queuedTasks()).append(',');
        number(json, "largestMailboxQueuedTasks", group.largestMailboxQueuedTasks()).append(',');
        field(json, "largestMailboxActorId", group.largestMailboxActorId()).append(',');
        object(json, "queuedTasksByCategory", enumMap(group.queuedTasksByCategory(),
                ActorTaskCategory.values()));
        json.append('}');
    }

    private static StringBuilder actorMailboxPressures(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        ActorMailboxPressureHealthStats stats = snapshot.actorMailboxPressures();
        json.append('{');
        number(json, "controllerCount", stats.controllerCount()).append(',');
        number(json, "admissions", stats.admissions()).append(',');
        number(json, "accepted", stats.accepted()).append(',');
        number(json, "delegateRejected", stats.delegateRejected()).append(',');
        number(json, "pressureRejected", stats.pressureRejected()).append(',');
        number(json, "targetPressureRejected", stats.targetPressureRejected()).append(',');
        number(json, "groupPressureRejected", stats.groupPressureRejected()).append(',');
        object(json, "rejectedByTargetType", stringNumberMap(stats.rejectedByTargetType())).append(',');
        object(json, "rejectedByActorGroup", stringNumberMap(stats.rejectedByActorGroup())).append(',');
        object(json, "rejectedByReason", stringNumberMap(stats.rejectedByReason()));
        json.append('}');
        return json;
    }

    private static StringBuilder actorHotspotAdmissions(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        ActorHotspotAdmissionHealthStats stats = snapshot.actorHotspotAdmissions();
        json.append('{');
        number(json, "controllerCount", stats.controllerCount()).append(',');
        number(json, "admissions", stats.admissions()).append(',');
        number(json, "accepted", stats.accepted()).append(',');
        number(json, "delegateRejected", stats.delegateRejected()).append(',');
        number(json, "hotspotRejected", stats.hotspotRejected()).append(',');
        number(json, "throttleRejected", stats.throttleRejected()).append(',');
        number(json, "migrationCandidateRejected", stats.migrationCandidateRejected()).append(',');
        object(json, "rejectedByTargetType", stringNumberMap(stats.rejectedByTargetType())).append(',');
        object(json, "rejectedByActorGroup", stringNumberMap(stats.rejectedByActorGroup())).append(',');
        object(json, "rejectedByReason", stringNumberMap(stats.rejectedByReason()));
        json.append('}');
        return json;
    }

    private static StringBuilder actorIncidents(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        ActorIncidentHealthStats stats = snapshot.actorIncidents();
        json.append('{');
        number(json, "viewCount", stats.viewCount()).append(',');
        number(json, "retainedEntries", stats.retainedEntries()).append(',');
        number(json, "recordedEntries", stats.recordedEntries()).append(',');
        number(json, "deadLetters", stats.deadLetters()).append(',');
        number(json, "poisonMessages", stats.poisonMessages()).append(',');
        number(json, "droppedEntries", stats.droppedEntries()).append(',');
        object(json, "byKind", enumMap(stats.byKind(), ActorIncidentKind.values())).append(',');
        object(json, "byCategory", enumMap(stats.byCategory(), ActorTaskCategory.values())).append(',');
        object(json, "byReason", stringNumberMap(stats.byReason()));
        json.append('}');
        return json;
    }

    private static StringBuilder actorSlowTasks(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        ActorSlowTaskHealthStats stats = snapshot.actorSlowTasks();
        json.append('{');
        number(json, "viewCount", stats.viewCount()).append(',');
        number(json, "retainedEntries", stats.retainedEntries()).append(',');
        number(json, "recordedEntries", stats.recordedEntries()).append(',');
        number(json, "droppedEntries", stats.droppedEntries()).append(',');
        number(json, "maxElapsedMillis", stats.maxElapsedMillis()).append(',');
        field(json, "maxElapsedActorId", stats.maxElapsedActorId()).append(',');
        field(json, "maxElapsedCategory", stats.maxElapsedCategory().name()).append(',');
        object(json, "byCategory", enumMap(stats.byCategory(), ActorTaskCategory.values()));
        json.append('}');
        return json;
    }

    private static StringBuilder actorSchedules(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "registryCount", snapshot.actorSchedules().registryCount()).append(',');
        number(json, "activeJobs", snapshot.actorSchedules().activeJobs()).append(',');
        number(json, "scheduledJobs", snapshot.actorSchedules().scheduledJobs()).append(',');
        number(json, "cancelledJobs", snapshot.actorSchedules().cancelledJobs()).append(',');
        number(json, "deliveredTimerMessages", snapshot.actorSchedules().deliveredTimerMessages()).append(',');
        number(json, "rejectedTimerMessages", snapshot.actorSchedules().rejectedTimerMessages());
        json.append('}');
        return json;
    }

    private static StringBuilder rpc(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "sentRequests", snapshot.rpc().sentRequests()).append(',');
        number(json, "succeededRequests", snapshot.rpc().succeededRequests()).append(',');
        number(json, "failedRequests", snapshot.rpc().failedRequests()).append(',');
        number(json, "timedOutRequests", snapshot.rpc().timedOutRequests()).append(',');
        number(json, "rejectedRequests", snapshot.rpc().rejectedRequests()).append(',');
        number(json, "slowRequests", snapshot.rpc().slowRequests()).append(',');
        number(json, "pendingRequests", snapshot.rpc().pendingRequests()).append(',');
        number(json, "idempotencyCacheSize", snapshot.rpc().idempotencyCacheSize());
        json.append('}');
        return json;
    }

    private static StringBuilder rpcResilience(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "attempts", snapshot.rpcResilience().attempts()).append(',');
        number(json, "retries", snapshot.rpcResilience().retries()).append(',');
        number(json, "shortCircuited", snapshot.rpcResilience().shortCircuited()).append(',');
        number(json, "openedCircuits", snapshot.rpcResilience().openedCircuits()).append(',');
        number(json, "rejectedAfterClose", snapshot.rpcResilience().rejectedAfterClose()).append(',');
        number(json, "circuits", snapshot.rpcResilience().circuits()).append(',');
        number(json, "openCircuits", snapshot.rpcResilience().openCircuits());
        json.append('}');
        return json;
    }

    private static StringBuilder rpcRoutes(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "viewCount", snapshot.rpcRoutes().viewCount()).append(',');
        number(json, "calls", snapshot.rpcRoutes().calls()).append(',');
        number(json, "routedCalls", snapshot.rpcRoutes().routedCalls()).append(',');
        number(json, "unroutedCalls", snapshot.rpcRoutes().unroutedCalls()).append(',');
        object(json, "routeTagCalls", stringNumberMap(snapshot.rpcRoutes().routeTagCalls()));
        json.append('}');
        return json;
    }

    private static StringBuilder actorRpc(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "clientCount", snapshot.actorRpc().clientCount()).append(',');
        number(json, "calls", snapshot.actorRpc().calls()).append(',');
        number(json, "succeededResponses", snapshot.actorRpc().succeededResponses()).append(',');
        number(json, "failedResponses", snapshot.actorRpc().failedResponses()).append(',');
        number(json, "callbackDeliveryFailures", snapshot.actorRpc().callbackDeliveryFailures()).append(',');
        object(json, "failedResponsesByStatus", enumMap(snapshot.actorRpc().failedResponsesByStatus(),
                AgentDeliveryStatus.values()));
        json.append('}');
        return json;
    }

    private static StringBuilder businessAgentMessages(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        BusinessAgentMessageHealthStats stats = snapshot.businessAgentMessages();
        json.append('{');
        number(json, "portCount", stats.portCount()).append(',');
        number(json, "submittedRequests", stats.submittedRequests()).append(',');
        number(json, "localRequests", stats.localRequests()).append(',');
        number(json, "remoteRequests", stats.remoteRequests()).append(',');
        number(json, "rejectedRequests", stats.rejectedRequests()).append(',');
        number(json, "remoteSuccessResponses", stats.remoteSuccessResponses()).append(',');
        number(json, "remoteFailureResponses", stats.remoteFailureResponses()).append(',');
        number(json, "remoteTimedOutResponses", stats.remoteTimedOutResponses()).append(',');
        number(json, "lateRemoteResponses", stats.lateRemoteResponses()).append(',');
        number(json, "callbackDeliveryFailures", stats.callbackDeliveryFailures()).append(',');
        object(json, "callbackDeliveryFailuresByStatus", enumMap(
                stats.callbackDeliveryFailuresByStatus(),
                AgentDeliveryStatus.values()
        ));
        json.append('}');
        return json;
    }

    private static StringBuilder businessAgentRpcEndpoints(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        BusinessAgentRpcEndpointHealthStats stats = snapshot.businessAgentRpcEndpoints();
        json.append('{');
        number(json, "endpointCount", stats.endpointCount()).append(',');
        number(json, "idempotencyCacheCapacity", stats.idempotencyCacheCapacity()).append(',');
        number(json, "idempotencyCacheTtlMillis", stats.idempotencyCacheTtlMillis()).append(',');
        number(json, "idempotencyCacheSize", stats.idempotencyCacheSize()).append(',');
        number(json, "idempotencyStartedRequests", stats.idempotencyStartedRequests()).append(',');
        number(json, "idempotencyJoinedInFlightRequests", stats.idempotencyJoinedInFlightRequests()).append(',');
        number(json, "idempotencyCompletedCacheHits", stats.idempotencyCompletedCacheHits()).append(',');
        number(json, "idempotencyExpiredEntries", stats.idempotencyExpiredEntries()).append(',');
        number(json, "idempotencyEvictedEntries", stats.idempotencyEvictedEntries()).append(',');
        number(json, "idempotencyCompletedSuccesses", stats.idempotencyCompletedSuccesses()).append(',');
        number(json, "idempotencyCompletedFailures", stats.idempotencyCompletedFailures());
        json.append('}');
        return json;
    }


    private static StringBuilder commands(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = enumMap(snapshot.commands().counts(), PlayerCommandStatus.values());
        json.deleteCharAt(json.length() - 1);
        number(json.append(','), "acceptingDispatchers", snapshot.commands().acceptingDispatchers()).append(',');
        number(json, "drainingDispatchers", snapshot.commands().drainingDispatchers());
        json.append('}');
        return json;
    }

    private static StringBuilder businessResponses(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "hubCount", snapshot.businessResponses().hubCount()).append(',');
        number(json, "pendingResponses", snapshot.businessResponses().pendingResponses()).append(',');
        number(json, "submittedResponses", snapshot.businessResponses().submittedResponses()).append(',');
        number(json, "completedResponses", snapshot.businessResponses().completedResponses()).append(',');
        number(json, "cancelledResponses", snapshot.businessResponses().cancelledResponses()).append(',');
        number(json, "timedOutResponses", snapshot.businessResponses().timedOutResponses()).append(',');
        number(json, "fallbackResponses", snapshot.businessResponses().fallbackResponses()).append(',');
        number(json, "sharedWaiters", snapshot.businessResponses().sharedWaiters()).append(',');
        number(json, "replayedResponses", snapshot.businessResponses().replayedResponses()).append(',');
        number(json, "cachedResponses", snapshot.businessResponses().cachedResponses()).append(',');
        number(json, "oldestPendingAgeMillis", snapshot.businessResponses().oldestPendingAgeMillis()).append(',');
        number(json, "failedResponses", snapshot.businessResponses().failedResponses()).append(',');
        object(json, "failedResponsesByCode", stringNumberMap(snapshot.businessResponses().failedResponsesByCode()))
                .append(',');
        number(json, "rejectedBusinessResults", snapshot.businessResponses().rejectedBusinessResults()).append(',');
        object(json, "rejectedBusinessResultsByCode",
                stringNumberMap(snapshot.businessResponses().rejectedBusinessResultsByCode()));
        json.append('}');
        return json;
    }

    private static StringBuilder asyncShopPurchases(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "viewCount", snapshot.asyncShopPurchases().viewCount()).append(',');
        number(json, "startedPurchases", snapshot.asyncShopPurchases().startedPurchases()).append(',');
        number(json, "immediatePurchases", snapshot.asyncShopPurchases().immediatePurchases()).append(',');
        number(json, "stockReservations", snapshot.asyncShopPurchases().stockReservations()).append(',');
        number(json, "reservedCallbacks", snapshot.asyncShopPurchases().reservedCallbacks()).append(',');
        number(json, "outOfStockCallbacks", snapshot.asyncShopPurchases().outOfStockCallbacks()).append(',');
        number(json, "rpcFailures", snapshot.asyncShopPurchases().rpcFailures()).append(',');
        number(json, "lateCallbacks", snapshot.asyncShopPurchases().lateCallbacks()).append(',');
        number(json, "completedPurchases", snapshot.asyncShopPurchases().completedPurchases()).append(',');
        number(json, "rejectedPurchases", snapshot.asyncShopPurchases().rejectedPurchases()).append(',');
        number(json, "releasedReservations", snapshot.asyncShopPurchases().releasedReservations()).append(',');
        number(json, "releaseFailures", snapshot.asyncShopPurchases().releaseFailures());
        json.append('}');
        return json;
    }

    private static StringBuilder agents(RuntimeHealthSnapshot snapshot) {
        return enumMap(snapshot.agents().counts(), AgentLifecycleState.values());
    }

    private static StringBuilder playerAgents(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "managerCount", snapshot.playerAgents().managerCount()).append(',');
        number(json, "loadedAgents", snapshot.playerAgents().loadedAgents()).append(',');
        number(json, "autoSaveSchedulers", snapshot.playerAgents().autoSaveSchedulers()).append(',');
        number(json, "autoSaveRuns", snapshot.playerAgents().autoSaveRuns()).append(',');
        number(json, "autoSaveSubmitted", snapshot.playerAgents().autoSaveSubmitted()).append(',');
        number(json, "autoSaveCompleted", snapshot.playerAgents().autoSaveCompleted()).append(',');
        number(json, "autoSaveFailedRuns", snapshot.playerAgents().autoSaveFailedRuns()).append(',');
        number(json, "autoSaveFailedSaves", snapshot.playerAgents().autoSaveFailedSaves()).append(',');
        number(json, "drainServices", snapshot.playerAgents().drainServices()).append(',');
        number(json, "drainingServices", snapshot.playerAgents().drainingServices()).append(',');
        number(json, "drainSubmitted", snapshot.playerAgents().drainSubmitted()).append(',');
        number(json, "drainCompleted", snapshot.playerAgents().drainCompleted()).append(',');
        number(json, "drainFailedSaves", snapshot.playerAgents().drainFailedSaves()).append(',');
        number(json, "drainPending", snapshot.playerAgents().drainPending());
        json.append('}');
        return json;
    }

    private static StringBuilder agentMigrations(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "initiated", snapshot.agentMigrations().initiated()).append(',');
        number(json, "sourceMoved", snapshot.agentMigrations().sourceMoved()).append(',');
        number(json, "sourceMoveFailed", snapshot.agentMigrations().sourceMoveFailed()).append(',');
        number(json, "targetAccepted", snapshot.agentMigrations().targetAccepted()).append(',');
        number(json, "targetRejected", snapshot.agentMigrations().targetRejected()).append(',');
        number(json, "targetFailed", snapshot.agentMigrations().targetFailed()).append(',');
        number(json, "targetRetries", snapshot.agentMigrations().targetRetries()).append(',');
        number(json, "completionRejected", snapshot.agentMigrations().completionRejected()).append(',');
        number(json, "rollbackSucceeded", snapshot.agentMigrations().rollbackSucceeded()).append(',');
        number(json, "rollbackFailed", snapshot.agentMigrations().rollbackFailed());
        json.append('}');
        return json;
    }

    private static StringBuilder agentMigrationExecutors(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "submitted", snapshot.agentMigrationExecutors().submitted()).append(',');
        number(json, "running", snapshot.agentMigrationExecutors().running()).append(',');
        number(json, "completed", snapshot.agentMigrationExecutors().completed()).append(',');
        number(json, "failed", snapshot.agentMigrationExecutors().failed()).append(',');
        number(json, "rejected", snapshot.agentMigrationExecutors().rejected()).append(',');
        number(json, "queuedTasks", snapshot.agentMigrationExecutors().queuedTasks());
        json.append('}');
        return json;
    }

    private static StringBuilder agentMigrationRecoveries(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "scans", snapshot.agentMigrationRecoveries().scans()).append(',');
        number(json, "recoveredTasks", snapshot.agentMigrationRecoveries().recoveredTasks()).append(',');
        number(json, "targetAccepted", snapshot.agentMigrationRecoveries().targetAccepted()).append(',');
        number(json, "targetRejected", snapshot.agentMigrationRecoveries().targetRejected()).append(',');
        number(json, "targetFailed", snapshot.agentMigrationRecoveries().targetFailed()).append(',');
        number(json, "targetRetries", snapshot.agentMigrationRecoveries().targetRetries()).append(',');
        number(json, "rollbackSucceeded", snapshot.agentMigrationRecoveries().rollbackSucceeded()).append(',');
        number(json, "rollbackFailed", snapshot.agentMigrationRecoveries().rollbackFailed()).append(',');
        number(json, "executorRejected", snapshot.agentMigrationRecoveries().executorRejected());
        json.append('}');
        return json;
    }

    private static StringBuilder agentMigrationRecoverySchedulers(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "runs", snapshot.agentMigrationRecoverySchedulers().runs()).append(',');
        number(json, "claimedTasks", snapshot.agentMigrationRecoverySchedulers().claimedTasks()).append(',');
        number(json, "failedRuns", snapshot.agentMigrationRecoverySchedulers().failedRuns());
        json.append('}');
        return json;
    }

    private static StringBuilder agentMigrationTaskRetentions(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "runs", snapshot.agentMigrationTaskRetentions().runs()).append(',');
        number(json, "purgedTasks", snapshot.agentMigrationTaskRetentions().purgedTasks()).append(',');
        number(json, "failedRuns", snapshot.agentMigrationTaskRetentions().failedRuns());
        json.append('}');
        return json;
    }

    private static StringBuilder agentMigrationTaskStores(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "stores", snapshot.agentMigrationTaskStores().stores()).append(',');
        number(json, "totalTasks", snapshot.agentMigrationTaskStores().totalTasks()).append(',');
        number(json, "preparedTasks", snapshot.agentMigrationTaskStores().preparedTasks()).append(',');
        number(json, "movedTasks", snapshot.agentMigrationTaskStores().movedTasks()).append(',');
        number(json, "terminalTasks", snapshot.agentMigrationTaskStores().terminalTasks()).append(',');
        number(json, "leasedPendingTasks", snapshot.agentMigrationTaskStores().leasedPendingTasks()).append(',');
        number(json, "oldestPendingAgeMillis", snapshot.agentMigrationTaskStores().oldestPendingAgeMillis());
        json.append('}');
        return json;
    }

    private static StringBuilder outbox(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "pendingEvents", snapshot.outbox().pendingEvents()).append(',');
        number(json, "failedAttempts", snapshot.outbox().failedAttempts()).append(',');
        number(json, "oldestPendingAgeMillis", snapshot.outbox().oldestPendingAgeMillis());
        json.append('}');
        return json;
    }

    private static StringBuilder cluster(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = enumMap(snapshot.cluster().counts(), ServiceKind.values());
        json.deleteCharAt(json.length() - 1);
        object(json.append(','), "draining", enumMap(snapshot.cluster().drainingCounts(), ServiceKind.values())).append(',');
        object(json, "versions", enumMap(snapshot.cluster().versions(), ServiceKind.values())).append(',');
        object(json, "routeTags", serviceKindStringNumberMap(snapshot.cluster().routeTagCounts())).append(',');
        object(json, "deploymentGroups", serviceKindStringNumberMap(snapshot.cluster().deploymentGroupCounts()));
        json.append('}');
        return json;
    }

    private static StringBuilder registryLeases(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "renewers", snapshot.registryLeases().renewers()).append(',');
        number(json, "successfulHeartbeats", snapshot.registryLeases().successfulHeartbeats()).append(',');
        number(json, "reRegistrations", snapshot.registryLeases().reRegistrations()).append(',');
        number(json, "failedRenewals", snapshot.registryLeases().failedRenewals()).append(',');
        number(json, "reapers", snapshot.registryLeases().reapers()).append(',');
        number(json, "expiredServices", snapshot.registryLeases().expiredServices());
        json.append('}');
        return json;
    }

    private static StringBuilder registryHistory(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "viewCount", snapshot.registryHistory().viewCount()).append(',');
        number(json, "currentVersion", snapshot.registryHistory().currentVersion()).append(',');
        number(json, "minReplayVersion", snapshot.registryHistory().minReplayVersion()).append(',');
        number(json, "retainedEvents", snapshot.registryHistory().retainedEvents()).append(',');
        number(json, "historyLimit", snapshot.registryHistory().historyLimit()).append(',');
        number(json, "compactedReplayRequests", snapshot.registryHistory().compactedReplayRequests());
        json.append('}');
        return json;
    }

    private static StringBuilder remoteRegistryRecoveries(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "schedulerCount", snapshot.remoteRegistryRecoveries().schedulerCount()).append(',');
        number(json, "runs", snapshot.remoteRegistryRecoveries().runs()).append(',');
        number(json, "succeededRuns", snapshot.remoteRegistryRecoveries().succeededRuns()).append(',');
        number(json, "failedRuns", snapshot.remoteRegistryRecoveries().failedRuns()).append(',');
        number(json, "skippedRuns", snapshot.remoteRegistryRecoveries().skippedRuns()).append(',');
        number(json, "recoveredKinds", snapshot.remoteRegistryRecoveries().recoveredKinds()).append(',');
        number(json, "inFlight", snapshot.remoteRegistryRecoveries().inFlight());
        json.append('}');
        return json;
    }

    private static StringBuilder registrySubscriptions(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "viewCount", snapshot.registrySubscriptions().viewCount()).append(',');
        number(json, "subscribedKinds", snapshot.registrySubscriptions().subscribedKinds()).append(',');
        number(json, "subscribers", snapshot.registrySubscriptions().subscribers()).append(',');
        number(json, "references", snapshot.registrySubscriptions().references()).append(',');
        number(json, "subscribeRequests", snapshot.registrySubscriptions().subscribeRequests()).append(',');
        number(json, "unsubscribeRequests", snapshot.registrySubscriptions().unsubscribeRequests()).append(',');
        number(json, "cleanedSubscribers", snapshot.registrySubscriptions().cleanedSubscribers()).append(',');
        number(json, "expiredSubscriptions", snapshot.registrySubscriptions().expiredSubscriptions());
        json.append('}');
        return json;
    }

    private static StringBuilder serviceDescriptorPublishers(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "publisherCount", snapshot.serviceDescriptorPublishers().publisherCount()).append(',');
        number(json, "drainingPublishers", snapshot.serviceDescriptorPublishers().drainingPublishers()).append(',');
        number(json, "attempts", snapshot.serviceDescriptorPublishers().attempts()).append(',');
        number(json, "succeeded", snapshot.serviceDescriptorPublishers().succeeded()).append(',');
        number(json, "failed", snapshot.serviceDescriptorPublishers().failed());
        json.append('}');
        return json;
    }

    private static StringBuilder networkTransports(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "transportCount", snapshot.networkTransports().transportCount()).append(',');
        number(json, "activeConnections", snapshot.networkTransports().activeConnections()).append(',');
        number(json, "connectionAttempts", snapshot.networkTransports().connectionAttempts()).append(',');
        number(json, "connectionFailures", snapshot.networkTransports().connectionFailures()).append(',');
        number(json, "sentEnvelopes", snapshot.networkTransports().sentEnvelopes()).append(',');
        number(json, "failedWrites", snapshot.networkTransports().failedWrites()).append(',');
        number(json, "receivedEnvelopes", snapshot.networkTransports().receivedEnvelopes()).append(',');
        number(json, "inboundFailures", snapshot.networkTransports().inboundFailures());
        json.append('}');
        return json;
    }

    private static StringBuilder configCaches(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "cacheCount", snapshot.configCaches().cacheCount()).append(',');
        number(json, "activeCaches", snapshot.configCaches().activeCaches()).append(',');
        number(json, "readyCaches", snapshot.configCaches().readyCaches()).append(',');
        number(json, "staleCaches", snapshot.configCaches().staleCaches()).append(',');
        number(json, "minAppliedEventRevision", snapshot.configCaches().minAppliedEventRevision()).append(',');
        number(json, "maxAppliedEventRevision", snapshot.configCaches().maxAppliedEventRevision());
        json.append('}');
        return json;
    }

    private static StringBuilder configRecoveries(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "recoveryCount", snapshot.configRecoveries().recoveryCount()).append(',');
        number(json, "requested", snapshot.configRecoveries().requested()).append(',');
        number(json, "skippedWhileInFlight", snapshot.configRecoveries().skippedWhileInFlight()).append(',');
        number(json, "succeeded", snapshot.configRecoveries().succeeded()).append(',');
        number(json, "failed", snapshot.configRecoveries().failed()).append(',');
        number(json, "inFlight", snapshot.configRecoveries().inFlight());
        json.append('}');
        return json;
    }

    private static StringBuilder eventSubscriptions(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "managerCount", snapshot.eventSubscriptions().managerCount()).append(',');
        number(json, "registered", snapshot.eventSubscriptions().registered()).append(',');
        number(json, "active", snapshot.eventSubscriptions().active()).append(',');
        number(json, "subscribeAttempts", snapshot.eventSubscriptions().subscribeAttempts()).append(',');
        number(json, "subscribeFailures", snapshot.eventSubscriptions().subscribeFailures()).append(',');
        number(json, "replayAttempts", snapshot.eventSubscriptions().replayAttempts()).append(',');
        number(json, "replayFailures", snapshot.eventSubscriptions().replayFailures()).append(',');
        number(json, "replayDelivered", snapshot.eventSubscriptions().replayDelivered()).append(',');
        number(json, "replayUnavailableOwners", snapshot.eventSubscriptions().replayUnavailableOwners()).append(',');
        number(json, "replayRepairRequests", snapshot.eventSubscriptions().replayRepairRequests()).append(',');
        number(json, "replayRepairOwnerCount", snapshot.eventSubscriptions().replayRepairOwnerCount()).append(',');
        number(json, "replayRepairFailures", snapshot.eventSubscriptions().replayRepairFailures()).append(',');
        number(json, "cursorFailures", snapshot.eventSubscriptions().cursorFailures());
        json.append('}');
        return json;
    }

    private static StringBuilder eventCenters(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "centerCount", snapshot.eventCenters().centerCount()).append(',');
        number(json, "topicCount", snapshot.eventCenters().topicCount()).append(',');
        number(json, "retainedEvents", snapshot.eventCenters().retainedEvents()).append(',');
        number(json, "retainedOwners", snapshot.eventCenters().retainedOwners()).append(',');
        number(json, "subscribers", snapshot.eventCenters().subscribers()).append(',');
        number(json, "publishedEvents", snapshot.eventCenters().publishedEvents()).append(',');
        number(json, "droppedEvents", snapshot.eventCenters().droppedEvents()).append(',');
        number(json, "deliveryFailures", snapshot.eventCenters().deliveryFailures()).append(',');
        number(json, "expiredSubscriptions", snapshot.eventCenters().expiredSubscriptions()).append(',');
        object(json, "topics", eventCenterTopics(snapshot));
        json.append('}');
        return json;
    }

    private static StringBuilder eventCenterTopics(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        boolean first = true;
        for (Map.Entry<String, EventCenterTopicHealthStats> entry : snapshot.eventCenters().topics().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            object(json, entry.getKey(), eventCenterTopic(entry.getValue()));
        }
        json.append('}');
        return json;
    }

    private static StringBuilder eventCenterTopic(EventCenterTopicHealthStats topic) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "historyLimit", topic.historyLimit()).append(',');
        number(json, "retainedEvents", topic.retainedEvents()).append(',');
        number(json, "retainedOwners", topic.retainedOwners()).append(',');
        number(json, "subscribers", topic.subscribers()).append(',');
        number(json, "publishedEvents", topic.publishedEvents()).append(',');
        number(json, "droppedEvents", topic.droppedEvents()).append(',');
        number(json, "deliveryFailures", topic.deliveryFailures()).append(',');
        number(json, "expiredSubscriptions", topic.expiredSubscriptions()).append(',');
        number(json, "minRetainedRevision", topic.minRetainedRevision()).append(',');
        number(json, "maxRetainedRevision", topic.maxRetainedRevision());
        json.append('}');
        return json;
    }

    private static StringBuilder actorEventSubscribers(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "subscriberCount", snapshot.actorEventSubscribers().subscriberCount()).append(',');
        number(json, "receivedEvents", snapshot.actorEventSubscribers().receivedEvents()).append(',');
        number(json, "enqueuedEvents", snapshot.actorEventSubscribers().enqueuedEvents()).append(',');
        number(json, "rejectedEvents", snapshot.actorEventSubscribers().rejectedEvents()).append(',');
        number(json, "handledEvents", snapshot.actorEventSubscribers().handledEvents()).append(',');
        number(json, "failedEvents", snapshot.actorEventSubscribers().failedEvents());
        json.append('}');
        return json;
    }

    private static StringBuilder ownerActorEventSubscriptions(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "subscriptionCount", snapshot.ownerActorEventSubscriptions().subscriptionCount()).append(',');
        number(json, "watchedOwners", snapshot.ownerActorEventSubscriptions().watchedOwners()).append(',');
        number(json, "watchReferences", snapshot.ownerActorEventSubscriptions().watchReferences()).append(',');
        number(json, "watchRequests", snapshot.ownerActorEventSubscriptions().watchRequests()).append(',');
        number(json, "unwatchRequests", snapshot.ownerActorEventSubscriptions().unwatchRequests()).append(',');
        number(json, "subscribeRequests", snapshot.ownerActorEventSubscriptions().subscribeRequests()).append(',');
        number(json, "unsubscribeRequests", snapshot.ownerActorEventSubscriptions().unsubscribeRequests()).append(',');
        number(json, "replayAttempts", snapshot.ownerActorEventSubscriptions().replayAttempts()).append(',');
        number(json, "replayFailures", snapshot.ownerActorEventSubscriptions().replayFailures()).append(',');
        number(json, "repairRequests", snapshot.ownerActorEventSubscriptions().repairRequests()).append(',');
        number(json, "repairOwnerCount", snapshot.ownerActorEventSubscriptions().repairOwnerCount()).append(',');
        number(json, "repairFailures", snapshot.ownerActorEventSubscriptions().repairFailures());
        json.append('}');
        return json;
    }

    private static StringBuilder ownerEventRepairSchedulers(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "schedulerCount", snapshot.ownerEventRepairSchedulers().schedulerCount()).append(',');
        number(json, "pendingOwners", snapshot.ownerEventRepairSchedulers().pendingOwners()).append(',');
        number(json, "maxDefaultPriority", snapshot.ownerEventRepairSchedulers().maxDefaultPriority()).append(',');
        number(json, "highestPendingPriority", snapshot.ownerEventRepairSchedulers().highestPendingPriority()).append(',');
        number(json, "repairRequests", snapshot.ownerEventRepairSchedulers().repairRequests()).append(',');
        number(json, "requestedOwners", snapshot.ownerEventRepairSchedulers().requestedOwners()).append(',');
        number(json, "enqueuedOwners", snapshot.ownerEventRepairSchedulers().enqueuedOwners()).append(',');
        number(json, "duplicateOwners", snapshot.ownerEventRepairSchedulers().duplicateOwners()).append(',');
        number(json, "dispatchRuns", snapshot.ownerEventRepairSchedulers().dispatchRuns()).append(',');
        number(json, "dispatchedOwners", snapshot.ownerEventRepairSchedulers().dispatchedOwners()).append(',');
        number(json, "failedRuns", snapshot.ownerEventRepairSchedulers().failedRuns()).append(',');
        number(json, "skippedRuns", snapshot.ownerEventRepairSchedulers().skippedRuns()).append(',');
        number(json, "backoffSkips", snapshot.ownerEventRepairSchedulers().backoffSkips()).append(',');
        number(json, "maxConsecutiveFailures", snapshot.ownerEventRepairSchedulers().maxConsecutiveFailures()).append(',');
        name(json, "backoffActive").append(snapshot.ownerEventRepairSchedulers().backoffActive()).append(',');
        number(json, "maxBackoffRemainingMillis", snapshot.ownerEventRepairSchedulers().maxBackoffRemainingMillis()).append(',');
        number(json, "isolatedOwners", snapshot.ownerEventRepairSchedulers().isolatedOwners()).append(',');
        number(json, "isolatedOwnersTotal", snapshot.ownerEventRepairSchedulers().isolatedOwnersTotal()).append(',');
        number(json, "isolationSkips", snapshot.ownerEventRepairSchedulers().isolationSkips()).append(',');
        number(json, "releasedIsolatedOwners", snapshot.ownerEventRepairSchedulers().releasedIsolatedOwners()).append(',');
        name(json, "singleOwnerProbeMode").append(snapshot.ownerEventRepairSchedulers().singleOwnerProbeMode()).append(',');
        name(json, "inFlight").append(snapshot.ownerEventRepairSchedulers().inFlight());
        json.append('}');
        return json;
    }

    private static StringBuilder ownerEventRepairDispatchers(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "dispatcherCount", snapshot.ownerEventRepairDispatchers().dispatcherCount()).append(',');
        number(json, "registeredSchedulers", snapshot.ownerEventRepairDispatchers().registeredSchedulers()).append(',');
        number(json, "dueSchedulers", snapshot.ownerEventRepairDispatchers().dueSchedulers()).append(',');
        number(json, "pendingSchedulers", snapshot.ownerEventRepairDispatchers().pendingSchedulers()).append(',');
        number(json, "eligibleSchedulers", snapshot.ownerEventRepairDispatchers().eligibleSchedulers()).append(',');
        number(json, "highestPendingPriority", snapshot.ownerEventRepairDispatchers().highestPendingPriority()).append(',');
        number(json, "maxDrainsPerTick", snapshot.ownerEventRepairDispatchers().maxDrainsPerTick()).append(',');
        number(json, "maxTickIntervalMillis", snapshot.ownerEventRepairDispatchers().maxTickIntervalMillis()).append(',');
        number(json, "drainAttempts", snapshot.ownerEventRepairDispatchers().drainAttempts()).append(',');
        number(json, "selectedSchedulers", snapshot.ownerEventRepairDispatchers().selectedSchedulers()).append(',');
        number(json, "drainedOwners", snapshot.ownerEventRepairDispatchers().drainedOwners()).append(',');
        number(json, "failedSchedulerRuns", snapshot.ownerEventRepairDispatchers().failedSchedulerRuns()).append(',');
        number(json, "limitedRuns", snapshot.ownerEventRepairDispatchers().limitedRuns()).append(',');
        number(json, "emptyRuns", snapshot.ownerEventRepairDispatchers().emptyRuns()).append(',');
        number(json, "skippedRuns", snapshot.ownerEventRepairDispatchers().skippedRuns()).append(',');
        name(json, "inFlight").append(snapshot.ownerEventRepairDispatchers().inFlight());
        json.append('}');
        return json;
    }

    private static StringBuilder ownerRepairOps(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        OwnerRepairOpsAuditHealthStats stats = snapshot.ownerRepairOpsAudits();
        json.append('{');
        number(json, "auditCount", stats.auditCount()).append(',');
        number(json, "retainedEntries", stats.retainedEntries()).append(',');
        number(json, "recordedEntries", stats.recordedEntries()).append(',');
        number(json, "releaseOps", stats.releaseOps()).append(',');
        number(json, "releaseAllOps", stats.releaseAllOps()).append(',');
        number(json, "releasedOwners", stats.releasedOwners()).append(',');
        number(json, "notFoundReleaseOps", stats.notFoundReleaseOps()).append(',');
        number(json, "droppedEntries", stats.droppedEntries());
        json.append('}');
        return json;
    }

    private static StringBuilder profileInterests(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "subscriptionCount", snapshot.profileInterests().subscriptionCount()).append(',');
        number(json, "watchedOwners", snapshot.profileInterests().watchedOwners()).append(',');
        number(json, "watchReferences", snapshot.profileInterests().watchReferences()).append(',');
        number(json, "watchRequests", snapshot.profileInterests().watchRequests()).append(',');
        number(json, "unwatchRequests", snapshot.profileInterests().unwatchRequests()).append(',');
        number(json, "replayAttempts", snapshot.profileInterests().replayAttempts()).append(',');
        number(json, "replayFailures", snapshot.profileInterests().replayFailures()).append(',');
        number(json, "repairRequests", snapshot.profileInterests().repairRequests()).append(',');
        number(json, "repairFailures", snapshot.profileInterests().repairFailures());
        json.append('}');
        return json;
    }

    private static StringBuilder profileRuntimes(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "runtimeCount", snapshot.profileRuntimes().runtimeCount()).append(',');
        number(json, "readRequests", snapshot.profileRuntimes().readRequests()).append(',');
        number(json, "localHits", snapshot.profileRuntimes().localHits()).append(',');
        number(json, "localStale", snapshot.profileRuntimes().localStale()).append(',');
        number(json, "localMisses", snapshot.profileRuntimes().localMisses()).append(',');
        number(json, "refreshes", snapshot.profileRuntimes().refreshes()).append(',');
        number(json, "remoteStale", snapshot.profileRuntimes().remoteStale()).append(',');
        number(json, "remoteMisses", snapshot.profileRuntimes().remoteMisses()).append(',');
        number(json, "localFallbacks", snapshot.profileRuntimes().localFallbacks());
        json.append('}');
        return json;
    }

    private static StringBuilder chatRuntimes(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "runtimeCount", snapshot.chatRuntimes().runtimeCount()).append(',');
        number(json, "activeChannels", snapshot.chatRuntimes().activeChannels()).append(',');
        number(json, "activeDirectSessions", snapshot.chatRuntimes().activeDirectSessions()).append(',');
        number(json, "joinRequests", snapshot.chatRuntimes().joinRequests()).append(',');
        number(json, "leaveRequests", snapshot.chatRuntimes().leaveRequests()).append(',');
        number(json, "sendRequests", snapshot.chatRuntimes().sendRequests()).append(',');
        number(json, "mutedRejects", snapshot.chatRuntimes().mutedRejects()).append(',');
        number(json, "blockedRejects", snapshot.chatRuntimes().blockedRejects()).append(',');
        number(json, "retainedMessages", snapshot.chatRuntimes().retainedMessages()).append(',');
        number(json, "droppedHistoryMessages", snapshot.chatRuntimes().droppedHistoryMessages()).append(',');
        number(json, "acceptedDeliveryRecipients", snapshot.chatRuntimes().acceptedDeliveryRecipients()).append(',');
        number(json, "droppedDeliveryRecipients", snapshot.chatRuntimes().droppedDeliveryRecipients()).append(',');
        number(json, "failedDeliveryRecipients", snapshot.chatRuntimes().failedDeliveryRecipients()).append(',');
        number(json, "allianceRemovedMembers", snapshot.chatRuntimes().allianceRemovedMembers()).append(',');
        number(json, "allianceEventRemovedMembers", snapshot.chatRuntimes().allianceEventRemovedMembers()).append(',');
        number(json, "allianceSnapshotRemovedMembers", snapshot.chatRuntimes().allianceSnapshotRemovedMembers());
        json.append('}');
        return json;
    }

    private static StringBuilder playerOutboundDeliveries(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "runtimeCount", snapshot.playerOutboundDeliveries().runtimeCount()).append(',');
        number(json, "activeConnections", snapshot.playerOutboundDeliveries().activeConnections()).append(',');
        number(json, "offlinePlayers", snapshot.playerOutboundDeliveries().offlinePlayers()).append(',');
        number(json, "pendingOfflineMessages", snapshot.playerOutboundDeliveries().pendingOfflineMessages()).append(',');
        number(json, "pendingAckPlayers", snapshot.playerOutboundDeliveries().pendingAckPlayers()).append(',');
        number(json, "pendingAckMessages", snapshot.playerOutboundDeliveries().pendingAckMessages()).append(',');
        number(json, "oldestPendingAckAgeMillis", snapshot.playerOutboundDeliveries().oldestPendingAckAgeMillis()).append(',');
        number(json, "onlineDeliveries", snapshot.playerOutboundDeliveries().onlineDeliveries()).append(',');
        number(json, "offlineQueuedDeliveries", snapshot.playerOutboundDeliveries().offlineQueuedDeliveries()).append(',');
        number(json, "droppedDeliveries", snapshot.playerOutboundDeliveries().droppedDeliveries()).append(',');
        number(json, "coalescedDeliveries", snapshot.playerOutboundDeliveries().coalescedDeliveries()).append(',');
        number(json, "failedOnlineDeliveries", snapshot.playerOutboundDeliveries().failedOnlineDeliveries()).append(',');
        number(json, "ackedDeliveries", snapshot.playerOutboundDeliveries().ackedDeliveries()).append(',');
        object(json, "topics", playerOutboundDeliveryTopics(snapshot));
        json.append('}');
        return json;
    }

    private static StringBuilder playerOutboundDeliveryTopics(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        boolean first = true;
        for (Map.Entry<String, PlayerOutboundTopicDeliveryStats> entry : snapshot.playerOutboundDeliveries().topics().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            object(json, entry.getKey(), playerOutboundDeliveryTopic(entry.getValue()));
        }
        json.append('}');
        return json;
    }

    private static StringBuilder playerOutboundDeliveryTopic(PlayerOutboundTopicDeliveryStats topic) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "pendingOfflineMessages", topic.pendingOfflineMessages()).append(',');
        number(json, "pendingAckMessages", topic.pendingAckMessages()).append(',');
        number(json, "oldestPendingAckAgeMillis", topic.oldestPendingAckAgeMillis()).append(',');
        number(json, "onlineDeliveries", topic.onlineDeliveries()).append(',');
        number(json, "offlineQueuedDeliveries", topic.offlineQueuedDeliveries()).append(',');
        number(json, "droppedDeliveries", topic.droppedDeliveries()).append(',');
        number(json, "coalescedDeliveries", topic.coalescedDeliveries()).append(',');
        number(json, "failedOnlineDeliveries", topic.failedOnlineDeliveries()).append(',');
        number(json, "ackedDeliveries", topic.ackedDeliveries());
        json.append('}');
        return json;
    }

    private static StringBuilder playerGateways(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "gatewayCount", snapshot.playerGateways().gatewayCount()).append(',');
        number(json, "acceptedLogins", snapshot.playerGateways().acceptedLogins()).append(',');
        number(json, "failedLogins", snapshot.playerGateways().failedLogins()).append(',');
        number(json, "authRejectedLogins", snapshot.playerGateways().authRejectedLogins()).append(',');
        number(json, "duplicateRejectedLogins", snapshot.playerGateways().duplicateRejectedLogins()).append(',');
        number(json, "kickedConnections", snapshot.playerGateways().kickedConnections()).append(',');
        number(json, "acceptedCommands", snapshot.playerGateways().acceptedCommands()).append(',');
        number(json, "rejectedCommands", snapshot.playerGateways().rejectedCommands()).append(',');
        number(json, "rateLimitedCommands", snapshot.playerGateways().rateLimitedCommands()).append(',');
        number(json, "acceptedHeartbeats", snapshot.playerGateways().acceptedHeartbeats()).append(',');
        number(json, "rejectedHeartbeats", snapshot.playerGateways().rejectedHeartbeats()).append(',');
        number(json, "rateLimitedHeartbeats", snapshot.playerGateways().rateLimitedHeartbeats()).append(',');
        number(json, "acceptedAcks", snapshot.playerGateways().acceptedAcks()).append(',');
        number(json, "rejectedAcks", snapshot.playerGateways().rejectedAcks()).append(',');
        number(json, "slowClientClosures", snapshot.playerGateways().slowClientClosures()).append(',');
        number(json, "invalidFrames", snapshot.playerGateways().invalidFrames()).append(',');
        number(json, "disconnectedSessions", snapshot.playerGateways().disconnectedSessions()).append(',');
        number(json, "idleTimeouts", snapshot.playerGateways().idleTimeouts()).append(',');
        object(json, "rejectedCommandsByCode", enumMap(snapshot.playerGateways().rejectedCommandsByCode(),
                PlayerClientErrorCode.values()));
        json.append('}');
        return json;
    }

    private static StringBuilder sceneRuntimes(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "runtimeCount", snapshot.sceneRuntimes().runtimeCount()).append(',');
        number(json, "activeScenes", snapshot.sceneRuntimes().activeScenes()).append(',');
        number(json, "activePlayers", snapshot.sceneRuntimes().activePlayers()).append(',');
        number(json, "shardCount", snapshot.sceneRuntimes().shardCount()).append(',');
        number(json, "maxShardPlayers", snapshot.sceneRuntimes().maxShardPlayers()).append(',');
        number(json, "playerInterests", snapshot.sceneRuntimes().playerInterests()).append(',');
        number(json, "allianceReferences", snapshot.sceneRuntimes().allianceReferences()).append(',');
        number(json, "duplicateEnters", snapshot.sceneRuntimes().duplicateEnters()).append(',');
        number(json, "missingLeaves", snapshot.sceneRuntimes().missingLeaves()).append(',');
        number(json, "projectionReceivedEvents", snapshot.sceneRuntimes().projectionReceivedEvents()).append(',');
        number(json, "projectionAppliedEvents", snapshot.sceneRuntimes().projectionAppliedEvents()).append(',');
        number(json, "projectionDuplicateEvents", snapshot.sceneRuntimes().projectionDuplicateEvents()).append(',');
        number(json, "projectionGapEvents", snapshot.sceneRuntimes().projectionGapEvents()).append(',');
        number(json, "projectionRepairRequests", snapshot.sceneRuntimes().projectionRepairRequests()).append(',');
        number(json, "projectionAppliedSnapshots", snapshot.sceneRuntimes().projectionAppliedSnapshots()).append(',');
        number(json, "projectionIgnoredSnapshots", snapshot.sceneRuntimes().projectionIgnoredSnapshots()).append(',');
        number(json, "projectionStaleViews", snapshot.sceneRuntimes().projectionStaleViews());
        json.append('}');
        return json;
    }

    private static StringBuilder shopRuntimes(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "runtimeCount", snapshot.shopRuntimes().runtimeCount()).append(',');
        number(json, "purchaseRequests", snapshot.shopRuntimes().purchaseRequests()).append(',');
        number(json, "successfulPurchases", snapshot.shopRuntimes().successfulPurchases()).append(',');
        number(json, "idempotentReplays", snapshot.shopRuntimes().idempotentReplays()).append(',');
        number(json, "unknownItems", snapshot.shopRuntimes().unknownItems()).append(',');
        number(json, "lifetimeLimitRejected", snapshot.shopRuntimes().lifetimeLimitRejected()).append(',');
        number(json, "dailyLimitRejected", snapshot.shopRuntimes().dailyLimitRejected()).append(',');
        number(json, "notEnoughCurrency", snapshot.shopRuntimes().notEnoughCurrency()).append(',');
        number(json, "outOfStock", snapshot.shopRuntimes().outOfStock()).append(',');
        number(json, "orderConflicts", snapshot.shopRuntimes().orderConflicts()).append(',');
        number(json, "recordedOrders", snapshot.shopRuntimes().recordedOrders()).append(',');
        number(json, "activeReservations", snapshot.shopRuntimes().activeReservations()).append(',');
        number(json, "reservationReapRuns", snapshot.shopRuntimes().reservationReapRuns()).append(',');
        number(json, "reapedReservations", snapshot.shopRuntimes().reapedReservations()).append(',');
        number(json, "reservationReapFailures", snapshot.shopRuntimes().reservationReapFailures());
        json.append('}');
        return json;
    }

    private static StringBuilder commandAudits(RuntimeHealthSnapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        number(json, "total", snapshot.commandAudits().total()).append(',');
        number(json, "retained", snapshot.commandAudits().retained()).append(',');
        number(json, "dropped", snapshot.commandAudits().dropped()).append(',');
        number(json, "executed", snapshot.commandAudits().executed()).append(',');
        number(json, "failed", snapshot.commandAudits().failed()).append(',');
        number(json, "rejected", snapshot.commandAudits().rejected()).append(',');
        number(json, "routedRemote", snapshot.commandAudits().routedRemote()).append(',');
        number(json, "asyncCompleted", snapshot.commandAudits().asyncCompleted()).append(',');
        number(json, "asyncFailed", snapshot.commandAudits().asyncFailed()).append(',');
        number(json, "maxElapsedMillis", snapshot.commandAudits().maxElapsedMillis()).append(',');
        object(json, "configVersionCounts", numberMap(snapshot.commandAudits().configVersionCounts())).append(',');
        object(json, "resultCodeCounts", stringNumberMap(snapshot.commandAudits().resultCodeCounts()));
        json.append('}');
        return json;
    }

    private static <E extends Enum<E>> StringBuilder enumMap(Map<E, ? extends Number> values, E[] keys) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            Number value = values.get(keys[i]);
            number(json, keys[i].name(), value == null ? 0 : value.longValue());
        }
        json.append('}');
        return json;
    }

    private static StringBuilder numberMap(Map<Long, Long> values) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        boolean first = true;
        for (Map.Entry<Long, Long> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            number(json, String.valueOf(entry.getKey()), entry.getValue());
        }
        json.append('}');
        return json;
    }

    private static StringBuilder stringNumberMap(Map<String, Long> values) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        boolean first = true;
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            number(json, entry.getKey(), entry.getValue());
        }
        json.append('}');
        return json;
    }

    private static StringBuilder serviceKindStringNumberMap(Map<ServiceKind, Map<String, Integer>> values) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        ServiceKind[] kinds = ServiceKind.values();
        for (int i = 0; i < kinds.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            object(json, kinds[i].name(), stringIntegerMap(values.getOrDefault(kinds[i], Map.of())));
        }
        json.append('}');
        return json;
    }

    private static StringBuilder stringIntegerMap(Map<String, Integer> values) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        boolean first = true;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            number(json, entry.getKey(), entry.getValue());
        }
        json.append('}');
        return json;
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        return name(json, name).append('"').append(escape(value)).append('"');
    }

    private static StringBuilder number(StringBuilder json, String name, long value) {
        return name(json, name).append(value);
    }

    private static StringBuilder object(StringBuilder json, String name, StringBuilder value) {
        return name(json, name).append(value);
    }

    private static StringBuilder name(StringBuilder json, String name) {
        return json.append('"').append(escape(name)).append('"').append(':');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
