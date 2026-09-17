package com.commonbattle.observability;

import com.commonbattle.game.event.OwnerEventRepairIsolatedOwner;
import com.commonbattle.game.event.OwnerEventRepairIsolationAdmin;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 轻量运维 HTTP 端点。
 * 提供 /live、/ready 和 /health，供容器探活、发布系统和人工排障读取运行时健康状态。
 */
public final class OpsHttpServer implements AutoCloseable {
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String METRICS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final RuntimeHealthProbe probe;
    private final ServerDrainController drainController;
    private final DrainConfig drainConfig;
    private final Collection<OwnerEventRepairIsolationAdmin> repairIsolationAdmins;
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
        this.probe = Objects.requireNonNull(probe, "probe");
        this.drainController = drainController;
        this.drainConfig = Objects.requireNonNull(drainConfig, "drainConfig");
        this.repairIsolationAdmins = List.copyOf(Objects.requireNonNull(repairIsolationAdmins,
                "repairIsolationAdmins"));
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
        respondJson(exchange, code, "{\"released\":" + released
                + ",\"ownerKey\":\"" + escape(ownerKey) + "\"}");
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

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
