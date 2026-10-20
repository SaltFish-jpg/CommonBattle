package com.commonbattle.persistence;

import com.commonbattle.game.shop.SerializedShopStockRepository;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAtomicBytesStoreTest {
    @Test
    void supportsCrossInstanceOptimisticCasDeleteAndPrefixScan() {
        FakeDatabase database = new FakeDatabase();
        JdbcAtomicBytesStore first = new JdbcAtomicBytesStore(database.dataSource());
        JdbcAtomicBytesStore second = new JdbcAtomicBytesStore(database.dataSource());
        first.initializeSchema();

        assertTrue(first.putIfAbsent("player:1", bytes(1)));
        assertFalse(second.putIfAbsent("player:1", bytes(9)));
        byte[] stale = first.load("player:1").orElseThrow();
        second.put("player:1", bytes(2));

        assertFalse(first.compareAndSet("player:1", stale, bytes(3)));
        assertTrue(first.compareAndSet("player:1", bytes(2), bytes(3)));
        first.put("other:1", bytes(7));

        List<AtomicBytesStore.Entry> players = first.scanPrefix("player:");
        assertEquals(1, players.size());
        assertEquals("player:1", players.getFirst().key());
        assertArrayEquals(bytes(3), players.getFirst().bytes());
        assertFalse(second.compareAndDelete("player:1", bytes(2)));
        assertTrue(second.compareAndDelete("player:1", bytes(3)));
        assertTrue(first.load("player:1").isEmpty());
    }

    @Test
    void initializesExpectedSchemaAndRejectsUnsafeTableName() {
        FakeDatabase database = new FakeDatabase();
        JdbcAtomicBytesStore store = new JdbcAtomicBytesStore(database.dataSource(), "player_atomic_state");

        store.initializeSchema();

        assertEquals("CREATE TABLE IF NOT EXISTS player_atomic_state "
                + "(record_key VARCHAR(512) PRIMARY KEY, payload BLOB NOT NULL, record_version BIGINT NOT NULL)",
                database.statements.getFirst());
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcAtomicBytesStore(database.dataSource(), "state;drop"));
    }

    @Test
    void shopStockAndReservationsSurviveRepositoryReconstruction() {
        FakeDatabase database = new FakeDatabase();
        JdbcAtomicBytesStore firstStore = new JdbcAtomicBytesStore(database.dataSource(), "shop_stock");
        firstStore.initializeSchema();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        SerializedShopStockRepository first = new SerializedShopStockRepository(
                firstStore, clock, Duration.ofMinutes(1)
        );
        first.set("daily-pack", 5);
        assertTrue(first.reserve("order-1", "daily-pack", 2));

        SerializedShopStockRepository recovered = new SerializedShopStockRepository(
                new JdbcAtomicBytesStore(database.dataSource(), "shop_stock"),
                clock,
                Duration.ofMinutes(1)
        );

        assertEquals(3, recovered.remaining("daily-pack"));
        assertEquals(1, recovered.reservationCount());
        assertTrue(recovered.reserve("order-1", "daily-pack", 2));
        assertEquals(3, recovered.remaining("daily-pack"));
        recovered.release("order-1", "daily-pack", 2);
        assertEquals(5, recovered.remaining("daily-pack"));
    }

    private static byte[] bytes(int value) {
        return new byte[]{(byte) value};
    }

    private static final class FakeDatabase {
        private final Map<String, Row> rows = new LinkedHashMap<>();
        private final List<String> statements = new ArrayList<>();

        private DataSource dataSource() {
            return proxy(DataSource.class, (ignored, method, args) -> method.getName().equals("getConnection")
                    ? connection() : defaultValue(method.getReturnType()));
        }

        private Connection connection() {
            class State {
                boolean autoCommit = true;
            }
            State state = new State();
            return proxy(Connection.class, (ignored, method, args) -> switch (method.getName()) {
                case "createStatement" -> statement();
                case "prepareStatement" -> preparedStatement((String) args[0]);
                case "getAutoCommit" -> state.autoCommit;
                case "setAutoCommit" -> {
                    state.autoCommit = (Boolean) args[0];
                    yield null;
                }
                case "commit", "rollback", "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private Statement statement() {
            return proxy(Statement.class, (ignored, method, args) -> {
                if (method.getName().equals("execute")) {
                    statements.add((String) args[0]);
                    return false;
                }
                return defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement preparedStatement(String sql) {
            Map<Integer, Object> parameters = new LinkedHashMap<>();
            return proxy(PreparedStatement.class, (ignored, method, args) -> switch (method.getName()) {
                case "setString", "setBytes", "setLong" -> {
                    parameters.put((Integer) args[0], copy(args[1]));
                    yield null;
                }
                case "executeUpdate" -> update(sql, parameters);
                case "executeQuery" -> query(sql, parameters);
                default -> defaultValue(method.getReturnType());
            });
        }

        private synchronized int update(String sql, Map<Integer, Object> parameters) throws Exception {
            if (sql.startsWith("INSERT INTO")) {
                String key = (String) parameters.get(1);
                if (rows.containsKey(key)) {
                    throw new SQLIntegrityConstraintViolationException("duplicate", "23505");
                }
                rows.put(key, new Row(bytes(parameters.get(2)), (Long) parameters.get(3)));
                return 1;
            }
            if (sql.startsWith("UPDATE") && sql.contains("AND record_version = ?")) {
                String key = (String) parameters.get(2);
                Row row = rows.get(key);
                long expectedVersion = (Long) parameters.get(3);
                if (row == null || row.version != expectedVersion) {
                    return 0;
                }
                rows.put(key, new Row(bytes(parameters.get(1)), row.version + 1));
                return 1;
            }
            if (sql.startsWith("UPDATE")) {
                String key = (String) parameters.get(2);
                Row row = rows.get(key);
                if (row == null) {
                    return 0;
                }
                rows.put(key, new Row(bytes(parameters.get(1)), row.version + 1));
                return 1;
            }
            if (sql.startsWith("DELETE")) {
                String key = (String) parameters.get(1);
                Row row = rows.get(key);
                long expectedVersion = (Long) parameters.get(2);
                if (row == null || row.version != expectedVersion) {
                    return 0;
                }
                rows.remove(key);
                return 1;
            }
            throw new IllegalStateException("Unexpected SQL update: " + sql);
        }

        private synchronized ResultSet query(String sql, Map<Integer, Object> parameters) {
            List<Object[]> result = new ArrayList<>();
            if (sql.contains("LIKE ?")) {
                String pattern = (String) parameters.get(1);
                String prefix = pattern.substring(0, pattern.length() - 1);
                rows.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(prefix))
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> result.add(new Object[]{entry.getKey(), bytes(entry.getValue().payload)}));
            } else {
                Row row = rows.get((String) parameters.get(1));
                if (row != null) {
                    result.add(sql.contains("record_version")
                            ? new Object[]{bytes(row.payload), row.version}
                            : new Object[]{bytes(row.payload)});
                }
            }
            return resultSet(result);
        }

        private static ResultSet resultSet(List<Object[]> rows) {
            class Cursor {
                int index = -1;
            }
            Cursor cursor = new Cursor();
            return proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
                case "next" -> ++cursor.index < rows.size();
                case "getBytes" -> bytes(rows.get(cursor.index)[(Integer) args[0] - 1]);
                case "getString" -> rows.get(cursor.index)[(Integer) args[0] - 1];
                case "getLong" -> rows.get(cursor.index)[(Integer) args[0] - 1];
                default -> defaultValue(method.getReturnType());
            });
        }

        private static Object copy(Object value) {
            return value instanceof byte[] payload ? bytes(payload) : value;
        }

        private static byte[] bytes(Object value) {
            byte[] payload = (byte[]) value;
            return Arrays.copyOf(payload, payload.length);
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            return null;
        }

        private record Row(byte[] payload, long version) {
        }
    }
}
