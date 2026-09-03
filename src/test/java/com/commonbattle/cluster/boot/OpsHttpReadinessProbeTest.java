package com.commonbattle.cluster.boot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsHttpReadinessProbeTest {
    @Test
    void returnsTrueForSuccessfulOpsResponse() throws Exception {
        int port = freePort();
        HttpServer server = server(port, 200);
        server.start();
        try {
            OpsHttpReadinessProbe probe = new OpsHttpReadinessProbe(Map.of(
                    "game",
                    URI.create("http://127.0.0.1:" + port + "/live")
            ));

            assertTrue(probe.ready(command("game"), process("game")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsFalseForUnavailableOpsResponse() throws Exception {
        int port = freePort();
        HttpServer server = server(port, 503);
        server.start();
        try {
            OpsHttpReadinessProbe probe = new OpsHttpReadinessProbe(Map.of(
                    "game",
                    URI.create("http://127.0.0.1:" + port + "/ready")
            ));

            assertFalse(probe.ready(command("game"), process("game")));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(int port, int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        return server;
    }

    private static ClusterLaunchCommand command(String serviceName) {
        return new ClusterLaunchCommand(serviceName, java.util.List.of("java", serviceName));
    }

    private static ClusterProcess process(String serviceName) {
        return new ClusterProcess() {
            @Override
            public String serviceName() {
                return serviceName;
            }

            @Override
            public boolean alive() {
                return true;
            }

            @Override
            public void close() {
            }
        };
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
