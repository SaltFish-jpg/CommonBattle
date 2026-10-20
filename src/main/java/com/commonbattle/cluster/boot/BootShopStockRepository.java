package com.commonbattle.cluster.boot;

import com.commonbattle.game.shop.InMemoryShopStockRepository;
import com.commonbattle.game.shop.JdbcShopStockRepository;
import com.commonbattle.game.shop.SerializedShopStockRepository;
import com.commonbattle.game.shop.ShopStockReservationRepository;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;

import java.time.Clock;
import java.util.Objects;

/**
 * 中心服全局商店库存仓储的启动装配入口。
 */
final class BootShopStockRepository {
    private BootShopStockRepository() {
    }

    static ShopStockReservationRepository configure(ClusterNodeConfig config, Clock clock) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        return switch (config.shopStockStoreKind()) {
            case MEMORY -> new InMemoryShopStockRepository(clock, config.shopStockReservationTtl());
            case ATOMIC_MEMORY -> new SerializedShopStockRepository(
                    new InMemoryAtomicBytesStore(), clock, config.shopStockReservationTtl()
            );
            case JDBC -> jdbcRepository(config, clock);
        };
    }

    private static JdbcShopStockRepository jdbcRepository(ClusterNodeConfig config, Clock clock) {
        JdbcShopStockRepository repository = new JdbcShopStockRepository(
                new DriverManagerDataSource(
                        config.shopStockJdbcDriver(),
                        config.shopStockJdbcUrl(),
                        config.shopStockJdbcUser(),
                        config.shopStockJdbcPassword()
                ),
                config.shopStockJdbcTable(),
                config.shopStockReservationJdbcTable(),
                clock,
                config.shopStockReservationTtl()
        );
        if (config.shopStockJdbcInitializeSchema()) {
            repository.initializeSchema();
        }
        return repository;
    }
}
