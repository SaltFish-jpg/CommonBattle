package com.commonbattle.game.event;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

class JdbcVersionedEventInboxBytesStoreTest {
    @Test
    void initializesSchemaAndSupportsAtomicInsertUpdateAndDelete() {
        FakeInboxDatabase database = new FakeInboxDatabase();
        JdbcVersionedEventInboxBytesStore store = new JdbcVersionedEventInboxBytesStore(database.dataSource());

        store.initializeSchema();
        assertTrue(store.insertIfAbsent("event-1", new byte[]{1}));
        assertFalse(store.insertIfAbsent("event-1", new byte[]{9}));
        store.save("event-1", new byte[]{2, 3});

        assertEquals("CREATE TABLE IF NOT EXISTS versioned_event_inbox "
                + "(event_key VARCHAR(64) PRIMARY KEY, payload BLOB NOT NULL)", database.statements.getFirst());
        assertArrayEquals(new byte[]{2, 3}, store.load("event-1").orElseThrow());
        assertEquals(1, store.loadAll().size());

        store.delete("event-1");
        assertTrue(store.load("event-1").isEmpty());
    }

    @Test
    void rejectsUnsafeTableName() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcVersionedEventInboxBytesStore(new FakeInboxDatabase().dataSource(), "inbox;drop"));
    }

    private static final class FakeInboxDatabase {
        private final Map<String, byte[]> rows = new LinkedHashMap<>();
        private final List<String> statements = new ArrayList<>();

        private DataSource dataSource() {
            return proxy(DataSource.class, (ignored, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    return connection();
                }
                return defaultValue(method.getReturnType());
            });
        }

        private Connection connection() {
            return proxy(Connection.class, (ignored, method, args) -> switch (method.getName()) {
                case "createStatement" -> statement();
                case "prepareStatement" -> preparedStatement((String) args[0]);
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
                case "setString", "setBytes" -> {
                    parameters.put((Integer) args[0], copy(args[1]));
                    yield null;
                }
                case "executeUpdate" -> update(sql, parameters);
                case "executeQuery" -> resultSet(query(sql, parameters));
                default -> defaultValue(method.getReturnType());
            });
        }

        private int update(String sql, Map<Integer, Object> parameters) throws SQLException {
            if (sql.startsWith("INSERT INTO versioned_event_inbox")) {
                String key = (String) parameters.get(1);
                if (rows.containsKey(key)) {
                    throw new SQLException("duplicate key");
                }
                rows.put(key, bytes(parameters.get(2)));
                return 1;
            }
            if (sql.startsWith("UPDATE versioned_event_inbox SET payload")) {
                String key = (String) parameters.get(2);
                if (!rows.containsKey(key)) {
                    return 0;
                }
                rows.put(key, bytes(parameters.get(1)));
                return 1;
            }
            if (sql.startsWith("DELETE FROM versioned_event_inbox")) {
                rows.remove(parameters.get(1));
                return 1;
            }
            throw new IllegalStateException("Unexpected SQL update: " + sql);
        }

        private List<Object[]> query(String sql, Map<Integer, Object> parameters) {
            if (sql.equals("SELECT payload FROM versioned_event_inbox WHERE event_key = ?")) {
                byte[] payload = rows.get(parameters.get(1));
                return payload == null ? List.of() : rows(payload);
            }
            if (sql.equals("SELECT payload FROM versioned_event_inbox")) {
                return rows.values().stream().map(FakeInboxDatabase::row).toList();
            }
            throw new IllegalStateException("Unexpected SQL query: " + sql);
        }

        private static ResultSet resultSet(List<Object[]> rows) {
            class Cursor {
                int index = -1;
            }
            Cursor cursor = new Cursor();
            return proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
                case "next" -> ++cursor.index < rows.size();
                case "getBytes" -> bytes(rows.get(cursor.index)[(Integer) args[0] - 1]);
                default -> defaultValue(method.getReturnType());
            });
        }

        private static List<Object[]> rows(byte[] payload) {
            List<Object[]> result = new ArrayList<>();
            result.add(row(payload));
            return result;
        }

        private static Object[] row(byte[] payload) {
            return new Object[]{bytes(payload)};
        }

        private static Object copy(Object value) {
            return value instanceof byte[] bytes ? bytes(bytes) : value;
        }

        private static byte[] bytes(Object value) {
            byte[] bytes = (byte[]) value;
            return Arrays.copyOf(bytes, bytes.length);
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
    }
}
