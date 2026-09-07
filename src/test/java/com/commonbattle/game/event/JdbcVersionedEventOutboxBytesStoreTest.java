package com.commonbattle.game.event;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcVersionedEventOutboxBytesStoreTest {
    @Test
    void initializeSchemaCreatesOutboxAndSequenceTables() {
        FakeEventOutboxDatabase database = new FakeEventOutboxDatabase();
        JdbcVersionedEventOutboxBytesStore store = new JdbcVersionedEventOutboxBytesStore(database.dataSource());

        store.initializeSchema();

        assertEquals(
                "CREATE TABLE IF NOT EXISTS versioned_event_outbox "
                        + "(outbox_id BIGINT PRIMARY KEY, payload BLOB NOT NULL)",
                database.executedStatements.get(0)
        );
        assertEquals(
                "CREATE TABLE IF NOT EXISTS versioned_event_outbox_sequence "
                        + "(sequence_name VARCHAR(64) PRIMARY KEY, next_value BIGINT NOT NULL)",
                database.executedStatements.get(1)
        );
    }

    @Test
    void allocatesMonotonicIdsAndStoresPayloads() {
        JdbcVersionedEventOutboxBytesStore store = new JdbcVersionedEventOutboxBytesStore(
                new FakeEventOutboxDatabase().dataSource()
        );

        long first = store.nextId();
        long second = store.nextId();
        store.save(first, new byte[]{1, 2});
        store.save(second, new byte[]{3});

        byte[] loaded = store.load(first).orElseThrow();
        loaded[0] = 9;

        assertEquals(1, first);
        assertEquals(2, second);
        assertArrayEquals(new byte[]{1, 2}, store.load(first).orElseThrow());
        assertEquals(2, store.loadAll().size());
        store.delete(first);
        assertTrue(store.load(first).isEmpty());
    }

    @Test
    void rejectsUnsafeTableNames() {
        FakeEventOutboxDatabase database = new FakeEventOutboxDatabase();

        assertThrows(IllegalArgumentException.class,
                () -> new JdbcVersionedEventOutboxBytesStore(database.dataSource(), "outbox;drop", "seq"));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcVersionedEventOutboxBytesStore(database.dataSource(), "outbox", "seq;drop"));
    }

    private static final class FakeEventOutboxDatabase {
        private final Map<Long, byte[]> rows = new LinkedHashMap<>();
        private final List<String> executedStatements = new ArrayList<>();
        private Long nextValue;
        private boolean autoCommit = true;

        DataSource dataSource() {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    return connection();
                }
                return defaultValue(method.getReturnType());
            };
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    handler
            );
        }

        private Connection connection() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "createStatement" -> statement();
                case "prepareStatement" -> preparedStatement((String) args[0]);
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> {
                    autoCommit = (Boolean) args[0];
                    yield null;
                }
                case "commit", "rollback" -> null;
                default -> defaultValue(method.getReturnType());
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    handler
            );
        }

        private Statement statement() {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("execute")) {
                    executedStatements.add((String) args[0]);
                    return false;
                }
                return defaultValue(method.getReturnType());
            };
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[]{Statement.class},
                    handler
            );
        }

        private PreparedStatement preparedStatement(String sql) {
            Map<Integer, Object> parameters = new LinkedHashMap<>();
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setLong", "setBytes" -> {
                    parameters.put((Integer) args[0], copyValue(args[1]));
                    yield null;
                }
                case "executeUpdate" -> executeUpdate(sql, parameters);
                case "executeQuery" -> resultSet(executeQuery(sql, parameters));
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    handler
            );
        }

        private int executeUpdate(String sql, Map<Integer, Object> parameters) throws Exception {
            if (sql.startsWith("UPDATE versioned_event_outbox_sequence SET next_value = ?")) {
                if (nextValue == null || nextValue != (Long) parameters.get(3)) {
                    return 0;
                }
                nextValue = (Long) parameters.get(1);
                return 1;
            }
            if (sql.startsWith("INSERT INTO versioned_event_outbox_sequence")) {
                if (nextValue != null) {
                    throw new SQLIntegrityConstraintViolationException("duplicate sequence");
                }
                nextValue = (Long) parameters.get(2);
                return 1;
            }
            if (sql.startsWith("INSERT INTO versioned_event_outbox")) {
                rows.put((Long) parameters.get(1), copyBytes(parameters.get(2)));
                return 1;
            }
            if (sql.startsWith("DELETE FROM versioned_event_outbox")) {
                rows.remove((Long) parameters.get(1));
                return 1;
            }
            throw new IllegalStateException("Unexpected SQL update: " + sql);
        }

        private List<Object[]> executeQuery(String sql, Map<Integer, Object> parameters) {
            if (sql.equals("SELECT next_value FROM versioned_event_outbox_sequence WHERE sequence_name = ?")) {
                if (nextValue == null) {
                    return List.of();
                }
                List<Object[]> result = new ArrayList<>();
                result.add(new Object[]{nextValue});
                return result;
            }
            if (sql.equals("SELECT payload FROM versioned_event_outbox WHERE outbox_id = ?")) {
                byte[] payload = rows.get((Long) parameters.get(1));
                if (payload == null) {
                    return List.of();
                }
                List<Object[]> result = new ArrayList<>();
                result.add(new Object[]{Arrays.copyOf(payload, payload.length)});
                return result;
            }
            if (sql.equals("SELECT payload FROM versioned_event_outbox ORDER BY outbox_id")) {
                return rows.values().stream()
                        .map(payload -> new Object[]{Arrays.copyOf(payload, payload.length)})
                        .toList();
            }
            throw new IllegalStateException("Unexpected SQL query: " + sql);
        }

        private static ResultSet resultSet(List<Object[]> rows) {
            class Cursor {
                int index = -1;
            }
            Cursor cursor = new Cursor();
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    cursor.index++;
                    yield cursor.index < rows.size();
                }
                case "getBytes" -> {
                    byte[] bytes = (byte[]) rows.get(cursor.index)[(Integer) args[0] - 1];
                    yield Arrays.copyOf(bytes, bytes.length);
                }
                case "getLong" -> rows.get(cursor.index)[(Integer) args[0] - 1];
                default -> defaultValue(method.getReturnType());
            };
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    handler
            );
        }

        private static Object copyValue(Object value) {
            if (value instanceof byte[] bytes) {
                return Arrays.copyOf(bytes, bytes.length);
            }
            return value;
        }

        private static byte[] copyBytes(Object value) {
            byte[] bytes = (byte[]) value;
            return Arrays.copyOf(bytes, bytes.length);
        }

        private static Object defaultValue(Class<?> returnType) {
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            return null;
        }
    }
}
