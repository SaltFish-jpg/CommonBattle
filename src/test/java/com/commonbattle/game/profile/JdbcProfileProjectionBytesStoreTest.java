package com.commonbattle.game.profile;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcProfileProjectionBytesStoreTest {
    @Test
    void initializesSchemaAndUpsertsProjection() {
        FakeDatabase database = new FakeDatabase();
        JdbcProfileProjectionBytesStore store = new JdbcProfileProjectionBytesStore(database.dataSource());

        store.initializeSchema();
        store.save("profile-1", new byte[]{1});
        store.save("profile-1", new byte[]{2, 3});

        assertEquals("CREATE TABLE IF NOT EXISTS profile_projection "
                + "(projection_key VARCHAR(64) PRIMARY KEY, payload BLOB NOT NULL)", database.statements.getFirst());
        assertEquals(1, store.loadAll().size());
        assertArrayEquals(new byte[]{2, 3}, store.loadAll().getFirst());
    }

    @Test
    void rejectsUnsafeTableName() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcProfileProjectionBytesStore(new FakeDatabase().dataSource(), "profile;drop"));
    }

    private static final class FakeDatabase {
        private final Map<String, byte[]> rows = new LinkedHashMap<>();
        private final List<String> statements = new ArrayList<>();

        private DataSource dataSource() {
            return proxy(DataSource.class, (ignored, method, args) -> method.getName().equals("getConnection")
                    ? connection() : defaultValue(method.getReturnType()));
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
                case "executeQuery" -> resultSet(rows.values().stream().map(FakeDatabase::row).toList());
                default -> defaultValue(method.getReturnType());
            });
        }

        private int update(String sql, Map<Integer, Object> parameters) {
            if (sql.startsWith("UPDATE profile_projection")) {
                String key = (String) parameters.get(2);
                if (!rows.containsKey(key)) {
                    return 0;
                }
                rows.put(key, bytes(parameters.get(1)));
                return 1;
            }
            if (sql.startsWith("INSERT INTO profile_projection")) {
                rows.put((String) parameters.get(1), bytes(parameters.get(2)));
                return 1;
            }
            throw new IllegalStateException("Unexpected SQL update: " + sql);
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

        private static Object[] row(byte[] value) {
            return new Object[]{bytes(value)};
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
    }
}
