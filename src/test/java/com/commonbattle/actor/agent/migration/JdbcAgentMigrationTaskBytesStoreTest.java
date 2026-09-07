package com.commonbattle.actor.agent.migration;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAgentMigrationTaskBytesStoreTest {
    @Test
    void initializeSchemaCreatesExpectedTable() {
        FakeMigrationTaskDatabase database = new FakeMigrationTaskDatabase();
        JdbcAgentMigrationTaskBytesStore store = new JdbcAgentMigrationTaskBytesStore(database.dataSource());

        store.initializeSchema();

        assertEquals(
                "CREATE TABLE IF NOT EXISTS agent_migration_tasks "
                        + "(task_id VARCHAR(255) PRIMARY KEY, version BIGINT NOT NULL, payload BLOB NOT NULL)",
                database.executedStatements.getFirst()
        );
    }

    @Test
    void saveLoadAndLoadAllUseJdbcRows() {
        JdbcAgentMigrationTaskBytesStore store = new JdbcAgentMigrationTaskBytesStore(
                new FakeMigrationTaskDatabase().dataSource()
        );
        store.save("migration-1", new byte[]{1, 2});
        store.save("migration-2", new byte[]{3});
        store.save("migration-1", new byte[]{4});

        byte[] loaded = store.load("migration-1").orElseThrow();
        loaded[0] = 9;

        assertArrayEquals(new byte[]{4}, store.load("migration-1").orElseThrow());
        assertEquals(2, store.loadAll().size());
    }

    @Test
    void compareAndSetRequiresExpectedPayloadAndVersion() {
        FakeMigrationTaskDatabase database = new FakeMigrationTaskDatabase();
        JdbcAgentMigrationTaskBytesStore store = new JdbcAgentMigrationTaskBytesStore(database.dataSource());
        store.save("migration-1", new byte[]{1});

        assertFalse(store.compareAndSet("migration-1", new byte[]{9}, new byte[]{2}));
        assertArrayEquals(new byte[]{1}, store.load("migration-1").orElseThrow());
        assertTrue(store.compareAndSet("migration-1", new byte[]{1}, new byte[]{2}));
        assertArrayEquals(new byte[]{2}, store.load("migration-1").orElseThrow());

        database.bumpVersionBeforeNextCasUpdate = true;
        assertFalse(store.compareAndSet("migration-1", new byte[]{2}, new byte[]{3}));
        assertArrayEquals(new byte[]{2}, store.load("migration-1").orElseThrow());
    }

    @Test
    void compareAndDeleteRequiresExpectedPayloadAndVersion() {
        FakeMigrationTaskDatabase database = new FakeMigrationTaskDatabase();
        JdbcAgentMigrationTaskBytesStore store = new JdbcAgentMigrationTaskBytesStore(database.dataSource());
        store.save("migration-1", new byte[]{1});

        assertFalse(store.compareAndDelete("migration-1", new byte[]{9}));
        assertTrue(store.load("migration-1").isPresent());
        assertTrue(store.compareAndDelete("migration-1", new byte[]{1}));
        assertTrue(store.load("migration-1").isEmpty());
    }

    @Test
    void rejectsUnsafeTableName() {
        FakeMigrationTaskDatabase database = new FakeMigrationTaskDatabase();

        assertThrows(IllegalArgumentException.class,
                () -> new JdbcAgentMigrationTaskBytesStore(database.dataSource(), "task;drop"));
    }

    private static final class FakeMigrationTaskDatabase {
        private final Map<String, Row> rows = new LinkedHashMap<>();
        private final List<String> executedStatements = new ArrayList<>();
        private boolean bumpVersionBeforeNextCasUpdate;

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
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("createStatement")) {
                    return statement();
                }
                if (method.getName().equals("prepareStatement")) {
                    return preparedStatement((String) args[0]);
                }
                return defaultValue(method.getReturnType());
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
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setString", "setLong", "setBytes" -> {
                        parameters.put((Integer) args[0], copyValue(args[1]));
                        return null;
                    }
                    case "executeUpdate" -> {
                        return executeUpdate(sql, parameters);
                    }
                    case "executeQuery" -> {
                        return resultSet(executeQuery(sql, parameters));
                    }
                    default -> {
                        return defaultValue(method.getReturnType());
                    }
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    handler
            );
        }

        private int executeUpdate(String sql, Map<Integer, Object> parameters) throws Exception {
            if (sql.startsWith("UPDATE agent_migration_tasks SET payload = ?, version = version + 1")) {
                String taskId = (String) parameters.get(2);
                Row row = rows.get(taskId);
                if (row == null) {
                    return 0;
                }
                row.version++;
                row.payload = copyBytes(parameters.get(1));
                return 1;
            }
            if (sql.startsWith("INSERT INTO agent_migration_tasks")) {
                String taskId = (String) parameters.get(1);
                if (rows.containsKey(taskId)) {
                    throw new SQLIntegrityConstraintViolationException("duplicate " + taskId);
                }
                rows.put(taskId, new Row((Long) parameters.get(2), copyBytes(parameters.get(3))));
                return 1;
            }
            if (sql.startsWith("UPDATE agent_migration_tasks SET payload = ?, version = ?")) {
                String taskId = (String) parameters.get(3);
                Row row = rows.get(taskId);
                if (row == null) {
                    return 0;
                }
                if (bumpVersionBeforeNextCasUpdate) {
                    row.version++;
                    bumpVersionBeforeNextCasUpdate = false;
                }
                long expectedVersion = (Long) parameters.get(4);
                if (row.version != expectedVersion) {
                    return 0;
                }
                row.payload = copyBytes(parameters.get(1));
                row.version = (Long) parameters.get(2);
                return 1;
            }
            if (sql.startsWith("DELETE FROM agent_migration_tasks")) {
                String taskId = (String) parameters.get(1);
                Row row = rows.get(taskId);
                if (row == null || row.version != (Long) parameters.get(2)) {
                    return 0;
                }
                rows.remove(taskId);
                return 1;
            }
            throw new IllegalStateException("Unexpected SQL update: " + sql);
        }

        private List<Object[]> executeQuery(String sql, Map<Integer, Object> parameters) {
            if (sql.equals("SELECT payload FROM agent_migration_tasks WHERE task_id = ?")) {
                Row row = rows.get((String) parameters.get(1));
                if (row == null) {
                    return List.of();
                }
                List<Object[]> result = new ArrayList<>();
                result.add(new Object[]{Arrays.copyOf(row.payload, row.payload.length)});
                return result;
            }
            if (sql.equals("SELECT version, payload FROM agent_migration_tasks WHERE task_id = ?")) {
                Row row = rows.get((String) parameters.get(1));
                if (row == null) {
                    return List.of();
                }
                List<Object[]> result = new ArrayList<>();
                result.add(new Object[]{row.version, Arrays.copyOf(row.payload, row.payload.length)});
                return result;
            }
            if (sql.equals("SELECT payload FROM agent_migration_tasks")) {
                return rows.values().stream()
                        .map(row -> new Object[]{Arrays.copyOf(row.payload, row.payload.length)})
                        .toList();
            }
            throw new IllegalStateException("Unexpected SQL query: " + sql);
        }

        private static ResultSet resultSet(List<Object[]> rows) {
            class Cursor {
                int index = -1;
            }
            Cursor cursor = new Cursor();
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next" -> {
                        cursor.index++;
                        return cursor.index < rows.size();
                    }
                    case "getBytes" -> {
                        byte[] bytes = (byte[]) rows.get(cursor.index)[(Integer) args[0] - 1];
                        return Arrays.copyOf(bytes, bytes.length);
                    }
                    case "getLong" -> {
                        return rows.get(cursor.index)[(Integer) args[0] - 1];
                    }
                    default -> {
                        return defaultValue(method.getReturnType());
                    }
                }
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

        private static final class Row {
            private long version;
            private byte[] payload;

            private Row(long version, byte[] payload) {
                this.version = version;
                this.payload = payload;
            }
        }
    }
}
