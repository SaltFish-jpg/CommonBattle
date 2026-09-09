package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterDeploymentManifestTest {
    @Test
    void defaultManifestDescribesAllStandaloneServices() {
        ClusterDeploymentManifest manifest = ClusterDeploymentManifest.defaultManifest();

        assertEquals(List.of("center", "game", "chat", "scene-small", "scene-large", "proxy", "region"),
                manifest.services().stream().map(ClusterDeploymentService::name).toList());
        assertEquals(ServiceKind.CHAT, manifest.service("chat").orElseThrow().kind());
        assertEquals(ServiceKind.SCENE, manifest.service("scene-large").orElseThrow().kind());
        assertEquals("com.commonbattle.cluster.boot.SceneServerMain",
                manifest.service("scene-small").orElseThrow().mainClassName());
    }

    @Test
    void defaultManifestMatchesClasspathConfigsAndMainClasses() {
        ClusterDeploymentValidation validation = ClusterDeploymentManifest.defaultManifest().validate();

        assertTrue(validation.valid(), () -> validation.issues().toString());
    }

    @Test
    void defaultManifestKeepsNodeAndOpsPortsUnique() {
        ClusterDeploymentManifest manifest = ClusterDeploymentManifest.defaultManifest();
        Set<ServiceId> serviceIds = new HashSet<>();
        Set<ServiceEndpoint> endpoints = new HashSet<>();
        Set<ServiceEndpoint> opsEndpoints = new HashSet<>();

        for (ClusterDeploymentService service : manifest.services()) {
            ClusterNodeConfig config = ClusterNodeConfig.fromClasspath(service.configResource());
            serviceIds.add(config.serviceId());
            endpoints.add(config.endpoint());
            opsEndpoints.add(config.opsEndpoint());
        }

        assertEquals(manifest.services().size(), serviceIds.size());
        assertEquals(manifest.services().size(), endpoints.size());
        assertEquals(manifest.services().size(), opsEndpoints.size());
    }

    @Test
    void defaultManifestDocumentsRuntimeWatches() {
        ClusterDeploymentManifest manifest = ClusterDeploymentManifest.defaultManifest();

        assertEquals(Set.of(ServiceKind.CENTER, ServiceKind.GAME, ServiceKind.CHAT, ServiceKind.SCENE, ServiceKind.PROXY, ServiceKind.REGION),
                manifest.service("center").orElseThrow().watches());
        assertEquals(Set.of(ServiceKind.CHAT, ServiceKind.SCENE, ServiceKind.PROXY, ServiceKind.REGION),
                manifest.service("game").orElseThrow().watches());
        assertEquals(Set.of(ServiceKind.GAME, ServiceKind.SCENE, ServiceKind.PROXY, ServiceKind.REGION),
                manifest.service("chat").orElseThrow().watches());
        assertEquals(Set.of(ServiceKind.GAME, ServiceKind.CHAT, ServiceKind.PROXY, ServiceKind.REGION),
                manifest.service("scene-small").orElseThrow().watches());
        assertEquals(Set.of(ServiceKind.GAME, ServiceKind.CHAT, ServiceKind.PROXY, ServiceKind.REGION),
                manifest.service("scene-large").orElseThrow().watches());
        assertEquals(Set.of(ServiceKind.CENTER, ServiceKind.GAME, ServiceKind.CHAT, ServiceKind.SCENE, ServiceKind.REGION),
                manifest.service("proxy").orElseThrow().watches());
        assertEquals(Set.of(ServiceKind.GAME, ServiceKind.CHAT, ServiceKind.SCENE, ServiceKind.PROXY),
                manifest.service("region").orElseThrow().watches());
    }

    @Test
    void defaultManifestComputesStartupOrderFromDependencies() {
        ClusterDeploymentManifest manifest = ClusterDeploymentManifest.defaultManifest();

        assertEquals(List.of("center", "proxy", "game", "chat", "scene-small", "scene-large", "region"),
                manifest.startupOrder().stream().map(ClusterDeploymentService::name).toList());
        assertEquals(Set.of("center", "proxy"), manifest.service("game").orElseThrow().startsAfter());
        assertEquals(Set.of("center"), manifest.service("proxy").orElseThrow().startsAfter());
    }

    @Test
    void validationReportsUnknownStartupDependency() {
        java.util.Properties properties = new java.util.Properties();
        properties.putAll(Map.of(
                "cluster.deployment.services", "game",
                "cluster.deployment.service.game.kind", "GAME",
                "cluster.deployment.service.game.config", "cluster/game.properties",
                "cluster.deployment.service.game.main", "com.commonbattle.cluster.boot.GameServerMain",
                "cluster.deployment.service.game.startsAfter", "missing"
        ));

        ClusterDeploymentValidation validation = ClusterDeploymentManifest.fromProperties(properties).validate();

        assertTrue(validation.issues().stream().anyMatch(issue ->
                issue.key().equals("startsAfter") && issue.message().contains("unknown dependency missing")));
    }

    @Test
    void startupOrderRejectsCycles() {
        java.util.Properties properties = new java.util.Properties();
        properties.putAll(Map.of(
                "cluster.deployment.services", "game,scene",
                "cluster.deployment.service.game.kind", "GAME",
                "cluster.deployment.service.game.config", "cluster/game.properties",
                "cluster.deployment.service.game.main", "com.commonbattle.cluster.boot.GameServerMain",
                "cluster.deployment.service.game.startsAfter", "scene",
                "cluster.deployment.service.scene.kind", "SCENE",
                "cluster.deployment.service.scene.config", "cluster/scene-small.properties",
                "cluster.deployment.service.scene.main", "com.commonbattle.cluster.boot.SceneServerMain",
                "cluster.deployment.service.scene.startsAfter", "game"
        ));

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ClusterDeploymentManifest.fromProperties(properties).startupOrder()
        );

        assertTrue(error.getMessage().contains("cycle detected"));
    }

    @Test
    void validationReportsMissingMainClass() {
        java.util.Properties properties = new java.util.Properties();
        properties.putAll(Map.of(
                "cluster.deployment.services", "broken",
                "cluster.deployment.service.broken.kind", "GAME",
                "cluster.deployment.service.broken.config", "cluster/game.properties",
                "cluster.deployment.service.broken.main", "com.commonbattle.MissingMain"
        ));

        ClusterDeploymentValidation validation = ClusterDeploymentManifest.fromProperties(properties).validate();

        assertEquals(1, validation.issues().size());
        assertEquals("main", validation.issues().getFirst().key());
    }
}
