package com.mrstudios.punish.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text after the player name of a punishment command (DESIGN.md 4.1), pure: {@code -s} may
 * appear anywhere and makes the punishment silent; the rest is the reason and note. Notes and
 * removal reasons are plain text: control characters are stripped, the text is trimmed and cut.
 */
public final class CommandArgs {

    public static final String SILENT = "-s";

    /** @param text the remaining words without any {@code -s}, single-spaced */
    public record Parsed(boolean silent, String text) {
    }

    private CommandArgs() {
    }

    public static Parsed parse(String rest) {
        if (rest == null || rest.isBlank()) {
            return new Parsed(false, "");
        }
        boolean silent = false;
        List<String> words = new ArrayList<>();
        for (String word : rest.strip().split("\\s+")) {
            if (word.toLowerCase(Locale.ROOT).equals(SILENT)) {
                silent = true;
            } else {
                words.add(word);
            }
        }
        return new Parsed(silent, String.join(" ", words));
    }

    /** Strips control characters, trims, and cuts at {@code max} characters; null if nothing is left. */
    public static String clean(String text, int max) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        text.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(out::appendCodePoint);
        String cleaned = out.toString().strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > max) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, max)).strip();
        }
        return cleaned;
    }

    /**
     * Where the word being typed starts in {@code rest}, and whether it's the reason (the first word
     * that isn't {@code -s}); used for tab completion.
     *
     * @return {start, 1 if the word is the reason, else 0}
     */
    public static int[] completing(String rest) {
        int start = rest.lastIndexOf(' ') + 1;
        String before = rest.substring(0, start);
        boolean onlyFlags = true;
        for (String word : before.strip().split("\\s+")) {
            if (!word.isEmpty() && !word.equalsIgnoreCase(SILENT)) {
                onlyFlags = false;
            }
        }
        return new int[] {start, onlyFlags ? 1 : 0};
    }
}
