package com.commonbattle.cluster.boot;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterLaunchPlanTest {
    @Test
    void createsOneCommandPerDeploymentService() {
        ClusterDeploymentManifest manifest = ClusterDeploymentManifest.defaultManifest();

        ClusterLaunchPlan plan = ClusterLaunchPlan.fromManifest(manifest, Path.of("src/main/resources"), "target/classes");

        assertEquals(manifest.services().size(), plan.commands().size());
        assertEquals("java", plan.command("game").orElseThrow().arguments().getFirst());
        assertEquals("com.commonbattle.cluster.boot.GameServerMain",
                plan.command("game").orElseThrow().arguments().get(3));
    }

    @Test
    void sceneVariantsShareMainClassButUseDifferentConfigs() {
        ClusterLaunchPlan plan = ClusterLaunchPlan.fromManifest(
                ClusterDeploymentManifest.defaultManifest(),
                Path.of("src/main/resources"),
                "target/classes"
        );

        ClusterLaunchCommand small = plan.command("scene-small").orElseThrow();
        ClusterLaunchCommand large = plan.command("scene-large").orElseThrow();

        assertEquals(small.arguments().get(3), large.arguments().get(3));
        assertTrue(small.arguments().get(4).endsWith("cluster\\scene-small.properties")
                || small.arguments().get(4).endsWith("cluster/scene-small.properties"));
        assertTrue(large.arguments().get(4).endsWith("cluster\\scene-large.properties")
                || large.arguments().get(4).endsWith("cluster/scene-large.properties"));
    }

    @Test
    void shellLineQuotesArgumentsWithSpaces() {
        ClusterLaunchPlan plan = ClusterLaunchPlan.fromManifest(
                ClusterDeploymentManifest.defaultManifest(),
                Path.of("G:/battle/Common Battle/src/main/resources"),
                "target/classes;target/dependency/*"
        );

        String scene = plan.command("scene-small").orElseThrow().shellLine();

        assertTrue(scene.contains("\"G:\\battle\\Common Battle\\src\\main\\resources\\cluster\\scene-small.properties\"")
                || scene.contains("\"G:/battle/Common Battle/src/main/resources/cluster/scene-small.properties\""));
    }
}
