package com.mrstudios.punish.config;

/** A file can't be used. The message is shown to admins as is, so it names the file and the reason. */
public final class ConfigException extends Exception {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
