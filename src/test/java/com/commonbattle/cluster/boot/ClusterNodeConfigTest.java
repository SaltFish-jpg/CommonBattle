package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterNodeConfigTest {
    @Test
    void classpathDefaultConfigsAreValidForTheirServiceKind() {
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/center.properties")
                .validate(ServiceKind.CENTER).throwIfInvalid());
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/game.properties")
                .validate(ServiceKind.GAME).throwIfInvalid());
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/region.properties")
                .validate(ServiceKind.REGION).throwIfInvalid());
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/proxy.properties")
                .validate(ServiceKind.PROXY).throwIfInvalid());
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/scene-small.properties")
                .validate(ServiceKind.SCENE).throwIfInvalid());
        assertDoesNotThrow(() -> ClusterNodeConfig.fromClasspath("cluster/scene-large.properties")
                .validate(ServiceKind.SCENE).throwIfInvalid());
    }

    @Test
    void validationCollectsAllObviousConfigIssues() {
        Properties properties = base();
        properties.setProperty("cluster.kind", "GAME");
        properties.setProperty("cluster.port", "bad-port");
        properties.remove("cluster.center.host");
        properties.setProperty("scene.mode", "UNKNOWN");
        properties.setProperty("scene.capacity", "0");
        properties.setProperty("scene.shards", "-1");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.SCENE);

        assertFalse(validation.valid());
        List<String> keys = validation.issues().stream().map(ClusterConfigIssue::key).toList();
        assertTrue(keys.contains("cluster.kind"));
        assertTrue(keys.contains("cluster.port"));
        assertTrue(keys.contains("cluster.center.host"));
        assertTrue(keys.contains("scene.mode"));
        assertTrue(keys.contains("scene.capacity"));
        assertTrue(keys.contains("scene.shards"));
    }

    @Test
    void validationThrowsReadableErrorMessage() {
        Properties properties = base();
        properties.setProperty("cluster.port", "70000");

        ClusterConfigValidation validation = ClusterNodeConfig.fromProperties(properties).validate(ServiceKind.GAME);

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                validation::throwIfInvalid
        );
        assertTrue(error.getMessage().contains("cluster.port"));
    }

    private static Properties base() {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", "GAME");
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", "game-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9001");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.actor.workers", "4");
        return properties;
    }
}
