package com.mrstudios.punish.config;

import java.util.List;

/**
 * Outcome of loading one file. When {@code error} is set the file could not be used at all and
 * {@code value} is null: the caller keeps whatever it had before. Otherwise {@code value} is the
 * loaded object and {@code warnings} lists every value that was replaced by its default.
 */
public record LoadResult<T>(T value, List<String> warnings, String error) {

    public LoadResult {
        warnings = List.copyOf(warnings);
    }

    public static <T> LoadResult<T> loaded(T value, List<String> warnings) {
        return new LoadResult<>(value, warnings, null);
    }

    public static <T> LoadResult<T> failed(String error) {
        return new LoadResult<>(null, List.of(), error);
    }

    public boolean ok() {
        return error == null;
    }
}
