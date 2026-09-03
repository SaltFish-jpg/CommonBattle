package com.commonbattle.observability;

import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.cluster.ServiceKind;
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
        object(json, "rpc", rpc(snapshot)).append(',');
        object(json, "rpcResilience", rpcResilience(snapshot)).append(',');
        object(json, "actorRpc", actorRpc(snapshot)).append(',');
        object(json, "commands", commands(snapshot)).append(',');
        object(json, "agents", agents(snapshot)).append(',');
        object(json, "outbox", outbox(snapshot)).append(',');
        object(json, "cluster", cluster(snapshot)).append(',');
        object(json, "registryLeases", registryLeases(snapshot)).append(',');
        object(json, "networkTransports", networkTransports(snapshot)).append(',');
        object(json, "configCaches", configCaches(snapshot)).append(',');
        object(json, "configRecoveries", configRecoveries(snapshot)).append(',');
        object(json, "eventCenters", eventCenters(snapshot)).append(',');
        object(json, "eventSubscriptions", eventSubscriptions(snapshot)).append(',');
        object(json, "profileInterests", profileInterests(snapshot)).append(',');
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
        object(json, "queuedTasksByCategory", enumMap(snapshot.actorSystem().queuedTasksByCategory(),
                ActorTaskCategory.values())).append(',');
        object(json, "rejectedTasksByCategory", enumMap(snapshot.actorSystem().rejectedTasksByCategory(),
                ActorTaskCategory.values())).append(',');
        object(json, "droppedTasksByCategory", enumMap(snapshot.actorSystem().droppedTasksByCategory(),
                ActorTaskCategory.values()));
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


    private static StringBuilder commands(RuntimeHealthSnapshot snapshot) {
        return enumMap(snapshot.commands().counts(), PlayerCommandStatus.values());
    }

    private static StringBuilder agents(RuntimeHealthSnapshot snapshot) {
        return enumMap(snapshot.agents().counts(), AgentLifecycleState.values());
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
        return enumMap(snapshot.cluster().counts(), ServiceKind.values());
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
        number(json, "minRetainedRevision", topic.minRetainedRevision()).append(',');
        number(json, "maxRetainedRevision", topic.maxRetainedRevision());
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
        number(json, "maxElapsedMillis", snapshot.commandAudits().maxElapsedMillis()).append(',');
        object(json, "configVersionCounts", numberMap(snapshot.commandAudits().configVersionCounts()));
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
