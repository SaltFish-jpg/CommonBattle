package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceRegistry;
import com.commonbattle.cluster.RegistrySubscriber;
import com.commonbattle.runtime.DrainPhase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryLeaseRenewerTest {
    @Test
    void beginDrainPublishFailureIsCounted() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RegistryLeaseRenewer renewer = new RegistryLeaseRenewer(
                new FailingAfterStartRegistry(),
                descriptor(),
                Duration.ofSeconds(5),
                Duration.ofHours(1),
                scheduler
        );
        try {
            renewer.start();

            assertThrows(IllegalStateException.class, renewer::beginDrain);

            assertEquals(1, renewer.stats().failedRenewals());
            assertEquals(DrainPhase.EXTERNAL_ADVERTISEMENT, renewer.phase());
        } finally {
            renewer.close();
            scheduler.shutdownNow();
        }
    }

    private static ServiceDescriptor descriptor() {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                new ServiceEndpoint("127.0.0.1", 9001),
                Set.of(),
                Map.of()
        );
    }

    private static final class FailingAfterStartRegistry implements ServiceRegistry {
        private int registers;

        @Override
        public void register(ServiceDescriptor service) {
            register(service, Duration.ofSeconds(5));
        }

        @Override
        public void register(ServiceDescriptor service, Duration leaseTtl) {
            registers++;
            if (registers > 1) {
                throw new IllegalStateException("registry down");
            }
        }

        @Override
        public boolean heartbeat(ServiceId serviceId, Duration leaseTtl) {
            return true;
        }

        @Override
        public void unregister(ServiceId serviceId) {
        }

        @Override
        public List<ServiceDescriptor> list(ServiceKind kind) {
            return List.of();
        }

        @Override
        public AutoCloseable subscribe(ServiceKind kind, RegistrySubscriber subscriber) {
            return () -> {
            };
        }
    }
}
