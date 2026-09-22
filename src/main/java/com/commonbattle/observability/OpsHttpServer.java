package com.commonbattle.observability;

import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.ActorMailboxStats;
import com.commonbattle.actor.agent.migration.ActorHotspotMigrationAdmin;
import com.commonbattle.actor.agent.migration.ActorHotspotMigrationResult;
import com.commonbattle.actor.agent.migration.ActorHotspotMigrationStatus;
import com.commonbattle.actor.backpressure.ActorHotspotOverride;
import com.commonbattle.actor.backpressure.ActorHotspotOverrideAdmin;
import com.commonbattle.actor.backpressure.ActorHotspotOverrideMode;
import com.commonbattle.game.event.OwnerEventRepairIsolatedOwner;
import com.commonbattle.game.event.OwnerEventRepairIsolationAdmin;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 轻量运维 HTTP 端点。
 * 提供 /live、/ready 和 /health，供容器探活、发布系统和人工排障读取运行时健康状态。
 */
public final class OpsHttpServer implements AutoCloseable {
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String METRICS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final int MAX_OWNER_REPAIR_AUDIT_ENTRIES = 128;
    private static final int DEFAULT_OWNER_REPAIR_AUDIT_LIMIT = 128;
    private static final int MAX_ACTOR_INCIDENT_ENTRIES = 128;
    private static final int DEFAULT_ACTOR_INCIDENT_LIMIT = 128;
    private static final int MAX_ACTOR_MAILBOX_ENTRIES = 512;
    private static final int DEFAULT_ACTOR_MAILBOX_LIMIT = 128;
    private static final int MAX_ACTOR_SLOW_TASK_ENTRIES = 128;
    private static final int DEFAULT_ACTOR_SLOW_TASK_LIMIT = 128;
    private static final int MAX_ACTOR_HOTSPOT_ENTRIES = 256;
    private static final int DEFAULT_ACTOR_HOTSPOT_LIMIT = 128;
    private static final int MAX_ACTOR_HOTSPOT_OVERRIDE_ENTRIES = 256;
    private static final int DEFAULT_ACTOR_HOTSPOT_OVERRIDE_LIMIT = 128;
    private static final long DEFAULT_ACTOR_HOTSPOT_OVERRIDE_TTL_MILLIS = 300_000;
    private static final int DEFAULT_ACTOR_HOTSPOT_MIGRATION_SUBMISSIONS = 16;
    private static final int MAX_ACTOR_HOTSPOT_MIGRATION_SUBMISSIONS = 128;

    private final RuntimeHealthProbe probe;
    private final ServerDrainController drainController;
    private final DrainConfig drainConfig;
    private final Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins;
    private final OwnerRepairOpsAuditSink ownerRepairOpsAuditSink;
    private final OwnerRepairOpsAuditView ownerRepairOpsAuditView;
    private final Collection<ActorIncidentView> actorIncidentViews;
    private final Collection<ActorSlowTaskView> actorSlowTaskViews;
    private final Collection<ActorHotspotOverrideAdmin> actorHotspotOverrideAdmins;
    private final Collection<ActorHotspotMigrationAdmin> actorHotspotMigrationAdmins;
    private final ActorHotspotPolicy actorHotspotPolicy;
    private final OpsHttpSecurityConfig securityConfig;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final HttpServer server;

    public OpsHttpServer(InetSocketAddress address, RuntimeHealthProbe probe) {
        this(address, probe, null, DrainConfig.defaults());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig
    ) {
        this(address, probe, drainController, drainConfig, List.of());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins,
                new InMemoryOwnerRepairOpsAuditLog(MAX_OWNER_REPAIR_AUDIT_ENTRIES),
                OpsHttpSecurityConfig.disabled());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            InMemoryOwnerRepairOpsAuditLog ownerRepairOpsAudit
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAudit,
                OpsHttpSecurityConfig.disabled());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            InMemoryOwnerRepairOpsAuditLog ownerRepairOpsAudit,
            OpsHttpSecurityConfig securityConfig
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAudit,
                ownerRepairOpsAudit, securityConfig);
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            InMemoryOwnerRepairOpsAuditLog ownerRepairOpsAudit,
            OpsHttpSecurityConfig securityConfig,
            Collection<ActorIncidentView> actorIncidentViews
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAudit,
                ownerRepairOpsAudit, securityConfig, actorIncidentViews, List.of());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            InMemoryOwnerRepairOpsAuditLog ownerRepairOpsAudit,
            OpsHttpSecurityConfig securityConfig,
            Collection<ActorIncidentView> actorIncidentViews,
            Collection<ActorSlowTaskView> actorSlowTaskViews
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAudit,
                ownerRepairOpsAudit, securityConfig, actorIncidentViews, actorSlowTaskViews,
                ActorHotspotPolicy.defaults());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            InMemoryOwnerRepairOpsAuditLog ownerRepairOpsAudit,
            OpsHttpSecurityConfig securityConfig,
            Collection<ActorIncidentView> actorIncidentViews,
            Collection<ActorSlowTaskView> actorSlowTaskViews,
            ActorHotspotPolicy actorHotspotPolicy
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAudit,
                ownerRepairOpsAudit, securityConfig, actorIncidentViews, actorSlowTaskViews, actorHotspotPolicy,
                List.of());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            InMemoryOwnerRepairOpsAuditLog ownerRepairOpsAudit,
            OpsHttpSecurityConfig securityConfig,
            Collection<ActorIncidentView> actorIncidentViews,
            Collection<ActorSlowTaskView> actorSlowTaskViews,
            ActorHotspotPolicy actorHotspotPolicy,
            Collection<ActorHotspotOverrideAdmin> actorHotspotOverrideAdmins
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAudit,
                ownerRepairOpsAudit, securityConfig, actorIncidentViews, actorSlowTaskViews, actorHotspotPolicy,
                actorHotspotOverrideAdmins, List.of());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            InMemoryOwnerRepairOpsAuditLog ownerRepairOpsAudit,
            OpsHttpSecurityConfig securityConfig,
            Collection<ActorIncidentView> actorIncidentViews,
            Collection<ActorSlowTaskView> actorSlowTaskViews,
            ActorHotspotPolicy actorHotspotPolicy,
            Collection<ActorHotspotOverrideAdmin> actorHotspotOverrideAdmins,
            Collection<ActorHotspotMigrationAdmin> actorHotspotMigrationAdmins
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAudit,
                ownerRepairOpsAudit, securityConfig, actorIncidentViews, actorSlowTaskViews, actorHotspotPolicy,
                actorHotspotOverrideAdmins, actorHotspotMigrationAdmins);
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            OwnerRepairOpsAuditSink ownerRepairOpsAuditSink,
            OwnerRepairOpsAuditView ownerRepairOpsAuditView
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAuditSink,
                ownerRepairOpsAuditView, OpsHttpSecurityConfig.disabled());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            OwnerRepairOpsAuditSink ownerRepairOpsAuditSink,
            OwnerRepairOpsAuditView ownerRepairOpsAuditView,
            OpsHttpSecurityConfig securityConfig
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAuditSink,
                ownerRepairOpsAuditView, securityConfig, List.of(), List.of());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            OwnerRepairOpsAuditSink ownerRepairOpsAuditSink,
            OwnerRepairOpsAuditView ownerRepairOpsAuditView,
            OpsHttpSecurityConfig securityConfig,
            Collection<ActorIncidentView> actorIncidentViews
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAuditSink,
                ownerRepairOpsAuditView, securityConfig, actorIncidentViews, List.of());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            OwnerRepairOpsAuditSink ownerRepairOpsAuditSink,
            OwnerRepairOpsAuditView ownerRepairOpsAuditView,
            OpsHttpSecurityConfig securityConfig,
            Collection<ActorIncidentView> actorIncidentViews,
            Collection<ActorSlowTaskView> actorSlowTaskViews
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAuditSink,
                ownerRepairOpsAuditView, securityConfig, actorIncidentViews, actorSlowTaskViews,
                ActorHotspotPolicy.defaults());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            OwnerRepairOpsAuditSink ownerRepairOpsAuditSink,
            OwnerRepairOpsAuditView ownerRepairOpsAuditView,
            OpsHttpSecurityConfig securityConfig,
            Collection<ActorIncidentView> actorIncidentViews,
            Collection<ActorSlowTaskView> actorSlowTaskViews,
            ActorHotspotPolicy actorHotspotPolicy
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAuditSink,
                ownerRepairOpsAuditView, securityConfig, actorIncidentViews, actorSlowTaskViews,
                actorHotspotPolicy, List.of());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            OwnerRepairOpsAuditSink ownerRepairOpsAuditSink,
            OwnerRepairOpsAuditView ownerRepairOpsAuditView,
            OpsHttpSecurityConfig securityConfig,
            Collection<ActorIncidentView> actorIncidentViews,
            Collection<ActorSlowTaskView> actorSlowTaskViews,
            ActorHotspotPolicy actorHotspotPolicy,
            Collection<ActorHotspotOverrideAdmin> actorHotspotOverrideAdmins
    ) {
        this(address, probe, drainController, drainConfig, repairIsolationAdmins, ownerRepairOpsAuditSink,
                ownerRepairOpsAuditView, securityConfig, actorIncidentViews, actorSlowTaskViews,
                actorHotspotPolicy, actorHotspotOverrideAdmins, List.of());
    }

    public OpsHttpServer(
            InetSocketAddress address,
            RuntimeHealthProbe probe,
            ServerDrainController drainController,
            DrainConfig drainConfig,
            Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins,
            OwnerRepairOpsAuditSink ownerRepairOpsAuditSink,
            OwnerRepairOpsAuditView ownerRepairOpsAuditView,
            OpsHttpSecurityConfig securityConfig,
            Collection<ActorIncidentView> actorIncidentViews,
            Collection<ActorSlowTaskView> actorSlowTaskViews,
            ActorHotspotPolicy actorHotspotPolicy,
            Collection<ActorHotspotOverrideAdmin> actorHotspotOverrideAdmins,
            Collection<ActorHotspotMigrationAdmin> actorHotspotMigrationAdmins
    ) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.drainController = drainController;
        this.drainConfig = Objects.requireNonNull(drainConfig, "drainConfig");
        this.repairIsolationAdmins = List.copyOf(Objects.requireNonNull(repairIsolationAdmins,
                "repairIsolationAdmins"));
        this.ownerRepairOpsAuditSink = Objects.requireNonNull(ownerRepairOpsAuditSink, "ownerRepairOpsAuditSink");
        this.ownerRepairOpsAuditView = Objects.requireNonNull(ownerRepairOpsAuditView, "ownerRepairOpsAuditView");
        this.actorIncidentViews = List.copyOf(Objects.requireNonNull(actorIncidentViews, "actorIncidentViews"));
        this.actorSlowTaskViews = List.copyOf(Objects.requireNonNull(actorSlowTaskViews, "actorSlowTaskViews"));
        this.actorHotspotOverrideAdmins = List.copyOf(Objects.requireNonNull(actorHotspotOverrideAdmins,
                "actorHotspotOverrideAdmins"));
        this.actorHotspotMigrationAdmins = List.copyOf(Objects.requireNonNull(actorHotspotMigrationAdmins,
                "actorHotspotMigrationAdmins"));
        this.actorHotspotPolicy = Objects.requireNonNull(actorHotspotPolicy, "actorHotspotPolicy");
        this.securityConfig = Objects.requireNonNull(securityConfig, "securityConfig");
        try {
            this.server = HttpServer.create(Objects.requireNonNull(address, "address"), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to bind ops http server", e);
        }
        server.createContext("/live", this::live);
        server.createContext("/ready", this::ready);
        server.createContext("/health", this::health);
        server.createContext("/metrics", this::metrics);
        server.createContext("/drain", this::drain);
        server.createContext("/owner-repair/isolated", this::repairIsolatedOwners);
        server.createContext("/owner-repair/release", this::releaseRepairOwner);
        server.createContext("/owner-repair/release-all", this::releaseAllRepairOwners);
        server.createContext("/owner-repair/audit", this::ownerRepairAudit);
        server.createContext("/actor-incidents", this::actorIncidents);
        server.createContext("/actor-mailboxes", this::actorMailboxes);
        server.createContext("/actor-slow-tasks", this::actorSlowTasks);
        server.createContext("/actor-hotspots", this::actorHotspots);
        server.createContext("/actor-hotspot-overrides", this::actorHotspotOverrides);
        server.createContext("/actor-hotspot-overrides/set", this::setActorHotspotOverride);
        server.createContext("/actor-hotspot-overrides/clear", this::clearActorHotspotOverride);
        server.createContext("/actor-hotspot-migrations/submit", this::submitActorHotspotMigrations);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void live(HttpExchange exchange) throws IOException {
        RuntimeHealthSnapshot snapshot = probe.snapshot();
        int code = snapshot.status() == RuntimeHealthStatus.DOWN ? 503 : 200;
        respondJson(exchange, code, "{\"status\":\"" + snapshot.status().name() + "\"}");
    }

    private void ready(HttpExchange exchange) throws IOException {
        RuntimeHealthSnapshot snapshot = probe.snapshot();
        int code = !draining.get() && snapshot.status() == RuntimeHealthStatus.UP ? 200 : 503;
        respondJson(exchange, code, "{\"status\":\"" + snapshot.status().name()
                + "\",\"draining\":" + draining.get() + "}");
    }

    private void health(HttpExchange exchange) throws IOException {
        RuntimeHealthSnapshot snapshot = probe.snapshot();
        int code = snapshot.status() == RuntimeHealthStatus.DOWN ? 503 : 200;
        respondJson(exchange, code, RuntimeHealthJsonFormatter.format(snapshot));
    }

    private void metrics(HttpExchange exchange) throws IOException {
        RuntimeHealthSnapshot snapshot = probe.snapshot();
        int code = snapshot.status() == RuntimeHealthStatus.DOWN ? 503 : 200;
        respond(exchange, code, METRICS_CONTENT_TYPE, RuntimeMetricsFormatter.format(snapshot));
    }

    private void drain(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (drainController == null) {
            respondJson(exchange, 503, "{\"drained\":false,\"reason\":\"drain_not_configured\"}");
            return;
        }
        draining.set(true);
        try {
            DrainResult result = drainController.awaitDrained(drainConfig);
            int code = result.drained() ? 200 : 503;
            respondJson(exchange, code, "{\"drained\":" + result.drained()
                    + ",\"elapsedMillis\":" + result.elapsed().toMillis()
                    + ",\"status\":\"" + result.lastSnapshot().status().name() + "\""
                    + ",\"reason\":\"" + escape(result.reason()) + "\"}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respondJson(exchange, 503, "{\"drained\":false,\"reason\":\"interrupted\"}");
        }
    }

    private void repairIsolatedOwners(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        StringBuilder json = new StringBuilder();
        json.append("{\"owners\":[");
        boolean first = true;
        for (OwnerEventRepairIsolationAdmin admin : repairIsolationAdmins) {
            for (OwnerEventRepairIsolatedOwner owner : admin.isolatedOwners()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"ownerKey\":\"").append(escape(owner.ownerKey())).append("\",")
                        .append("\"priority\":").append(owner.priority()).append(',')
                        .append("\"remainingMillis\":").append(owner.remainingMillis()).append('}');
            }
        }
        json.append("]}");
        respondJson(exchange, 200, json.toString());
    }

    private void releaseRepairOwner(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String ownerKey = queryParam(exchange.getRequestURI().getRawQuery(), "ownerKey");
        if (ownerKey == null || ownerKey.isBlank()) {
            respondJson(exchange, 400, "{\"released\":0,\"error\":\"missing_owner_key\"}");
            return;
        }
        int released = 0;
        for (OwnerEventRepairIsolationAdmin admin : repairIsolationAdmins) {
            released += admin.releaseIsolatedOwner(ownerKey);
        }
        int code = released > 0 ? 200 : 404;
        recordOwnerRepairAudit("release", ownerKey, released, exchange);
        respondJson(exchange, code, "{\"released\":" + released
                + ",\"ownerKey\":\"" + escape(ownerKey) + "\"}");
    }

    private void releaseAllRepairOwners(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        int released = 0;
        for (OwnerEventRepairIsolationAdmin admin : repairIsolationAdmins) {
            released += admin.releaseAllIsolatedOwners();
        }
        recordOwnerRepairAudit("release-all", "*", released, exchange);
        respondJson(exchange, 200, "{\"released\":" + released + "}");
    }

    private void ownerRepairAudit(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        OwnerRepairOpsAuditQuery query;
        try {
            query = ownerRepairAuditQuery(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }
        OwnerRepairOpsAuditPage page = ownerRepairAuditPage(query);
        StringBuilder json = new StringBuilder();
        json.append("{\"entries\":[");
        boolean first = true;
        for (OwnerRepairOpsAuditRecord entry : page.entries()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendOwnerRepairAuditEntry(json, entry);
        }
        json.append("],")
                .append("\"matched\":").append(page.matched()).append(',')
                .append("\"offset\":").append(query.offset()).append(',')
                .append("\"limit\":").append(query.limit())
                .append('}');
        respondJson(exchange, 200, json.toString());
    }

    private OwnerRepairOpsAuditPage ownerRepairAuditPage(OwnerRepairOpsAuditQuery query) {
        return ownerRepairOpsAuditView.query(query);
    }

    private void actorIncidents(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        ActorIncidentQuery query;
        try {
            query = actorIncidentQuery(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }
        ActorIncidentPage page = actorIncidentPage(query);
        StringBuilder json = new StringBuilder();
        json.append("{\"entries\":[");
        boolean first = true;
        for (ActorIncidentRecord entry : page.entries()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendActorIncident(json, entry);
        }
        json.append("],")
                .append("\"matched\":").append(page.matched()).append(',')
                .append("\"offset\":").append(query.offset()).append(',')
                .append("\"limit\":").append(query.limit())
                .append('}');
        respondJson(exchange, 200, json.toString());
    }

    private void actorMailboxes(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        ActorMailboxQuery query;
        try {
            query = actorMailboxQuery(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }
        ActorMailboxPage page = actorMailboxPage(query);
        StringBuilder json = new StringBuilder();
        json.append("{\"entries\":[");
        boolean first = true;
        for (ActorMailboxStats entry : page.entries()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendActorMailbox(json, entry);
        }
        json.append("],")
                .append("\"matched\":").append(page.matched()).append(',')
                .append("\"offset\":").append(query.offset()).append(',')
                .append("\"limit\":").append(query.limit())
                .append('}');
        respondJson(exchange, 200, json.toString());
    }

    private void actorSlowTasks(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        ActorSlowTaskQuery query;
        try {
            query = actorSlowTaskQuery(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }
        ActorSlowTaskPage page = actorSlowTaskPage(query);
        StringBuilder json = new StringBuilder();
        json.append("{\"entries\":[");
        boolean first = true;
        for (ActorSlowTaskRecord entry : page.entries()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendActorSlowTask(json, entry);
        }
        json.append("],")
                .append("\"matched\":").append(page.matched()).append(',')
                .append("\"offset\":").append(query.offset()).append(',')
                .append("\"limit\":").append(query.limit())
                .append('}');
        respondJson(exchange, 200, json.toString());
    }

    private void actorHotspots(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        ActorHotspotQuery query;
        try {
            query = actorHotspotQuery(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }
        ActorHotspotPage page = actorHotspotPage(query);
        StringBuilder json = new StringBuilder();
        json.append("{\"entries\":[");
        boolean first = true;
        for (ActorHotspotCandidate entry : page.entries()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendActorHotspot(json, entry);
        }
        json.append("],")
                .append("\"matched\":").append(page.matched()).append(',')
                .append("\"offset\":").append(query.offset()).append(',')
                .append("\"limit\":").append(query.limit())
                .append('}');
        respondJson(exchange, 200, json.toString());
    }

    private void actorHotspotOverrides(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        ActorHotspotOverrideQuery query;
        try {
            query = actorHotspotOverrideQuery(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }
        List<ActorHotspotOverride> matched = actorHotspotOverrides(query);
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        StringBuilder json = new StringBuilder();
        json.append("{\"entries\":[");
        boolean first = true;
        for (ActorHotspotOverride override : matched.subList(from, to)) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendActorHotspotOverride(json, override);
        }
        json.append("],")
                .append("\"matched\":").append(matched.size()).append(',')
                .append("\"offset\":").append(query.offset()).append(',')
                .append("\"limit\":").append(query.limit())
                .append('}');
        respondJson(exchange, 200, json.toString());
    }

    private void setActorHotspotOverride(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (actorHotspotOverrideAdmins.isEmpty()) {
            respondJson(exchange, 503, "{\"updated\":0,\"error\":\"hotspot_override_not_configured\"}");
            return;
        }
        String rawQuery = exchange.getRequestURI().getRawQuery();
        String actorId = blankToNull(queryParam(rawQuery, "actorId"));
        if (actorId == null) {
            respondJson(exchange, 400, "{\"updated\":0,\"error\":\"missing_actor_id\"}");
            return;
        }
        ActorHotspotOverrideMode mode;
        try {
            mode = actorHotspotOverrideMode(rawQuery);
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, "{\"updated\":0,\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }
        long ttlMillis;
        try {
            ttlMillis = longQueryParam(rawQuery, "ttlMillis", DEFAULT_ACTOR_HOTSPOT_OVERRIDE_TTL_MILLIS);
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, "{\"updated\":0,\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }
        if (ttlMillis <= 0) {
            respondJson(exchange, 400, "{\"updated\":0,\"error\":\"invalid_ttlMillis\"}");
            return;
        }
        String reason = blankToNull(queryParam(rawQuery, "reason"));
        ActorHotspotOverride last = null;
        int updated = 0;
        for (ActorHotspotOverrideAdmin admin : actorHotspotOverrideAdmins) {
            last = admin.setHotspotOverride(actorId, mode, Duration.ofMillis(ttlMillis), reason);
            updated++;
        }
        StringBuilder json = new StringBuilder();
        json.append("{\"updated\":").append(updated).append(',');
        if (last != null) {
            json.append("\"override\":");
            appendActorHotspotOverride(json, last);
        } else {
            json.append("\"override\":null");
        }
        json.append('}');
        respondJson(exchange, 200, json.toString());
    }

    private void clearActorHotspotOverride(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String actorId = blankToNull(queryParam(exchange.getRequestURI().getRawQuery(), "actorId"));
        if (actorId == null) {
            respondJson(exchange, 400, "{\"cleared\":0,\"error\":\"missing_actor_id\"}");
            return;
        }
        int cleared = 0;
        for (ActorHotspotOverrideAdmin admin : actorHotspotOverrideAdmins) {
            cleared += admin.clearHotspotOverride(actorId);
        }
        int code = cleared > 0 ? 200 : 404;
        respondJson(exchange, code, "{\"cleared\":" + cleared
                + ",\"actorId\":\"" + escape(actorId) + "\"}");
    }

    private void submitActorHotspotMigrations(HttpExchange exchange) throws IOException {
        if (!authorizeAdmin(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (actorHotspotMigrationAdmins.isEmpty()) {
            respondJson(exchange, 503, "{\"submitted\":0,\"error\":\"hotspot_migration_not_configured\"}");
            return;
        }
        String rawQuery = exchange.getRequestURI().getRawQuery();
        ActorHotspotQuery query;
        int maxSubmissions;
        try {
            query = actorHotspotQuery(rawQuery);
            maxSubmissions = intQueryParam(rawQuery, "maxSubmissions", DEFAULT_ACTOR_HOTSPOT_MIGRATION_SUBMISSIONS);
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, "{\"submitted\":0,\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }
        if (maxSubmissions < 1 || maxSubmissions > MAX_ACTOR_HOTSPOT_MIGRATION_SUBMISSIONS) {
            respondJson(exchange, 400, "{\"submitted\":0,\"error\":\"invalid_maxSubmissions\"}");
            return;
        }
        List<ActorHotspotCandidate> candidates = actorHotspotPage(query).entries().stream()
                .filter(candidate -> candidate.action() == ActorHotspotAction.MIGRATION_CANDIDATE)
                .toList();
        List<ActorHotspotMigrationResult> results = actorHotspotMigrationAdmins.stream()
                .flatMap(admin -> admin.submitHotspotMigrations(candidates, maxSubmissions).stream())
                .toList();
        long submitted = results.stream()
                .filter(result -> result.status() == ActorHotspotMigrationStatus.SUBMITTED)
                .count();
        StringBuilder json = new StringBuilder();
        json.append("{\"submitted\":").append(submitted)
                .append(",\"candidates\":").append(candidates.size())
                .append(",\"results\":[");
        boolean first = true;
        for (ActorHotspotMigrationResult result : results) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendActorHotspotMigrationResult(json, result);
        }
        json.append("]}");
        respondJson(exchange, 200, json.toString());
    }

    private ActorIncidentPage actorIncidentPage(ActorIncidentQuery query) {
        List<ActorIncidentRecord> matched = actorIncidentViews.stream()
                .flatMap(view -> view.recentActorIncidents().stream())
                .filter(entry -> query.kind() == null || query.kind() == entry.kind())
                .filter(entry -> query.actorId() == null || query.actorId().equals(entry.actorId()))
                .filter(entry -> query.category() == null || query.category() == entry.category())
                .filter(entry -> query.reason() == null || query.reason().equals(entry.reason()))
                .toList();
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return new ActorIncidentPage(matched.subList(from, to), matched.size(), query.offset(), query.limit());
    }

    private ActorMailboxPage actorMailboxPage(ActorMailboxQuery query) {
        List<ActorMailboxStats> matched = probe.queuedMailboxStats().stream()
                .filter(entry -> query.group() == null
                        || query.group().equals(ActorMailboxDiagnostics.groupOfActorId(entry.actor().id())))
                .filter(entry -> query.actorIdPrefix() == null || entry.actor().id().startsWith(query.actorIdPrefix()))
                .filter(entry -> query.category() == null
                        || entry.queuedTasksByCategory().getOrDefault(query.category(), 0) > 0)
                .filter(entry -> entry.queuedTasks() >= query.minQueuedTasks())
                .toList();
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return new ActorMailboxPage(matched.subList(from, to), matched.size(), query.offset(), query.limit());
    }

    private ActorSlowTaskPage actorSlowTaskPage(ActorSlowTaskQuery query) {
        List<ActorSlowTaskRecord> matched = actorSlowTaskViews.stream()
                .flatMap(view -> view.recentActorSlowTasks().stream())
                .filter(entry -> query.actorId() == null || query.actorId().equals(entry.actorId()))
                .filter(entry -> query.category() == null || query.category() == entry.category())
                .filter(entry -> entry.elapsedMillis() >= query.minElapsedMillis())
                .toList();
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return new ActorSlowTaskPage(matched.subList(from, to), matched.size(), query.offset(), query.limit());
    }

    private ActorHotspotPage actorHotspotPage(ActorHotspotQuery query) {
        List<ActorSlowTaskRecord> slowTasks = actorSlowTaskViews.stream()
                .flatMap(view -> view.recentActorSlowTasks().stream())
                .toList();
        List<ActorHotspotCandidate> matched = ActorHotspotAnalyzer.analyze(
                        probe.queuedMailboxStats(),
                        slowTasks,
                        actorHotspotPolicy
                ).stream()
                .filter(entry -> query.action() == null || query.action() == entry.action())
                .filter(entry -> query.group() == null || query.group().equals(entry.group()))
                .filter(entry -> query.actorIdPrefix() == null || entry.actorId().startsWith(query.actorIdPrefix()))
                .toList();
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return new ActorHotspotPage(matched.subList(from, to), matched.size(), query.offset(), query.limit());
    }

    private List<ActorHotspotOverride> actorHotspotOverrides(ActorHotspotOverrideQuery query) {
        return actorHotspotOverrideAdmins.stream()
                .flatMap(admin -> admin.hotspotOverrides().stream())
                .filter(entry -> query.mode() == null || query.mode() == entry.mode())
                .filter(entry -> query.group() == null
                        || query.group().equals(ActorMailboxDiagnostics.groupOfActorId(entry.actorId())))
                .filter(entry -> query.actorIdPrefix() == null || entry.actorId().startsWith(query.actorIdPrefix()))
                .toList();
    }

    private void appendOwnerRepairAuditEntry(StringBuilder json, OwnerRepairOpsAuditRecord entry) {
        json.append("{\"action\":\"").append(escape(entry.action())).append("\",")
                .append("\"ownerKey\":\"").append(escape(entry.ownerKey())).append("\",")
                .append("\"released\":").append(entry.released()).append(',')
                .append("\"at\":\"").append(entry.at()).append("\",")
                .append("\"remote\":\"").append(escape(entry.remote())).append("\",")
                .append("\"operator\":\"").append(escape(entry.operator())).append("\"}");
    }

    private void appendActorIncident(StringBuilder json, ActorIncidentRecord entry) {
        json.append("{\"kind\":\"").append(entry.kind().name()).append("\",")
                .append("\"actorId\":\"").append(escape(entry.actorId())).append("\",")
                .append("\"category\":\"").append(entry.category().name()).append("\",")
                .append("\"reason\":\"").append(escape(entry.reason())).append("\",")
                .append("\"errorType\":\"").append(escape(entry.errorType())).append("\",")
                .append("\"message\":\"").append(escape(entry.message())).append("\",")
                .append("\"at\":\"").append(entry.at()).append("\"}");
    }

    private void appendActorMailbox(StringBuilder json, ActorMailboxStats entry) {
        json.append("{\"actorId\":\"").append(escape(entry.actor().id())).append("\",")
                .append("\"group\":\"").append(escape(ActorMailboxDiagnostics.groupOfActorId(entry.actor().id()))).append("\",")
                .append("\"queuedTasks\":").append(entry.queuedTasks()).append(',')
                .append("\"queuedTasksByCategory\":");
        appendCategoryMap(json, entry.queuedTasksByCategory());
        json.append('}');
    }

    private void appendActorSlowTask(StringBuilder json, ActorSlowTaskRecord entry) {
        json.append("{\"actorId\":\"").append(escape(entry.actorId())).append("\",")
                .append("\"category\":\"").append(entry.category().name()).append("\",")
                .append("\"elapsedMillis\":").append(entry.elapsedMillis()).append(',')
                .append("\"thresholdMillis\":").append(entry.thresholdMillis()).append(',')
                .append("\"at\":\"").append(entry.at()).append("\"}");
    }

    private void appendActorHotspot(StringBuilder json, ActorHotspotCandidate entry) {
        json.append("{\"actorId\":\"").append(escape(entry.actorId())).append("\",")
                .append("\"group\":\"").append(escape(entry.group())).append("\",")
                .append("\"queuedTasks\":").append(entry.queuedTasks()).append(',')
                .append("\"recentSlowTasks\":").append(entry.recentSlowTasks()).append(',')
                .append("\"maxSlowTaskMillis\":").append(entry.maxSlowTaskMillis()).append(',')
                .append("\"action\":\"").append(entry.action().name()).append("\"}");
    }

    private void appendActorHotspotOverride(StringBuilder json, ActorHotspotOverride entry) {
        json.append("{\"actorId\":\"").append(escape(entry.actorId())).append("\",")
                .append("\"group\":\"").append(escape(ActorMailboxDiagnostics.groupOfActorId(entry.actorId())))
                .append("\",")
                .append("\"mode\":\"").append(entry.mode().name()).append("\",")
                .append("\"expiresAt\":\"").append(entry.expiresAt()).append("\",")
                .append("\"reason\":\"").append(escape(entry.reason())).append("\"}");
    }

    private void appendActorHotspotMigrationResult(StringBuilder json, ActorHotspotMigrationResult result) {
        json.append("{\"actorId\":\"").append(escape(result.actorId())).append("\",")
                .append("\"identity\":\"").append(escape(result.identity()
                        .map(identity -> identity.type() + ":" + identity.key())
                        .orElse(""))).append("\",")
                .append("\"targetService\":\"").append(escape(result.target()
                        .map(target -> target.serviceId().wireName())
                        .orElse(""))).append("\",")
                .append("\"targetActor\":\"").append(escape(result.target()
                        .map(target -> target.actorRef().id())
                        .orElse(""))).append("\",")
                .append("\"status\":\"").append(result.status().name()).append("\",")
                .append("\"reason\":\"").append(escape(result.reason())).append("\"}");
    }

    private void appendCategoryMap(StringBuilder json, Map<ActorTaskCategory, Integer> values) {
        json.append('{');
        ActorTaskCategory[] categories = ActorTaskCategory.values();
        for (int i = 0; i < categories.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            ActorTaskCategory category = categories[i];
            json.append('"').append(category.name()).append("\":")
                    .append(values.getOrDefault(category, 0));
        }
        json.append('}');
    }

    private void recordOwnerRepairAudit(String action, String ownerKey, int released, HttpExchange exchange) {
        ownerRepairOpsAuditSink.record(new OwnerRepairOpsAuditRecord(
                action,
                ownerKey,
                released,
                Instant.now(),
                remoteAddress(exchange),
                operator(exchange)
        ));
    }

    private boolean authorizeAdmin(HttpExchange exchange) throws IOException {
        if (!securityConfig.enabled()) {
            return true;
        }
        String token = exchange.getRequestHeaders().getFirst(securityConfig.tokenHeader());
        if (sameToken(token, securityConfig.adminToken())) {
            return true;
        }
        respondJson(exchange, 401, "{\"error\":\"unauthorized\"}");
        return false;
    }

    private void respondJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        respond(exchange, statusCode, JSON_CONTENT_TYPE, body);
    }

    private void respond(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String rawName = equals >= 0 ? part.substring(0, equals) : part;
            if (!name.equals(urlDecode(rawName))) {
                continue;
            }
            String rawValue = equals >= 0 ? part.substring(equals + 1) : "";
            return urlDecode(rawValue);
        }
        return null;
    }

    private static OwnerRepairOpsAuditQuery ownerRepairAuditQuery(String rawQuery) {
        String action = blankToNull(queryParam(rawQuery, "action"));
        if (action != null && !"release".equals(action) && !"release-all".equals(action)) {
            throw new IllegalArgumentException("invalid_action");
        }
        String ownerKey = blankToNull(queryParam(rawQuery, "ownerKey"));
        int offset = intQueryParam(rawQuery, "offset", 0);
        int limit = intQueryParam(rawQuery, "limit", DEFAULT_OWNER_REPAIR_AUDIT_LIMIT);
        if (offset < 0) {
            throw new IllegalArgumentException("invalid_offset");
        }
        if (limit < 1 || limit > MAX_OWNER_REPAIR_AUDIT_ENTRIES) {
            throw new IllegalArgumentException("invalid_limit");
        }
        return new OwnerRepairOpsAuditQuery(action, ownerKey, offset, limit);
    }

    private static ActorIncidentQuery actorIncidentQuery(String rawQuery) {
        ActorIncidentKind kind = null;
        String rawKind = blankToNull(queryParam(rawQuery, "kind"));
        if (rawKind != null) {
            try {
                kind = ActorIncidentKind.valueOf(rawKind);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid_kind", e);
            }
        }
        ActorTaskCategory category = null;
        String rawCategory = blankToNull(queryParam(rawQuery, "category"));
        if (rawCategory != null) {
            try {
                category = ActorTaskCategory.valueOf(rawCategory);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid_category", e);
            }
        }
        String actorId = blankToNull(queryParam(rawQuery, "actorId"));
        String reason = blankToNull(queryParam(rawQuery, "reason"));
        int offset = intQueryParam(rawQuery, "offset", 0);
        int limit = intQueryParam(rawQuery, "limit", DEFAULT_ACTOR_INCIDENT_LIMIT);
        if (offset < 0) {
            throw new IllegalArgumentException("invalid_offset");
        }
        if (limit < 1 || limit > MAX_ACTOR_INCIDENT_ENTRIES) {
            throw new IllegalArgumentException("invalid_limit");
        }
        return new ActorIncidentQuery(kind, actorId, category, reason, offset, limit);
    }

    private static ActorMailboxQuery actorMailboxQuery(String rawQuery) {
        String group = blankToNull(queryParam(rawQuery, "group"));
        String actorIdPrefix = blankToNull(queryParam(rawQuery, "actorIdPrefix"));
        ActorTaskCategory category = null;
        String rawCategory = blankToNull(queryParam(rawQuery, "category"));
        if (rawCategory != null) {
            try {
                category = ActorTaskCategory.valueOf(rawCategory);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid_category", e);
            }
        }
        int minQueuedTasks = intQueryParam(rawQuery, "minQueuedTasks", 1);
        int offset = intQueryParam(rawQuery, "offset", 0);
        int limit = intQueryParam(rawQuery, "limit", DEFAULT_ACTOR_MAILBOX_LIMIT);
        if (minQueuedTasks < 0) {
            throw new IllegalArgumentException("invalid_minQueuedTasks");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("invalid_offset");
        }
        if (limit < 1 || limit > MAX_ACTOR_MAILBOX_ENTRIES) {
            throw new IllegalArgumentException("invalid_limit");
        }
        return new ActorMailboxQuery(group, actorIdPrefix, category, minQueuedTasks, offset, limit);
    }

    private static ActorSlowTaskQuery actorSlowTaskQuery(String rawQuery) {
        String actorId = blankToNull(queryParam(rawQuery, "actorId"));
        ActorTaskCategory category = null;
        String rawCategory = blankToNull(queryParam(rawQuery, "category"));
        if (rawCategory != null) {
            try {
                category = ActorTaskCategory.valueOf(rawCategory);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid_category", e);
            }
        }
        int minElapsedMillis = intQueryParam(rawQuery, "minElapsedMillis", 0);
        int offset = intQueryParam(rawQuery, "offset", 0);
        int limit = intQueryParam(rawQuery, "limit", DEFAULT_ACTOR_SLOW_TASK_LIMIT);
        if (minElapsedMillis < 0) {
            throw new IllegalArgumentException("invalid_minElapsedMillis");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("invalid_offset");
        }
        if (limit < 1 || limit > MAX_ACTOR_SLOW_TASK_ENTRIES) {
            throw new IllegalArgumentException("invalid_limit");
        }
        return new ActorSlowTaskQuery(actorId, category, minElapsedMillis, offset, limit);
    }

    private static ActorHotspotQuery actorHotspotQuery(String rawQuery) {
        ActorHotspotAction action = null;
        String rawAction = blankToNull(queryParam(rawQuery, "action"));
        if (rawAction != null) {
            try {
                action = ActorHotspotAction.valueOf(rawAction);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid_action", e);
            }
        }
        String group = blankToNull(queryParam(rawQuery, "group"));
        String actorIdPrefix = blankToNull(queryParam(rawQuery, "actorIdPrefix"));
        int offset = intQueryParam(rawQuery, "offset", 0);
        int limit = intQueryParam(rawQuery, "limit", DEFAULT_ACTOR_HOTSPOT_LIMIT);
        if (offset < 0) {
            throw new IllegalArgumentException("invalid_offset");
        }
        if (limit < 1 || limit > MAX_ACTOR_HOTSPOT_ENTRIES) {
            throw new IllegalArgumentException("invalid_limit");
        }
        return new ActorHotspotQuery(action, group, actorIdPrefix, offset, limit);
    }

    private static ActorHotspotOverrideQuery actorHotspotOverrideQuery(String rawQuery) {
        ActorHotspotOverrideMode mode = null;
        String rawMode = blankToNull(queryParam(rawQuery, "mode"));
        if (rawMode != null) {
            try {
                mode = ActorHotspotOverrideMode.valueOf(rawMode);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid_mode", e);
            }
        }
        String group = blankToNull(queryParam(rawQuery, "group"));
        String actorIdPrefix = blankToNull(queryParam(rawQuery, "actorIdPrefix"));
        int offset = intQueryParam(rawQuery, "offset", 0);
        int limit = intQueryParam(rawQuery, "limit", DEFAULT_ACTOR_HOTSPOT_OVERRIDE_LIMIT);
        if (offset < 0) {
            throw new IllegalArgumentException("invalid_offset");
        }
        if (limit < 1 || limit > MAX_ACTOR_HOTSPOT_OVERRIDE_ENTRIES) {
            throw new IllegalArgumentException("invalid_limit");
        }
        return new ActorHotspotOverrideQuery(mode, group, actorIdPrefix, offset, limit);
    }

    private static ActorHotspotOverrideMode actorHotspotOverrideMode(String rawQuery) {
        String rawMode = blankToNull(queryParam(rawQuery, "mode"));
        if (rawMode == null) {
            throw new IllegalArgumentException("missing_mode");
        }
        try {
            return ActorHotspotOverrideMode.valueOf(rawMode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid_mode", e);
        }
    }

    private static int intQueryParam(String rawQuery, String name, int defaultValue) {
        String value = blankToNull(queryParam(rawQuery, name));
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid_" + name, e);
        }
    }

    private static long longQueryParam(String rawQuery, String name, long defaultValue) {
        String value = blankToNull(queryParam(rawQuery, name));
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid_" + name, e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String remoteAddress(HttpExchange exchange) {
        return exchange.getRemoteAddress() == null ? "" : exchange.getRemoteAddress().toString();
    }

    private String operator(HttpExchange exchange) {
        String operator = exchange.getRequestHeaders().getFirst(securityConfig.operatorHeader());
        return operator == null ? "" : operator;
    }

    private static boolean sameToken(String provided, String expected) {
        if (provided == null) {
            return false;
        }
        byte[] left = provided.getBytes(StandardCharsets.UTF_8);
        byte[] right = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    private record ActorHotspotOverrideQuery(
            ActorHotspotOverrideMode mode,
            String group,
            String actorIdPrefix,
            int offset,
            int limit
    ) {
    }

    @Override
    public void close() {
        server.stop(0);
    }

}
