package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.agent.BusinessAgentRpcOperations;
import com.commonbattle.game.chat.ChatOperations;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterDescriptorsTest {
    @Test
    void fromConfigAppliesRoutingMetadataToLocalDescriptor() {
        Properties properties = base(ServiceKind.GAME);
        properties.setProperty("cluster.route.tag", "gray");
        properties.setProperty("cluster.deployment.group", "canary-1");
        properties.setProperty("cluster.metadata.zone.partition", "east");
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        ServiceDescriptor descriptor = ClusterDescriptors.fromConfig(config);

        assertEquals("gray", descriptor.metadata(ServiceMetadata.ROUTE_TAG));
        assertEquals("canary-1", descriptor.metadata(ServiceMetadata.DEPLOYMENT_GROUP));
        assertEquals("east", descriptor.metadata("zone.partition"));
        assertTrue(descriptor.supports("game.resume"));
        assertTrue(descriptor.supports(BusinessAgentRpcOperations.DISPATCH));
    }

    @Test
    void sceneDescriptorDeclaresGenericBusinessAgentDispatch() {
        ServiceDescriptor descriptor = ClusterDescriptors.descriptor(
                com.commonbattle.cluster.ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                new com.commonbattle.cluster.ServiceEndpoint("127.0.0.1", 9102)
        );

        assertTrue(descriptor.supports(SceneOperations.ENTER));
        assertTrue(descriptor.supports(BusinessAgentRpcOperations.DISPATCH));
    }

    @Test
    void chatDescriptorDeclaresChannelAndGenericAgentOperations() {
        ServiceDescriptor descriptor = ClusterDescriptors.descriptor(
                com.commonbattle.cluster.ServiceId.of(ServiceKind.CHAT, "r1", "chat-1"),
                new com.commonbattle.cluster.ServiceEndpoint("127.0.0.1", 9106)
        );

        assertTrue(descriptor.supports(ChatOperations.JOIN_CHANNEL));
        assertTrue(descriptor.supports(ChatOperations.LEAVE_CHANNEL));
        assertTrue(descriptor.supports(ChatOperations.SEND_CHANNEL));
        assertTrue(descriptor.supports(BusinessAgentRpcOperations.DISPATCH));
    }

    @Test
    void withConfigMetadataMergesRoutingMetadataWithRuntimeMetadata() {
        Properties properties = base(ServiceKind.SCENE);
        properties.setProperty("cluster.route.tag", "gray");
        properties.setProperty("cluster.deployment.group", "scene-canary");
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
        ServiceDescriptor scene = new ServiceDescriptor(
                config.serviceId(),
                config.endpoint(),
                Set.of(SceneOperations.ENTER),
                Map.of("scene.mode", "MULTI_SMALL_SCENE", ServiceMetadata.LOAD_USED, "3")
        );

        ServiceDescriptor descriptor = ClusterDescriptors.withConfigMetadata(scene, config);

        assertEquals("MULTI_SMALL_SCENE", descriptor.metadata("scene.mode"));
        assertEquals("3", descriptor.metadata(ServiceMetadata.LOAD_USED));
        assertEquals("gray", descriptor.metadata(ServiceMetadata.ROUTE_TAG));
        assertEquals("scene-canary", descriptor.metadata(ServiceMetadata.DEPLOYMENT_GROUP));
    }

    @Test
    void centerDescriptorForRemoteSeedDoesNotInheritCallerMetadata() {
        Properties properties = base(ServiceKind.GAME);
        properties.setProperty("cluster.route.tag", "gray");
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);

        ServiceDescriptor center = ClusterDescriptors.center(config);

        assertEquals(ServiceKind.CENTER, center.id().kind());
        assertEquals(null, center.metadata(ServiceMetadata.ROUTE_TAG));
    }

    private static Properties base(ServiceKind kind) {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", kind.name());
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", kind.name().toLowerCase() + "-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9001");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.actor.workers", "4");
        return properties;
    }
}
