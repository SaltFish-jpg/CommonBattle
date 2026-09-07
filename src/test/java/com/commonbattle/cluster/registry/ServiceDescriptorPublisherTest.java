package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.runtime.DrainPhase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceDescriptorPublisherTest {
    @Test
    void publishOnceRefreshesDynamicLoadMetadata() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        AtomicInteger load = new AtomicInteger(1);
        ServiceDescriptor base = descriptor();
        ServiceDescriptorPublisher publisher = new ServiceDescriptorPublisher(
                registry,
                () -> ServiceMetadata.withLoad(base, load.get(), 10),
                Duration.ofSeconds(5),
                Duration.ofHours(1)
        );
        try {
            publisher.publishOnce();
            load.set(4);
            publisher.publishOnce();

            ServiceDescriptor published = registry.list(ServiceKind.SCENE).getFirst();
            assertEquals("4", published.metadata(ServiceMetadata.LOAD_USED));
            assertEquals("10", published.metadata(ServiceMetadata.LOAD_CAPACITY));
            assertEquals(2, publisher.stats().attempts());
            assertEquals(2, publisher.stats().succeeded());
            assertEquals(0, publisher.stats().failed());
        } finally {
            publisher.close();
        }
    }

    @Test
    void drainAndResumePublishServiceState() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ServiceDescriptorPublisher publisher = new ServiceDescriptorPublisher(
                registry,
                ServiceDescriptorPublisherTest::descriptor,
                Duration.ofSeconds(5),
                Duration.ofHours(1),
                scheduler
        );
        try {
            publisher.start();
            publisher.beginDrain();

            assertTrue(publisher.isDraining());
            assertTrue(registry.list(ServiceKind.SCENE).getFirst().draining());
            assertEquals(DrainPhase.EXTERNAL_ADVERTISEMENT, publisher.phase());

            publisher.resumeAccepting();

            assertFalse(publisher.isDraining());
            assertFalse(registry.list(ServiceKind.SCENE).getFirst().draining());
        } finally {
            publisher.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void failedPublishIsCounted() {
        ServiceDescriptorPublisher publisher = new ServiceDescriptorPublisher(
                new FailingRegistry(),
                ServiceDescriptorPublisherTest::descriptor,
                Duration.ofSeconds(5),
                Duration.ofHours(1)
        );
        try {
            assertThrows(IllegalStateException.class, publisher::publishOnce);

            assertEquals(1, publisher.stats().attempts());
            assertEquals(0, publisher.stats().succeeded());
            assertEquals(1, publisher.stats().failed());
        } finally {
            publisher.close();
        }
    }

    private static ServiceDescriptor descriptor() {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                new ServiceEndpoint("127.0.0.1", 9001),
                Set.of("scene.enter"),
                Map.of()
        );
    }

    private static final class FailingRegistry implements com.commonbattle.cluster.ServiceRegistry {
        @Override
        public void register(ServiceDescriptor service) {
            throw new IllegalStateException("registry down");
        }

        @Override
        public void register(ServiceDescriptor service, Duration leaseTtl) {
            throw new IllegalStateException("registry down");
        }

        @Override
        public void unregister(ServiceId serviceId) {
        }

        @Override
        public java.util.List<ServiceDescriptor> list(ServiceKind kind) {
            return java.util.List.of();
        }

        @Override
        public AutoCloseable subscribe(ServiceKind kind, com.commonbattle.cluster.RegistrySubscriber subscriber) {
            return () -> {
            };
        }
    }
}
