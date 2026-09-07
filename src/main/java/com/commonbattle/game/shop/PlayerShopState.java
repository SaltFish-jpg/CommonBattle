package com.commonbattle.game.shop;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家商店购买状态。
 * 该对象应只在玩家 Agent 邮箱内修改；跨线程请求必须先投递回玩家 Agent。
 */
public final class PlayerShopState {
    private final Map<String, Integer> lifetimePurchases = new HashMap<>();
    private final Map<DailyKey, Integer> dailyPurchases = new HashMap<>();

    public int lifetimePurchased(String sku) {
        return lifetimePurchases.getOrDefault(sku, 0);
    }

    public int dailyPurchased(String sku, LocalDate day) {
        return dailyPurchases.getOrDefault(new DailyKey(sku, day), 0);
    }

    public PlayerShopSnapshot snapshot() {
        Map<String, Integer> daily = new HashMap<>();
        dailyPurchases.forEach((key, count) -> daily.put(key.snapshotKey(), count));
        return new PlayerShopSnapshot(lifetimePurchases, daily);
    }

    public void restore(PlayerShopSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        lifetimePurchases.clear();
        dailyPurchases.clear();
        snapshot.lifetimePurchases().forEach((sku, count) -> {
            if (count > 0) {
                lifetimePurchases.put(sku, count);
            }
        });
        snapshot.dailyPurchases().forEach((key, count) -> {
            if (count > 0) {
                dailyPurchases.put(DailyKey.restore(key), count);
            }
        });
    }

    void record(String sku, int count, LocalDate day) {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(day, "day");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        lifetimePurchases.merge(sku, count, Integer::sum);
        dailyPurchases.merge(new DailyKey(sku, day), count, Integer::sum);
    }

    private record DailyKey(String sku, LocalDate day) {
        private DailyKey {
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(day, "day");
        }

        private String snapshotKey() {
            return sku + "@" + day;
        }

        private static DailyKey restore(String key) {
            int split = key.lastIndexOf('@');
            if (split <= 0 || split == key.length() - 1) {
                throw new IllegalArgumentException("invalid shop daily key");
            }
            return new DailyKey(key.substring(0, split), LocalDate.parse(key.substring(split + 1)));
        }
    }
}
