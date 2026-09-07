package com.commonbattle.observability;

import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.session.PlayerCommandStatus;

import java.util.Map;
import java.util.Objects;

/**
 * 运行时健康快照的 Prometheus 文本指标渲染器。
 * 指标名保持稳定，枚举维度通过 label 展开，方便线上告警和看板聚合。
 */
public final class RuntimeMetricsFormatter {
    private RuntimeMetricsFormatter() {
    }

    public static String format(RuntimeHealthSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder metrics = new StringBuilder(2048);
        status(metrics, snapshot);
        actorSystem(metrics, snapshot);
        rpc(metrics, snapshot);
        rpcResilience(metrics, snapshot);
        actorRpc(metrics, snapshot);
        commands(metrics, snapshot);
        agents(metrics, snapshot);
        playerAgents(metrics, snapshot);
        agentMigrations(metrics, snapshot);
        agentMigrationExecutors(metrics, snapshot);
        agentMigrationRecoveries(metrics, snapshot);
        agentMigrationRecoverySchedulers(metrics, snapshot);
        agentMigrationTaskRetentions(metrics, snapshot);
        agentMigrationTaskStores(metrics, snapshot);
        outbox(metrics, snapshot);
        cluster(metrics, snapshot);
        registryLeases(metrics, snapshot);
        serviceDescriptorPublishers(metrics, snapshot);
        networkTransports(metrics, snapshot);
        configCaches(metrics, snapshot);
        configRecoveries(metrics, snapshot);
        eventCenters(metrics, snapshot);
        eventSubscriptions(metrics, snapshot);
        profileInterests(metrics, snapshot);
        profileRuntimes(metrics, snapshot);
        sceneRuntimes(metrics, snapshot);
        shopRuntimes(metrics, snapshot);
        commandAudits(metrics, snapshot);
        return metrics.toString();
    }

    private static void status(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        help(metrics, "commonbattle_runtime_status", "Runtime status, 1 for current status.");
        type(metrics, "commonbattle_runtime_status", "gauge");
        for (RuntimeHealthStatus status : RuntimeHealthStatus.values()) {
            gauge(metrics, "commonbattle_runtime_status", "status", status.name(), status == snapshot.status() ? 1 : 0);
        }
    }

    private static void actorSystem(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_actor_submitted_tasks_total", snapshot.actorSystem().submittedTasks());
        gauge(metrics, "commonbattle_actor_completed_tasks_total", snapshot.actorSystem().completedTasks());
        gauge(metrics, "commonbattle_actor_failed_tasks_total", snapshot.actorSystem().failedTasks());
        gauge(metrics, "commonbattle_actor_rejected_tasks_total", snapshot.actorSystem().rejectedTasks());
        gauge(metrics, "commonbattle_actor_dropped_tasks_total", snapshot.actorSystem().droppedTasks());
        gauge(metrics, "commonbattle_actor_queued_tasks", snapshot.actorSystem().queuedTasks());
        gauge(metrics, "commonbattle_actor_running_mailboxes", snapshot.actorSystem().runningMailboxes());
        gauge(metrics, "commonbattle_actor_active_mailboxes", snapshot.actorSystem().activeMailboxes());
        gauge(metrics, "commonbattle_actor_largest_mailbox_queued_tasks", snapshot.actorSystem().largestMailboxQueuedTasks());
        gauge(metrics, "commonbattle_actor_peak_queued_tasks", snapshot.actorSystem().peakQueuedTasks());
        gauge(metrics, "commonbattle_actor_peak_running_mailboxes", snapshot.actorSystem().peakRunningMailboxes());
        labeledEnum(metrics, "commonbattle_actor_queued_tasks_by_category", "category",
                snapshot.actorSystem().queuedTasksByCategory(), ActorTaskCategory.values());
        labeledEnum(metrics, "commonbattle_actor_rejected_tasks_by_category_total", "category",
                snapshot.actorSystem().rejectedTasksByCategory(), ActorTaskCategory.values());
        labeledEnum(metrics, "commonbattle_actor_dropped_tasks_by_category_total", "category",
                snapshot.actorSystem().droppedTasksByCategory(), ActorTaskCategory.values());
    }

    private static void rpc(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_rpc_sent_requests_total", snapshot.rpc().sentRequests());
        gauge(metrics, "commonbattle_rpc_succeeded_requests_total", snapshot.rpc().succeededRequests());
        gauge(metrics, "commonbattle_rpc_failed_requests_total", snapshot.rpc().failedRequests());
        gauge(metrics, "commonbattle_rpc_timed_out_requests_total", snapshot.rpc().timedOutRequests());
        gauge(metrics, "commonbattle_rpc_rejected_requests_total", snapshot.rpc().rejectedRequests());
        gauge(metrics, "commonbattle_rpc_slow_requests_total", snapshot.rpc().slowRequests());
        gauge(metrics, "commonbattle_rpc_pending_requests", snapshot.rpc().pendingRequests());
        gauge(metrics, "commonbattle_rpc_idempotency_cache_size", snapshot.rpc().idempotencyCacheSize());
    }

    private static void rpcResilience(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_rpc_resilience_attempts_total", snapshot.rpcResilience().attempts());
        gauge(metrics, "commonbattle_rpc_resilience_retries_total", snapshot.rpcResilience().retries());
        gauge(metrics, "commonbattle_rpc_resilience_short_circuited_total", snapshot.rpcResilience().shortCircuited());
        gauge(metrics, "commonbattle_rpc_resilience_opened_circuits_total", snapshot.rpcResilience().openedCircuits());
        gauge(metrics, "commonbattle_rpc_resilience_rejected_after_close_total", snapshot.rpcResilience().rejectedAfterClose());
        gauge(metrics, "commonbattle_rpc_resilience_circuits", snapshot.rpcResilience().circuits());
        gauge(metrics, "commonbattle_rpc_resilience_open_circuits", snapshot.rpcResilience().openCircuits());
    }

    private static void actorRpc(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_actor_rpc_clients", snapshot.actorRpc().clientCount());
        gauge(metrics, "commonbattle_actor_rpc_calls_total", snapshot.actorRpc().calls());
        gauge(metrics, "commonbattle_actor_rpc_succeeded_responses_total", snapshot.actorRpc().succeededResponses());
        gauge(metrics, "commonbattle_actor_rpc_failed_responses_total", snapshot.actorRpc().failedResponses());
        gauge(metrics, "commonbattle_actor_rpc_callback_delivery_failures_total",
                snapshot.actorRpc().callbackDeliveryFailures());
        labeledEnum(metrics, "commonbattle_actor_rpc_failed_responses_by_status_total", "status",
                snapshot.actorRpc().failedResponsesByStatus(), AgentDeliveryStatus.values());
    }

    private static void commands(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        labeledEnum(metrics, "commonbattle_player_commands_total", "status", snapshot.commands().counts(),
                PlayerCommandStatus.values());
        gauge(metrics, "commonbattle_player_command_accepting_dispatchers", snapshot.commands().acceptingDispatchers());
        gauge(metrics, "commonbattle_player_command_draining_dispatchers", snapshot.commands().drainingDispatchers());
    }

    private static void agents(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        labeledEnum(metrics, "commonbattle_agents", "state", snapshot.agents().counts(), AgentLifecycleState.values());
        gauge(metrics, "commonbattle_agents_total", snapshot.agents().total());
    }

    private static void playerAgents(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_player_agent_managers", snapshot.playerAgents().managerCount());
        gauge(metrics, "commonbattle_player_agents_loaded", snapshot.playerAgents().loadedAgents());
        gauge(metrics, "commonbattle_player_autosave_schedulers", snapshot.playerAgents().autoSaveSchedulers());
        gauge(metrics, "commonbattle_player_autosave_runs_total", snapshot.playerAgents().autoSaveRuns());
        gauge(metrics, "commonbattle_player_autosave_submitted_total", snapshot.playerAgents().autoSaveSubmitted());
        gauge(metrics, "commonbattle_player_autosave_completed_total", snapshot.playerAgents().autoSaveCompleted());
        gauge(metrics, "commonbattle_player_autosave_failed_runs_total", snapshot.playerAgents().autoSaveFailedRuns());
        gauge(metrics, "commonbattle_player_autosave_failed_saves_total", snapshot.playerAgents().autoSaveFailedSaves());
        gauge(metrics, "commonbattle_player_agent_drain_services", snapshot.playerAgents().drainServices());
        gauge(metrics, "commonbattle_player_agent_draining_services", snapshot.playerAgents().drainingServices());
        gauge(metrics, "commonbattle_player_agent_drain_submitted_total", snapshot.playerAgents().drainSubmitted());
        gauge(metrics, "commonbattle_player_agent_drain_completed_total", snapshot.playerAgents().drainCompleted());
        gauge(metrics, "commonbattle_player_agent_drain_failed_saves_total", snapshot.playerAgents().drainFailedSaves());
        gauge(metrics, "commonbattle_player_agent_drain_pending", snapshot.playerAgents().drainPending());
    }

    private static void agentMigrations(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_agent_migration_initiated_total", snapshot.agentMigrations().initiated());
        gauge(metrics, "commonbattle_agent_migration_source_moved_total", snapshot.agentMigrations().sourceMoved());
        gauge(metrics, "commonbattle_agent_migration_source_move_failed_total",
                snapshot.agentMigrations().sourceMoveFailed());
        gauge(metrics, "commonbattle_agent_migration_target_accepted_total", snapshot.agentMigrations().targetAccepted());
        gauge(metrics, "commonbattle_agent_migration_target_rejected_total", snapshot.agentMigrations().targetRejected());
        gauge(metrics, "commonbattle_agent_migration_target_failed_total", snapshot.agentMigrations().targetFailed());
        gauge(metrics, "commonbattle_agent_migration_target_retries_total", snapshot.agentMigrations().targetRetries());
        gauge(metrics, "commonbattle_agent_migration_completion_rejected_total",
                snapshot.agentMigrations().completionRejected());
        gauge(metrics, "commonbattle_agent_migration_rollback_succeeded_total",
                snapshot.agentMigrations().rollbackSucceeded());
        gauge(metrics, "commonbattle_agent_migration_rollback_failed_total",
                snapshot.agentMigrations().rollbackFailed());
    }

    private static void agentMigrationExecutors(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_agent_migration_executor_submitted_total",
                snapshot.agentMigrationExecutors().submitted());
        gauge(metrics, "commonbattle_agent_migration_executor_running", snapshot.agentMigrationExecutors().running());
        gauge(metrics, "commonbattle_agent_migration_executor_completed_total",
                snapshot.agentMigrationExecutors().completed());
        gauge(metrics, "commonbattle_agent_migration_executor_failed_total", snapshot.agentMigrationExecutors().failed());
        gauge(metrics, "commonbattle_agent_migration_executor_rejected_total",
                snapshot.agentMigrationExecutors().rejected());
        gauge(metrics, "commonbattle_agent_migration_executor_queued_tasks",
                snapshot.agentMigrationExecutors().queuedTasks());
    }

    private static void agentMigrationRecoveries(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_agent_migration_recovery_scans_total",
                snapshot.agentMigrationRecoveries().scans());
        gauge(metrics, "commonbattle_agent_migration_recovery_tasks_total",
                snapshot.agentMigrationRecoveries().recoveredTasks());
        gauge(metrics, "commonbattle_agent_migration_recovery_target_accepted_total",
                snapshot.agentMigrationRecoveries().targetAccepted());
        gauge(metrics, "commonbattle_agent_migration_recovery_target_rejected_total",
                snapshot.agentMigrationRecoveries().targetRejected());
        gauge(metrics, "commonbattle_agent_migration_recovery_target_failed_total",
                snapshot.agentMigrationRecoveries().targetFailed());
        gauge(metrics, "commonbattle_agent_migration_recovery_target_retries_total",
                snapshot.agentMigrationRecoveries().targetRetries());
        gauge(metrics, "commonbattle_agent_migration_recovery_rollback_succeeded_total",
                snapshot.agentMigrationRecoveries().rollbackSucceeded());
        gauge(metrics, "commonbattle_agent_migration_recovery_rollback_failed_total",
                snapshot.agentMigrationRecoveries().rollbackFailed());
        gauge(metrics, "commonbattle_agent_migration_recovery_executor_rejected_total",
                snapshot.agentMigrationRecoveries().executorRejected());
    }

    private static void agentMigrationRecoverySchedulers(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_agent_migration_recovery_scheduler_runs_total",
                snapshot.agentMigrationRecoverySchedulers().runs());
        gauge(metrics, "commonbattle_agent_migration_recovery_scheduler_claimed_tasks_total",
                snapshot.agentMigrationRecoverySchedulers().claimedTasks());
        gauge(metrics, "commonbattle_agent_migration_recovery_scheduler_failed_runs_total",
                snapshot.agentMigrationRecoverySchedulers().failedRuns());
    }

    private static void agentMigrationTaskRetentions(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_agent_migration_task_retention_runs_total",
                snapshot.agentMigrationTaskRetentions().runs());
        gauge(metrics, "commonbattle_agent_migration_task_retention_purged_tasks_total",
                snapshot.agentMigrationTaskRetentions().purgedTasks());
        gauge(metrics, "commonbattle_agent_migration_task_retention_failed_runs_total",
                snapshot.agentMigrationTaskRetentions().failedRuns());
    }

    private static void agentMigrationTaskStores(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_agent_migration_task_stores", snapshot.agentMigrationTaskStores().stores());
        gauge(metrics, "commonbattle_agent_migration_task_store_tasks",
                snapshot.agentMigrationTaskStores().totalTasks());
        gauge(metrics, "commonbattle_agent_migration_task_store_prepared_tasks",
                snapshot.agentMigrationTaskStores().preparedTasks());
        gauge(metrics, "commonbattle_agent_migration_task_store_moved_tasks",
                snapshot.agentMigrationTaskStores().movedTasks());
        gauge(metrics, "commonbattle_agent_migration_task_store_terminal_tasks",
                snapshot.agentMigrationTaskStores().terminalTasks());
        gauge(metrics, "commonbattle_agent_migration_task_store_leased_pending_tasks",
                snapshot.agentMigrationTaskStores().leasedPendingTasks());
        gauge(metrics, "commonbattle_agent_migration_task_store_oldest_pending_age_millis",
                snapshot.agentMigrationTaskStores().oldestPendingAgeMillis());
    }

    private static void outbox(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_outbox_pending_events", snapshot.outbox().pendingEvents());
        gauge(metrics, "commonbattle_outbox_failed_attempts_total", snapshot.outbox().failedAttempts());
        gauge(metrics, "commonbattle_outbox_oldest_pending_age_millis", snapshot.outbox().oldestPendingAgeMillis());
    }

    private static void cluster(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        labeledEnum(metrics, "commonbattle_cluster_services", "kind", snapshot.cluster().counts(), ServiceKind.values());
        labeledEnum(metrics, "commonbattle_cluster_draining_services", "kind",
                snapshot.cluster().drainingCounts(), ServiceKind.values());
    }

    private static void registryLeases(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_registry_lease_renewers", snapshot.registryLeases().renewers());
        gauge(metrics, "commonbattle_registry_lease_successful_heartbeats_total", snapshot.registryLeases().successfulHeartbeats());
        gauge(metrics, "commonbattle_registry_lease_re_registrations_total", snapshot.registryLeases().reRegistrations());
        gauge(metrics, "commonbattle_registry_lease_failed_renewals_total", snapshot.registryLeases().failedRenewals());
        gauge(metrics, "commonbattle_registry_lease_reapers", snapshot.registryLeases().reapers());
        gauge(metrics, "commonbattle_registry_lease_expired_services_total", snapshot.registryLeases().expiredServices());
    }

    private static void serviceDescriptorPublishers(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_service_descriptor_publishers", snapshot.serviceDescriptorPublishers().publisherCount());
        gauge(metrics, "commonbattle_service_descriptor_draining_publishers", snapshot.serviceDescriptorPublishers().drainingPublishers());
        gauge(metrics, "commonbattle_service_descriptor_publish_attempts_total", snapshot.serviceDescriptorPublishers().attempts());
        gauge(metrics, "commonbattle_service_descriptor_publish_succeeded_total", snapshot.serviceDescriptorPublishers().succeeded());
        gauge(metrics, "commonbattle_service_descriptor_publish_failed_total", snapshot.serviceDescriptorPublishers().failed());
    }

    private static void networkTransports(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_network_transports", snapshot.networkTransports().transportCount());
        gauge(metrics, "commonbattle_network_active_connections", snapshot.networkTransports().activeConnections());
        gauge(metrics, "commonbattle_network_connection_attempts_total", snapshot.networkTransports().connectionAttempts());
        gauge(metrics, "commonbattle_network_connection_failures_total", snapshot.networkTransports().connectionFailures());
        gauge(metrics, "commonbattle_network_sent_envelopes_total", snapshot.networkTransports().sentEnvelopes());
        gauge(metrics, "commonbattle_network_failed_writes_total", snapshot.networkTransports().failedWrites());
        gauge(metrics, "commonbattle_network_received_envelopes_total", snapshot.networkTransports().receivedEnvelopes());
        gauge(metrics, "commonbattle_network_inbound_failures_total", snapshot.networkTransports().inboundFailures());
    }

    private static void configCaches(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_config_caches", snapshot.configCaches().cacheCount());
        gauge(metrics, "commonbattle_config_active_caches", snapshot.configCaches().activeCaches());
        gauge(metrics, "commonbattle_config_ready_caches", snapshot.configCaches().readyCaches());
        gauge(metrics, "commonbattle_config_stale_caches", snapshot.configCaches().staleCaches());
        gauge(metrics, "commonbattle_config_min_applied_event_revision", snapshot.configCaches().minAppliedEventRevision());
        gauge(metrics, "commonbattle_config_max_applied_event_revision", snapshot.configCaches().maxAppliedEventRevision());
    }

    private static void configRecoveries(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_config_recoveries", snapshot.configRecoveries().recoveryCount());
        gauge(metrics, "commonbattle_config_recovery_requested_total", snapshot.configRecoveries().requested());
        gauge(metrics, "commonbattle_config_recovery_skipped_in_flight_total", snapshot.configRecoveries().skippedWhileInFlight());
        gauge(metrics, "commonbattle_config_recovery_succeeded_total", snapshot.configRecoveries().succeeded());
        gauge(metrics, "commonbattle_config_recovery_failed_total", snapshot.configRecoveries().failed());
        gauge(metrics, "commonbattle_config_recovery_in_flight", snapshot.configRecoveries().inFlight());
    }

    private static void eventSubscriptions(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_event_subscription_managers", snapshot.eventSubscriptions().managerCount());
        gauge(metrics, "commonbattle_event_subscription_registered", snapshot.eventSubscriptions().registered());
        gauge(metrics, "commonbattle_event_subscription_active", snapshot.eventSubscriptions().active());
        gauge(metrics, "commonbattle_event_subscription_attempts_total", snapshot.eventSubscriptions().subscribeAttempts());
        gauge(metrics, "commonbattle_event_subscription_failures_total", snapshot.eventSubscriptions().subscribeFailures());
        gauge(metrics, "commonbattle_event_replay_attempts_total", snapshot.eventSubscriptions().replayAttempts());
        gauge(metrics, "commonbattle_event_replay_failures_total", snapshot.eventSubscriptions().replayFailures());
        gauge(metrics, "commonbattle_event_replay_delivered_total", snapshot.eventSubscriptions().replayDelivered());
        gauge(metrics, "commonbattle_event_replay_unavailable_owners_total", snapshot.eventSubscriptions().replayUnavailableOwners());
        gauge(metrics, "commonbattle_event_replay_repair_requests_total", snapshot.eventSubscriptions().replayRepairRequests());
        gauge(metrics, "commonbattle_event_replay_repair_owners_total", snapshot.eventSubscriptions().replayRepairOwnerCount());
        gauge(metrics, "commonbattle_event_replay_repair_failures_total", snapshot.eventSubscriptions().replayRepairFailures());
        gauge(metrics, "commonbattle_event_subscription_cursor_failures_total", snapshot.eventSubscriptions().cursorFailures());
    }

    private static void eventCenters(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_event_centers", snapshot.eventCenters().centerCount());
        gauge(metrics, "commonbattle_event_center_topics", snapshot.eventCenters().topicCount());
        gauge(metrics, "commonbattle_event_center_retained_events", snapshot.eventCenters().retainedEvents());
        gauge(metrics, "commonbattle_event_center_retained_owners", snapshot.eventCenters().retainedOwners());
        gauge(metrics, "commonbattle_event_center_subscribers", snapshot.eventCenters().subscribers());
        gauge(metrics, "commonbattle_event_center_published_events_total", snapshot.eventCenters().publishedEvents());
        gauge(metrics, "commonbattle_event_center_dropped_events_total", snapshot.eventCenters().droppedEvents());
        for (EventCenterTopicHealthStats topic : snapshot.eventCenters().topics().values()) {
            gauge(metrics, "commonbattle_event_center_topic_history_limit", "topic", topic.topic(), topic.historyLimit());
            gauge(metrics, "commonbattle_event_center_topic_retained_events", "topic", topic.topic(), topic.retainedEvents());
            gauge(metrics, "commonbattle_event_center_topic_retained_owners", "topic", topic.topic(), topic.retainedOwners());
            gauge(metrics, "commonbattle_event_center_topic_subscribers", "topic", topic.topic(), topic.subscribers());
            gauge(metrics, "commonbattle_event_center_topic_published_events_total", "topic", topic.topic(), topic.publishedEvents());
            gauge(metrics, "commonbattle_event_center_topic_dropped_events_total", "topic", topic.topic(), topic.droppedEvents());
            gauge(metrics, "commonbattle_event_center_topic_min_retained_revision", "topic", topic.topic(), topic.minRetainedRevision());
            gauge(metrics, "commonbattle_event_center_topic_max_retained_revision", "topic", topic.topic(), topic.maxRetainedRevision());
        }
    }

    private static void profileInterests(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_profile_interest_subscriptions", snapshot.profileInterests().subscriptionCount());
        gauge(metrics, "commonbattle_profile_interest_watched_owners", snapshot.profileInterests().watchedOwners());
        gauge(metrics, "commonbattle_profile_interest_watch_references", snapshot.profileInterests().watchReferences());
        gauge(metrics, "commonbattle_profile_interest_watch_requests_total", snapshot.profileInterests().watchRequests());
        gauge(metrics, "commonbattle_profile_interest_unwatch_requests_total", snapshot.profileInterests().unwatchRequests());
        gauge(metrics, "commonbattle_profile_interest_replay_attempts_total", snapshot.profileInterests().replayAttempts());
        gauge(metrics, "commonbattle_profile_interest_replay_failures_total", snapshot.profileInterests().replayFailures());
        gauge(metrics, "commonbattle_profile_interest_repair_requests_total", snapshot.profileInterests().repairRequests());
        gauge(metrics, "commonbattle_profile_interest_repair_failures_total", snapshot.profileInterests().repairFailures());
    }

    private static void profileRuntimes(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_profile_runtimes", snapshot.profileRuntimes().runtimeCount());
        gauge(metrics, "commonbattle_profile_runtime_read_requests_total", snapshot.profileRuntimes().readRequests());
        gauge(metrics, "commonbattle_profile_runtime_local_hits_total", snapshot.profileRuntimes().localHits());
        gauge(metrics, "commonbattle_profile_runtime_local_stale_total", snapshot.profileRuntimes().localStale());
        gauge(metrics, "commonbattle_profile_runtime_local_misses_total", snapshot.profileRuntimes().localMisses());
        gauge(metrics, "commonbattle_profile_runtime_refreshes_total", snapshot.profileRuntimes().refreshes());
        gauge(metrics, "commonbattle_profile_runtime_remote_misses_total", snapshot.profileRuntimes().remoteMisses());
        gauge(metrics, "commonbattle_profile_runtime_local_fallbacks_total", snapshot.profileRuntimes().localFallbacks());
    }

    private static void sceneRuntimes(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_scene_runtimes", snapshot.sceneRuntimes().runtimeCount());
        gauge(metrics, "commonbattle_scene_active_scenes", snapshot.sceneRuntimes().activeScenes());
        gauge(metrics, "commonbattle_scene_active_players", snapshot.sceneRuntimes().activePlayers());
        gauge(metrics, "commonbattle_scene_shards", snapshot.sceneRuntimes().shardCount());
        gauge(metrics, "commonbattle_scene_max_shard_players", snapshot.sceneRuntimes().maxShardPlayers());
    }

    private static void shopRuntimes(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_shop_runtimes", snapshot.shopRuntimes().runtimeCount());
        gauge(metrics, "commonbattle_shop_purchase_requests_total", snapshot.shopRuntimes().purchaseRequests());
        gauge(metrics, "commonbattle_shop_successful_purchases_total", snapshot.shopRuntimes().successfulPurchases());
        gauge(metrics, "commonbattle_shop_idempotent_replays_total", snapshot.shopRuntimes().idempotentReplays());
        gauge(metrics, "commonbattle_shop_unknown_items_total", snapshot.shopRuntimes().unknownItems());
        gauge(metrics, "commonbattle_shop_lifetime_limit_rejected_total", snapshot.shopRuntimes().lifetimeLimitRejected());
        gauge(metrics, "commonbattle_shop_daily_limit_rejected_total", snapshot.shopRuntimes().dailyLimitRejected());
        gauge(metrics, "commonbattle_shop_not_enough_currency_total", snapshot.shopRuntimes().notEnoughCurrency());
        gauge(metrics, "commonbattle_shop_out_of_stock_total", snapshot.shopRuntimes().outOfStock());
        gauge(metrics, "commonbattle_shop_order_conflicts_total", snapshot.shopRuntimes().orderConflicts());
        gauge(metrics, "commonbattle_shop_recorded_orders_total", snapshot.shopRuntimes().recordedOrders());
        gauge(metrics, "commonbattle_shop_active_reservations", snapshot.shopRuntimes().activeReservations());
        gauge(metrics, "commonbattle_shop_reservation_reap_runs_total", snapshot.shopRuntimes().reservationReapRuns());
        gauge(metrics, "commonbattle_shop_reaped_reservations_total", snapshot.shopRuntimes().reapedReservations());
        gauge(metrics, "commonbattle_shop_reservation_reap_failures_total", snapshot.shopRuntimes().reservationReapFailures());
    }

    private static void commandAudits(StringBuilder metrics, RuntimeHealthSnapshot snapshot) {
        gauge(metrics, "commonbattle_command_audit_total", snapshot.commandAudits().total());
        gauge(metrics, "commonbattle_command_audit_retained", snapshot.commandAudits().retained());
        gauge(metrics, "commonbattle_command_audit_dropped_total", snapshot.commandAudits().dropped());
        gauge(metrics, "commonbattle_command_audit_executed_total", snapshot.commandAudits().executed());
        gauge(metrics, "commonbattle_command_audit_failed_total", snapshot.commandAudits().failed());
        gauge(metrics, "commonbattle_command_audit_rejected_total", snapshot.commandAudits().rejected());
        gauge(metrics, "commonbattle_command_audit_routed_remote_total", snapshot.commandAudits().routedRemote());
        gauge(metrics, "commonbattle_command_audit_max_elapsed_millis", snapshot.commandAudits().maxElapsedMillis());
        for (Map.Entry<Long, Long> entry : snapshot.commandAudits().configVersionCounts().entrySet()) {
            gauge(metrics, "commonbattle_command_audit_config_version_total", "version",
                    String.valueOf(entry.getKey()), entry.getValue());
        }
    }

    private static <E extends Enum<E>> void labeledEnum(
            StringBuilder metrics,
            String name,
            String label,
            Map<E, ? extends Number> values,
            E[] keys
    ) {
        for (E key : keys) {
            Number value = values.get(key);
            gauge(metrics, name, label, key.name(), value == null ? 0 : value.longValue());
        }
    }

    private static void help(StringBuilder metrics, String name, String text) {
        metrics.append("# HELP ").append(name).append(' ').append(text).append('\n');
    }

    private static void type(StringBuilder metrics, String name, String type) {
        metrics.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void gauge(StringBuilder metrics, String name, long value) {
        metrics.append(name).append(' ').append(value).append('\n');
    }

    private static void gauge(StringBuilder metrics, String name, String label, String labelValue, long value) {
        metrics.append(name)
                .append('{')
                .append(label)
                .append("=\"")
                .append(escapeLabel(labelValue))
                .append("\"} ")
                .append(value)
                .append('\n');
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
