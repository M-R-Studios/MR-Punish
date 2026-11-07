package com.mrstudios.punish.storage;

import com.mrstudios.punish.config.Settings.Mysql;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/** The database in MySQL or MariaDB through a HikariCP pool (DESIGN.md 9.2). */
public final class MysqlDatabase implements Database {

    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";

    private final Mysql settings;
    private final HikariDataSource pool;

    public MysqlDatabase(Mysql settings) throws SQLException {
        this.settings = settings;
        HikariConfig config = new HikariConfig();
        config.setPoolName("MR-Punish");
        config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(Math.min(2, settings.poolSize()));
        config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(5));
        config.setMaxLifetime(TimeUnit.MINUTES.toMillis(30));
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("characterEncoding", "utf8");
        try {
            // Paper ships the driver; name it so the pool doesn't rely on the service lookup.
            Class.forName(DRIVER);
            config.setDriverClassName(DRIVER);
        } catch (ClassNotFoundException e) {
            // Left to DriverManager.
        }
        settings.properties().forEach(config::addDataSourceProperty);
        try {
            pool = new HikariDataSource(config);
        } catch (RuntimeException e) {
            throw new SQLException("Couldn't connect to MySQL at " + settings.host() + ":" + settings.port() + ": "
                    + e.getMessage(), "08001", e);
        }
    }

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public String table(String name) {
        return Dialect.MYSQL.quote(settings.tablePrefix() + name);
    }

    @Override
    public String prefix() {
        return settings.tablePrefix();
    }

    @Override
    public <T> T with(SqlWork<T> work) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            return work.run(connection);
        }
    }

    @Override
    public String backup(int version) {
        return "Back up the MySQL database " + settings.database() + " now (e.g. with mysqldump): MR-Punish is"
                + " upgrading its tables from schema version " + version + " and can't make a copy itself";
    }

    @Override
    public String describe() {
        return "MySQL (" + settings.host() + ":" + settings.port() + "/" + settings.database() + ")";
    }

    @Override
    public void close() {
        pool.close();
    }
}
