package com.mrstudios.punish.storage;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Whether the database can be used right now (DESIGN.md 9.3). Any thread may read or change it;
 * listeners are called on the thread that changed it and must hop to the main thread themselves.
 */
public final class DatabaseHealth {

    public enum State {
        /** Not opened yet (startup still running, or it failed and is being retried). */
        STARTING,
        UP,
        /** Was open, lost the connection; pinged until it answers. */
        DOWN,
        /** Made by a newer MR-Punish: never used, never retried. */
        INCOMPATIBLE
    }

    /** SQLite result codes that mean the file itself can't be used: IOERR, CANTOPEN, NOTADB. */
    private static final Set<Integer> SQLITE_FILE_ERRORS = Set.of(10, 14, 26);

    private volatile State state = State.STARTING;
    private volatile String reason = "not opened yet";
    private final CopyOnWriteArrayList<BiConsumer<State, State>> listeners = new CopyOnWriteArrayList<>();

    public State state() {
        return state;
    }

    public boolean isUp() {
        return state == State.UP;
    }

    /** Why it isn't up, for logs and {@code /mrpunish version}. */
    public String reason() {
        return reason;
    }

    /** Called with (old state, new state) on every change. */
    public void onChange(BiConsumer<State, State> listener) {
        listeners.add(listener);
    }

    public void markUp() {
        set(State.UP, "");
    }

    /** Lost the connection: only an open database can go down; otherwise it stays as it is. */
    public void markDown(String why) {
        if (state == State.UP) {
            set(State.DOWN, why);
        } else if (state != State.INCOMPATIBLE) {
            reason = why;
        }
    }

    /** Opening failed (still {@link State#STARTING}, retried) or the schema is too new (never retried). */
    public void markNotOpened(String why, boolean incompatible) {
        set(incompatible ? State.INCOMPATIBLE : State.STARTING, why);
    }

    private void set(State next, String why) {
        State old;
        synchronized (this) {
            old = state;
            state = next;
            reason = why;
        }
        if (old != next) {
            for (BiConsumer<State, State> listener : listeners) {
                listener.accept(old, next);
            }
        }
    }

    /**
     * Whether {@code e} means the connection or file is gone (rather than a bad statement): SQLState
     * class 08, a recoverable or connection-timeout exception, or SQLite IOERR/CANTOPEN/NOTADB.
     */
    public static boolean isConnectionLoss(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLRecoverableException || t instanceof SQLTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("08")) {
                    return true;
                }
                if (t.getClass().getName().startsWith("org.sqlite.")
                        && SQLITE_FILE_ERRORS.contains(sql.getErrorCode() & 0xFF)) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
