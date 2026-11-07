package com.mrstudios.punish.config;

/**
 * A length of time from the config: a positive number of milliseconds, or permanent. Built by
 * {@link DurationParser}.
 */
public record TimeSpan(boolean permanent, long millis) {

    public static final TimeSpan PERMANENT = new TimeSpan(true, 0);

    public TimeSpan {
        if (!permanent && millis <= 0) {
            throw new IllegalArgumentException("A time span must be positive or permanent, not " + millis + " ms");
        }
        if (permanent) {
            millis = 0;
        }
    }

    public static TimeSpan ofMillis(long millis) {
        return new TimeSpan(false, millis);
    }

    @Override
    public String toString() {
        return permanent ? "permanent" : DurationParser.format(millis);
    }
}
