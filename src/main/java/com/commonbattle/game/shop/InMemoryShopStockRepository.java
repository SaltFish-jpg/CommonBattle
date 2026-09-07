package com.commonbattle.game.shop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存全服库存实现。
 * 适合单进程测试和样例；多进程部署时应替换为 Redis Lua、DB CAS 或中心库存 Agent。
 */
public final class InMemoryShopStockRepository implements ShopStockReservationRepository {
    private final ConcurrentHashMap<String, AtomicInteger> stocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration reservationTtl;

    public InMemoryShopStockRepository() {
        this(Clock.systemUTC(), Duration.ZERO);
    }

    public InMemoryShopStockRepository(Clock clock, Duration reservationTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reservationTtl = Objects.requireNonNull(reservationTtl, "reservationTtl");
        if (reservationTtl.isNegative()) {
            throw new IllegalArgumentException("reservationTtl must not be negative");
        }
    }

    public void set(String sku, int count) {
        Objects.requireNonNull(sku, "sku");
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        stocks.put(sku, new AtomicInteger(count));
    }

    public void seed(ShopCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        catalog.definitions().stream()
                .filter(ShopItemDefinition::limitedStock)
                .forEach(definition -> set(definition.sku(), definition.globalStock()));
    }

    @Override
    public boolean reserve(String sku, int count) {
        validateCount(count);
        AtomicInteger stock = stocks.computeIfAbsent(Objects.requireNonNull(sku, "sku"), ignored -> new AtomicInteger());
        while (true) {
            int current = stock.get();
            if (current < count) {
                return false;
            }
            if (stock.compareAndSet(current, current - count)) {
                return true;
            }
        }
    }

    @Override
    public boolean reserve(String reservationId, String sku, int count) {
        Objects.requireNonNull(sku, "sku");
        validateCount(count);
        if (reservationId == null || reservationId.isBlank()) {
            return reserve(sku, count);
        }
        AtomicBoolean reserved = new AtomicBoolean();
        Instant now = clock.instant();
        reservations.compute(reservationId, (ignored, existing) -> {
            if (existing != null) {
                if (!existing.expiredAt(now)) {
                    reserved.set(existing.matches(sku, count));
                    return existing;
                }
                release(existing.sku(), existing.count());
            }
            if (!reserve(sku, count)) {
                reserved.set(false);
                return null;
            }
            reserved.set(true);
            return new Reservation(sku, count, expiresAt(now));
        });
        return reserved.get();
    }

    @Override
    public void release(String sku, int count) {
        validateCount(count);
        stocks.computeIfAbsent(Objects.requireNonNull(sku, "sku"), ignored -> new AtomicInteger())
                .addAndGet(count);
    }

    @Override
    public void release(String reservationId, String sku, int count) {
        Objects.requireNonNull(sku, "sku");
        validateCount(count);
        if (reservationId == null || reservationId.isBlank()) {
            release(sku, count);
            return;
        }
        reservations.computeIfPresent(reservationId, (ignored, existing) -> {
            if (existing.matches(sku, count)) {
                release(sku, count);
                return null;
            }
            return existing;
        });
    }

    @Override
    public int remaining(String sku) {
        AtomicInteger stock = stocks.get(sku);
        return stock == null ? 0 : stock.get();
    }

    @Override
    public int reservationCount() {
        return reservations.size();
    }

    @Override
    public int reapExpiredReservations() {
        Instant now = clock.instant();
        AtomicInteger reaped = new AtomicInteger();
        reservations.forEach((reservationId, reservation) -> {
            if (reservation.expiredAt(now) && reservations.remove(reservationId, reservation)) {
                release(reservation.sku(), reservation.count());
                reaped.incrementAndGet();
            }
        });
        return reaped.get();
    }

    private static void validateCount(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    private Instant expiresAt(Instant now) {
        if (reservationTtl.isZero()) {
            return Instant.MAX;
        }
        return now.plus(reservationTtl);
    }

    private record Reservation(String sku, int count, Instant expiresAt) {
        boolean matches(String sku, int count) {
            return this.sku.equals(sku) && this.count == count;
        }

        boolean expiredAt(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }
}
