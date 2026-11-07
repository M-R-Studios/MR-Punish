package com.mrstudios.punish.storage;

/**
 * Database work that failed. The {@link #errorId()} is shown to the staff member and printed with
 * the stack trace in the console, so the two can be matched up.
 */
public final class StorageException extends RuntimeException {

    private final String errorId;

    public StorageException(String errorId, String message, Throwable cause) {
        super(message, cause);
        this.errorId = errorId;
    }

    /** 8 hex characters, or null when nothing ran (storage unavailable, server stopping). */
    public String errorId() {
        return errorId;
    }
}
