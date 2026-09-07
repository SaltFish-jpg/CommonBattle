package com.commonbattle.cluster.boot;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 基于 DriverManager 的轻量 DataSource。
 * 启动配置只负责提供 JDBC URL 和凭据，具体数据库驱动由部署包自行放入 classpath。
 */
final class DriverManagerDataSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;
    private PrintWriter logWriter;
    private int loginTimeoutSeconds;

    DriverManagerDataSource(String driverClassName, String url, String user, String password) {
        if (driverClassName != null && !driverClassName.isBlank()) {
            loadDriver(driverClassName);
        }
        this.url = requireUrl(url);
        this.user = Objects.requireNonNullElse(user, "");
        this.password = Objects.requireNonNullElse(password, "");
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (user.isBlank()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeoutSeconds = seconds;
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeoutSeconds;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("DriverManagerDataSource has no parent logger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    private static void loadDriver(String driverClassName) {
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing JDBC driver class " + driverClassName, e);
        }
    }

    private static String requireUrl(String url) {
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) {
            throw new IllegalArgumentException("JDBC url must not be blank");
        }
        return url;
    }
}
