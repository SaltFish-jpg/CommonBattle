package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ServiceEndpoint;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于运维 HTTP 端点的启动探针。
 * 本地开发通常使用 /live，发布系统可使用 /ready 执行更严格的流量接入检查。
 */
public final class OpsHttpReadinessProbe implements ClusterReadinessProbe {
    private final Map<String, URI> probes;
    private final HttpClient client;
    private final Duration requestTimeout;

    public OpsHttpReadinessProbe(Map<String, URI> probes) {
        this(probes, HttpClient.newHttpClient(), Duration.ofMillis(500));
    }

    OpsHttpReadinessProbe(Map<String, URI> probes, HttpClient client, Duration requestTimeout) {
        this.probes = Map.copyOf(Objects.requireNonNull(probes, "probes"));
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = requestTimeout;
    }

    public static OpsHttpReadinessProbe live(ClusterDeploymentManifest manifest) {
        return fromManifest(manifest, "/live");
    }

    public static OpsHttpReadinessProbe ready(ClusterDeploymentManifest manifest) {
        return fromManifest(manifest, "/ready");
    }

    public static OpsHttpReadinessProbe fromManifest(ClusterDeploymentManifest manifest, String path) {
        Objects.requireNonNull(manifest, "manifest");
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        Map<String, URI> probes = new HashMap<>();
        for (ClusterDeploymentService service : manifest.services()) {
            ServiceEndpoint endpoint = ClusterNodeConfig.fromClasspath(service.configResource()).opsEndpoint();
            probes.put(service.name(), URI.create("http://" + endpoint.host() + ":" + endpoint.port() + path));
        }
        return new OpsHttpReadinessProbe(probes);
    }

    @Override
    public boolean ready(ClusterLaunchCommand command, ClusterProcess process) {
        URI uri = probes.get(command.serviceName());
        if (uri == null) {
            throw new IllegalArgumentException("Missing ops probe for service " + command.serviceName());
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .GET()
                .build();
        try {
            int statusCode = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return statusCode >= 200 && statusCode < 300;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
