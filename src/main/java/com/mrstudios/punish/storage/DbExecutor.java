package com.mrstudios.punish.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs all database work off the main thread (DESIGN.md 2.2): one thread for SQLite (one writer),
 * {@code pool-size} threads for MySQL. Every task has a timeout, and a failed task completes its
 * future with a {@link StorageException} carrying an error ID that's also in the log, never silently.
 */
public final class DbExecutor {

    /** How long a task may take before its future fails (the work itself still finishes). */
    public static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final ExecutorService threads;
    private final Supplier<Database> database;
    private final DatabaseHealth health;
    private final Logger log;
    private volatile boolean closed;

    /**
     * @param threadCount 1 for SQLite, the pool size for MySQL
     * @param database    the open database (may be null while it isn't open)
     */
    public DbExecutor(int threadCount, Supplier<Database> database, DatabaseHealth health, Logger log) {
        AtomicInteger number = new AtomicInteger();
        this.threads = Executors.newFixedThreadPool(threadCount, run -> {
            Thread thread = new Thread(run, "MR-Punish-DB-" + number.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
        this.database = database;
        this.health = health;
        this.log = log;
    }

    /** Runs {@code work} with a connection, in auto-commit mode (single reads and writes). */
    public <T> CompletableFuture<T> submit(String what, SqlWork<T> work) {
        return run(what, () -> {
            Database db = requireOpen();
            return db.with(work);
        });
    }

    /**
     * Runs {@code work} in one transaction: committed if it returns, rolled back if it throws
     * (DESIGN.md 9.3).
     */
    public <T> CompletableFuture<T> transaction(String what, SqlWork<T> work) {
        return run(what, () -> requireOpen().with(c -> inTransaction(c, work)));
    }

    static <T> T inTransaction(Connection c, SqlWork<T> work) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            try {
                c.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            try {
                c.setAutoCommit(auto);
            } catch (SQLException ignored) {
                // The connection is broken; the next use reopens it.
            }
        }
    }

    /** Opening, pinging and migrating: runs on a database thread even while storage isn't up. */
    public <T> CompletableFuture<T> maintenance(String what, Callable<T> work) {
        return run(what, work);
    }

    private Database requireOpen() {
        Database db = database.get();
        if (db == null || !health.isUp()) {
            throw new StorageException(null, "The database is unavailable (" + health.reason() + ")", null);
        }
        return db;
    }

    private <T> CompletableFuture<T> run(String what, Callable<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new StorageException(null, "The server is stopping", null));
            return future;
        }
        try {
            threads.execute(() -> {
                try {
                    future.complete(work.call());
                } catch (StorageException e) {
                    future.completeExceptionally(e);
                } catch (Exception e) {
                    future.completeExceptionally(fail(what, e));
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new StorageException(null, "The server is stopping", e));
        }
        return future.orTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** Logs the failure with a fresh error ID and marks storage down if the connection is gone. */
    private StorageException fail(String what, Exception e) {
        String id = errorId();
        log.log(Level.SEVERE, "Database error " + id + " while " + what + ": " + e.getMessage(), e);
        if (DatabaseHealth.isConnectionLoss(e)) {
            health.markDown(e.getMessage());
        }
        return new StorageException(id, "Database error " + id + " while " + what, e);
    }

    static String errorId() {
        return String.format(Locale.ROOT, "%08x", ThreadLocalRandom.current().nextInt());
    }

    /**
     * Stops taking new work and waits up to {@code timeoutMs} for submitted work to finish (it
     * still commits). Work submitted afterwards fails with "the server is stopping".
     *
     * @return true if everything finished in time
     */
    public boolean shutdown(long timeoutMs) {
        closed = true;
        threads.shutdown();
        try {
            return threads.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isShutDown() {
        return closed;
    }
}
