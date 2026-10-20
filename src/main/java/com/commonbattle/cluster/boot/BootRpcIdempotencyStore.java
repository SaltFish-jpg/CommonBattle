package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.rpc.InMemoryRpcIdempotencyStore;
import com.commonbattle.cluster.rpc.RpcIdempotencyStore;
import com.commonbattle.cluster.rpc.SerializedRpcIdempotencyStore;
import com.commonbattle.persistence.JdbcAtomicBytesStore;

import java.time.Clock;
import java.util.Objects;

/**
 * RPC 幂等状态存储的启动装配入口。
 */
final class BootRpcIdempotencyStore {
    private BootRpcIdempotencyStore() {
    }

    static RpcIdempotencyStore configure(
            ClusterNodeConfig config,
            PayloadCodecRegistry payloadCodecs,
            Clock clock
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(payloadCodecs, "payloadCodecs");
        Objects.requireNonNull(clock, "clock");
        var governance = config.rpcGovernanceConfig();
        return switch (config.rpcIdempotencyStoreKind()) {
            case MEMORY -> new InMemoryRpcIdempotencyStore(
                    governance.idempotencyCacheCapacity(), governance.idempotencyCacheTtl(), clock
            );
            case JDBC -> new SerializedRpcIdempotencyStore(
                    jdbcStore(config),
                    payloadCodecs,
                    governance.idempotencyCacheTtl(),
                    governance.idempotencyLeaseDuration(),
                    clock
            );
        };
    }

    private static JdbcAtomicBytesStore jdbcStore(ClusterNodeConfig config) {
        JdbcAtomicBytesStore store = new JdbcAtomicBytesStore(
                new DriverManagerDataSource(
                        config.rpcIdempotencyJdbcDriver(),
                        config.rpcIdempotencyJdbcUrl(),
                        config.rpcIdempotencyJdbcUser(),
                        config.rpcIdempotencyJdbcPassword()
                ),
                config.rpcIdempotencyJdbcTable()
        );
        if (config.rpcIdempotencyJdbcInitializeSchema()) {
            store.initializeSchema();
        }
        return store;
    }
}
