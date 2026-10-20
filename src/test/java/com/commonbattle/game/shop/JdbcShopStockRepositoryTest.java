package com.commonbattle.game.shop;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcShopStockRepositoryTest {
    @Test
    void reservationAndStockCommitAtomicallyAndSurviveReconstruction() {
        FakeDatabase database = new FakeDatabase();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        JdbcShopStockRepository first = repository(database, clock);
        first.initializeSchema();
        first.set("limited_pack", 5);

        assertTrue(first.reserve("order-1", "limited_pack", 2));
        assertTrue(first.reserve("order-1", "limited_pack", 2));
        assertFalse(first.reserve("order-1", "limited_pack", 1));
        assertEquals(3, first.remaining("limited_pack"));

        JdbcShopStockRepository recovered = repository(database, clock);
        assertEquals(1, recovered.reservationCount());
        assertTrue(recovered.reserve("order-1", "limited_pack", 2));
        recovered.release("order-1", "limited_pack", 2);
        recovered.release("order-1", "limited_pack", 2);

        assertEquals(5, recovered.remaining("limited_pack"));
        assertEquals(0, recovered.reservationCount());
        assertEquals(2, database.schemaStatements.size());
    }

    @Test
    void reservationInsertFailureRollsBackEarlierStockDecrement() {
        FakeDatabase database = new FakeDatabase();
        JdbcShopStockRepository repository = repository(
                database,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC)
        );
        repository.set("limited_pack", 5);
        database.failNextReservationInsert = true;

        assertThrows(IllegalStateException.class,
                () -> repository.reserve("order-1", "limited_pack", 2));

        assertEquals(5, repository.remaining("limited_pack"));
        assertEquals(0, repository.reservationCount());
        assertTrue(repository.reserve("order-1", "limited_pack", 2));
        assertEquals(3, repository.remaining("limited_pack"));
    }

    @Test
    void expiredReservationIsReleasedExactlyOnce() {
        FakeDatabase database = new FakeDatabase();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        JdbcShopStockRepository repository = repository(database, clock);
        repository.set("limited_pack", 2);
        assertTrue(repository.reserve("order-1", "limited_pack", 1));
        clock.advance(Duration.ofMinutes(2));

        assertEquals(1, repository.reapExpiredReservations());
        assertEquals(0, repository.reapExpiredReservations());
        assertEquals(2, repository.remaining("limited_pack"));
        assertEquals(0, repository.reservationCount());
    }

    @Test
    void rejectsUnsafeTableNames() {
        FakeDatabase database = new FakeDatabase();
        assertThrows(IllegalArgumentException.class, () -> new JdbcShopStockRepository(
                database.dataSource(),
                "stock;drop",
                "reservations",
                Clock.systemUTC(),
                Duration.ofMinutes(1)
        ));
    }

    private static JdbcShopStockRepository repository(FakeDatabase database, Clock clock) {
        return new JdbcShopStockRepository(
                database.dataSource(),
                "shop_stock",
                "shop_stock_reservation",
                clock,
                Duration.ofMinutes(1)
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FakeDatabase {
        private Map<String, Integer> stocks = new LinkedHashMap<>();
        private Map<String, ReservationRow> reservations = new LinkedHashMap<>();
        private final List<String> schemaStatements = new ArrayList<>();
        private boolean failNextReservationInsert;

        private DataSource dataSource() {
            return proxy(DataSource.class, (ignored, method, args) -> method.getName().equals("getConnection")
                    ? connection() : defaultValue(method.getReturnType()));
        }

        private Connection connection() {
            class State {
                boolean autoCommit = true;
                Map<String, Integer> transactionStocks;
                Map<String, ReservationRow> transactionReservations;
            }
            State state = new State();
            return proxy(Connection.class, (ignored, method, args) -> switch (method.getName()) {
                case "createStatement" -> statement();
                case "prepareStatement" -> preparedStatement((String) args[0], state);
                case "getAutoCommit" -> state.autoCommit;
                case "setAutoCommit" -> {
                    boolean next = (Boolean) args[0];
                    if (!next && state.autoCommit) {
                        synchronized (this) {
                            state.transactionStocks = new LinkedHashMap<>(stocks);
                            state.transactionReservations = new LinkedHashMap<>(reservations);
                        }
                    }
                    state.autoCommit = next;
                    yield null;
                }
                case "commit" -> {
                    synchronized (this) {
                        if (state.transactionStocks != null) {
                            stocks = new LinkedHashMap<>(state.transactionStocks);
                            reservations = new LinkedHashMap<>(state.transactionReservations);
                        }
                    }
                    yield null;
                }
                case "rollback" -> {
                    synchronized (this) {
                        state.transactionStocks = new LinkedHashMap<>(stocks);
                        state.transactionReservations = new LinkedHashMap<>(reservations);
                    }
                    yield null;
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private Statement statement() {
            return proxy(Statement.class, (ignored, method, args) -> {
                if (method.getName().equals("execute")) {
                    schemaStatements.add((String) args[0]);
                    return false;
                }
                return defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement preparedStatement(String sql, Object state) {
            Map<Integer, Object> parameters = new LinkedHashMap<>();
            return proxy(PreparedStatement.class, (ignored, method, args) -> switch (method.getName()) {
                case "setString", "setInt", "setLong" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "executeUpdate" -> update(sql, parameters, state);
                case "executeQuery" -> query(sql, parameters, state);
                default -> defaultValue(method.getReturnType());
            });
        }

        @SuppressWarnings("unchecked")
        private synchronized int update(String sql, Map<Integer, Object> parameters, Object rawState) throws SQLException {
            StateAccess state = new StateAccess(rawState);
            Map<String, Integer> currentStocks = state.stocks();
            Map<String, ReservationRow> currentReservations = state.reservations();
            if (sql.startsWith("UPDATE shop_stock SET remaining = remaining -")) {
                int count = (Integer) parameters.get(1);
                String sku = (String) parameters.get(2);
                int remaining = currentStocks.getOrDefault(sku, 0);
                if (remaining < (Integer) parameters.get(3) || !currentStocks.containsKey(sku)) {
                    return 0;
                }
                currentStocks.put(sku, remaining - count);
                return 1;
            }
            if (sql.startsWith("UPDATE shop_stock SET remaining = remaining +")) {
                int count = (Integer) parameters.get(1);
                String sku = (String) parameters.get(2);
                if (!currentStocks.containsKey(sku)) {
                    return 0;
                }
                currentStocks.put(sku, currentStocks.get(sku) + count);
                return 1;
            }
            if (sql.startsWith("UPDATE shop_stock SET remaining = ?")) {
                String sku = (String) parameters.get(2);
                if (!currentStocks.containsKey(sku)) {
                    return 0;
                }
                currentStocks.put(sku, (Integer) parameters.get(1));
                return 1;
            }
            if (sql.startsWith("INSERT INTO shop_stock (")) {
                String sku = (String) parameters.get(1);
                if (currentStocks.containsKey(sku)) {
                    throw new SQLIntegrityConstraintViolationException("duplicate", "23505");
                }
                currentStocks.put(sku, (Integer) parameters.get(2));
                return 1;
            }
            if (sql.startsWith("INSERT INTO shop_stock_reservation")) {
                if (failNextReservationInsert) {
                    failNextReservationInsert = false;
                    throw new SQLException("injected reservation insert failure", "XX001");
                }
                String id = (String) parameters.get(1);
                if (currentReservations.containsKey(id)) {
                    throw new SQLIntegrityConstraintViolationException("duplicate", "23505");
                }
                currentReservations.put(id, new ReservationRow(
                        (String) parameters.get(2),
                        (Integer) parameters.get(3),
                        (Long) parameters.get(4)
                ));
                return 1;
            }
            if (sql.startsWith("DELETE FROM shop_stock_reservation")) {
                return currentReservations.remove((String) parameters.get(1)) == null ? 0 : 1;
            }
            throw new IllegalStateException("Unexpected SQL update: " + sql);
        }

        private synchronized ResultSet query(String sql, Map<Integer, Object> parameters, Object rawState) {
            StateAccess state = new StateAccess(rawState);
            List<Object[]> rows = new ArrayList<>();
            if (sql.startsWith("SELECT remaining")) {
                Integer remaining = state.stocks().get((String) parameters.get(1));
                if (remaining != null) {
                    rows.add(new Object[]{remaining});
                }
            } else if (sql.startsWith("SELECT COUNT(*)")) {
                rows.add(new Object[]{state.reservations().size()});
            } else if (sql.startsWith("SELECT reservation_id")) {
                long now = (Long) parameters.get(1);
                state.reservations().forEach((id, reservation) -> {
                    if (reservation.expiresAtMillis() <= now) {
                        rows.add(new Object[]{id});
                    }
                });
            } else if (sql.startsWith("SELECT sku, reserved_count")) {
                ReservationRow reservation = state.reservations().get((String) parameters.get(1));
                if (reservation != null) {
                    rows.add(new Object[]{reservation.sku(), reservation.count(), reservation.expiresAtMillis()});
                }
            } else {
                throw new IllegalStateException("Unexpected SQL query: " + sql);
            }
            return resultSet(rows);
        }

        private final class StateAccess {
            private final Object state;

            private StateAccess(Object state) {
                this.state = state;
            }

            @SuppressWarnings("unchecked")
            private Map<String, Integer> stocks() {
                return (Map<String, Integer>) field("transactionStocks", stocks);
            }

            @SuppressWarnings("unchecked")
            private Map<String, ReservationRow> reservations() {
                return (Map<String, ReservationRow>) field("transactionReservations", reservations);
            }

            private Object field(String name, Object fallback) {
                try {
                    var field = state.getClass().getDeclaredField(name);
                    field.setAccessible(true);
                    Object value = field.get(state);
                    return value == null ? fallback : value;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        private static ResultSet resultSet(List<Object[]> rows) {
            class Cursor {
                int index = -1;
            }
            Cursor cursor = new Cursor();
            return proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
                case "next" -> ++cursor.index < rows.size();
                case "getString" -> rows.get(cursor.index)[(Integer) args[0] - 1];
                case "getInt" -> rows.get(cursor.index)[(Integer) args[0] - 1];
                case "getLong" -> rows.get(cursor.index)[(Integer) args[0] - 1];
                default -> defaultValue(method.getReturnType());
            });
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

        private record ReservationRow(String sku, int count, long expiresAtMillis) {
        }
    }
}
