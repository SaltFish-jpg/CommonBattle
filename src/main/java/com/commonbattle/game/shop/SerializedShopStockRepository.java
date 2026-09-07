package com.commonbattle.game.shop;

import com.commonbattle.persistence.AtomicBytesStore;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 基于原子字节存储的商店全服库存仓储。
 * 库存扣减使用 CAS，reservation 使用唯一键写入；底层可映射到 Redis Lua 或 SQL 条件更新。
 */
public final class SerializedShopStockRepository implements ShopStockReservationRepository {
    private static final String STOCK_PREFIX = "shop:stock:";
    private static final String RESERVATION_PREFIX = "shop:stock:reservation:";

    private final AtomicBytesStore store;
    private final Clock clock;
    private final Duration reservationTtl;

    public SerializedShopStockRepository(AtomicBytesStore store, Clock clock, Duration reservationTtl) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reservationTtl = Objects.requireNonNull(reservationTtl, "reservationTtl");
        if (reservationTtl.isNegative()) {
            throw new IllegalArgumentException("reservationTtl must not be negative");
        }
    }

    public void set(String sku, int count) {
        requireSku(sku);
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        store.put(stockKey(sku), encodeInt(count));
    }

    public void seed(ShopCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        catalog.definitions().stream()
                .filter(ShopItemDefinition::limitedStock)
                .forEach(definition -> set(definition.sku(), definition.globalStock()));
    }

    @Override
    public boolean reserve(String sku, int count) {
        requireSku(sku);
        validateCount(count);
        String key = stockKey(sku);
        while (true) {
            var current = store.load(key);
            if (current.isEmpty()) {
                return false;
            }
            int remaining = decodeInt(current.orElseThrow());
            if (remaining < count) {
                return false;
            }
            if (store.compareAndSet(key, current.orElseThrow(), encodeInt(remaining - count))) {
                return true;
            }
        }
    }

    @Override
    public boolean reserve(String reservationId, String sku, int count) {
        requireSku(sku);
        validateCount(count);
        if (reservationId == null || reservationId.isBlank()) {
            return reserve(sku, count);
        }
        String key = reservationKey(reservationId);
        while (true) {
            var existing = store.load(key);
            if (existing.isPresent()) {
                Reservation reservation = decodeReservation(existing.orElseThrow());
                if (!reservation.expiredAt(clock.instant())) {
                    return reservation.matches(sku, count);
                }
                if (store.compareAndDelete(key, existing.orElseThrow())) {
                    release(reservation.sku(), reservation.count());
                }
                continue;
            }
            if (!reserve(sku, count)) {
                return false;
            }
            Reservation reservation = new Reservation(sku, count, expiresAt(clock.instant()));
            if (store.putIfAbsent(key, encodeReservation(reservation))) {
                return true;
            }
            release(sku, count);
        }
    }

    @Override
    public void release(String sku, int count) {
        requireSku(sku);
        validateCount(count);
        String key = stockKey(sku);
        while (true) {
            var current = store.load(key);
            if (current.isEmpty()) {
                if (store.putIfAbsent(key, encodeInt(count))) {
                    return;
                }
                continue;
            }
            int remaining = decodeInt(current.orElseThrow());
            if (store.compareAndSet(key, current.orElseThrow(), encodeInt(Math.addExact(remaining, count)))) {
                return;
            }
        }
    }

    @Override
    public void release(String reservationId, String sku, int count) {
        requireSku(sku);
        validateCount(count);
        if (reservationId == null || reservationId.isBlank()) {
            release(sku, count);
            return;
        }
        String key = reservationKey(reservationId);
        while (true) {
            var existing = store.load(key);
            if (existing.isEmpty()) {
                return;
            }
            Reservation reservation = decodeReservation(existing.orElseThrow());
            if (!reservation.matches(sku, count)) {
                return;
            }
            if (store.compareAndDelete(key, existing.orElseThrow())) {
                release(sku, count);
                return;
            }
        }
    }

    @Override
    public int remaining(String sku) {
        requireSku(sku);
        return store.load(stockKey(sku)).map(SerializedShopStockRepository::decodeInt).orElse(0);
    }

    @Override
    public int reservationCount() {
        return store.scanPrefix(RESERVATION_PREFIX).size();
    }

    @Override
    public int reapExpiredReservations() {
        Instant now = clock.instant();
        int reaped = 0;
        for (AtomicBytesStore.Entry entry : store.scanPrefix(RESERVATION_PREFIX)) {
            Reservation reservation = decodeReservation(entry.bytes());
            if (reservation.expiredAt(now) && store.compareAndDelete(entry.key(), entry.bytes())) {
                release(reservation.sku(), reservation.count());
                reaped++;
            }
        }
        return reaped;
    }

    private Instant expiresAt(Instant now) {
        if (reservationTtl.isZero()) {
            return Instant.ofEpochMilli(Long.MAX_VALUE);
        }
        return now.plus(reservationTtl);
    }

    private static String stockKey(String sku) {
        return STOCK_PREFIX + requireSku(sku);
    }

    private static String reservationKey(String reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        if (reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId must not be blank");
        }
        return RESERVATION_PREFIX + reservationId;
    }

    private static String requireSku(String sku) {
        Objects.requireNonNull(sku, "sku");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        return sku;
    }

    private static void validateCount(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    private static byte[] encodeInt(int value) {
        int size = CodedOutputStream.computeInt32Size(1, value);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt32(1, value);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode shop stock", e);
        }
    }

    private static int decodeInt(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        int value = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                if (WireFormat.getTagFieldNumber(tag) == 1) {
                    value = input.readInt32();
                } else {
                    input.skipField(tag);
                }
            }
            return value;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode shop stock", e);
        }
    }

    private static byte[] encodeReservation(Reservation reservation) {
        int size = CodedOutputStream.computeStringSize(1, reservation.sku())
                + CodedOutputStream.computeInt32Size(2, reservation.count())
                + CodedOutputStream.computeInt64Size(3, reservation.expiresAt().toEpochMilli());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, reservation.sku());
            output.writeInt32(2, reservation.count());
            output.writeInt64(3, reservation.expiresAt().toEpochMilli());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode shop stock reservation", e);
        }
    }

    private static Reservation decodeReservation(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String sku = "";
        int count = 0;
        Instant expiresAt = Instant.EPOCH;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> sku = input.readString();
                    case 2 -> count = input.readInt32();
                    case 3 -> expiresAt = Instant.ofEpochMilli(input.readInt64());
                    default -> input.skipField(tag);
                }
            }
            return new Reservation(sku, count, expiresAt);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode shop stock reservation", e);
        }
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
