package com.mrstudios.punish.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Database work that runs with a connection, on the database thread. */
@FunctionalInterface
public interface SqlWork<T> {

    T run(Connection connection) throws SQLException;
}
