package com.commonbattle.cluster.boot;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterLaunchPlanMainTest {
    @Test
    void renderPrefixesCommandWithServiceName() {
        ClusterLaunchPlan plan = ClusterLaunchPlan.fromManifest(
                ClusterDeploymentManifest.defaultManifest(),
                Path.of("src/main/resources"),
                "target/classes"
        );

        List<String> lines = ClusterLaunchPlanMain.render(plan);

        assertEquals(7, lines.size());
        assertTrue(lines.getFirst().startsWith("center=java -cp target/classes "));
        assertTrue(lines.stream().anyMatch(line ->
                line.startsWith("scene-large=")
                        && line.contains("com.commonbattle.cluster.boot.SceneServerMain")
                        && line.contains("scene-large.properties")));
    }

    @Test
    void mainRejectsTooManyArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                ClusterLaunchPlanMain.main(new String[]{"a", "b", "c"}));
    }
}
