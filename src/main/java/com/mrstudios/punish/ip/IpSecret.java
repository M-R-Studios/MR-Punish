package com.mrstudios.punish.ip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The secret behind {@link IpHasher}, in {@code plugins/MR-Punish/ip-secret.key} (DESIGN.md 8.1): 32
 * bytes from {@link SecureRandom}, Base64, created the first time IP features are turned on. Never
 * shown in game or logged. Losing it means existing IP punishments stop matching; the README says to
 * back it up and to copy the same file to every server on a shared database.
 */
public final class IpSecret {

    public static final String FILE = "ip-secret.key";
    private static final int BYTES = 32;

    private IpSecret() {
    }

    /** The result of {@link #loadOrCreate}: the secret and whether it was just made. */
    public record Loaded(byte[] secret, boolean created) {
    }

    /**
     * Reads the key file, or creates it if it doesn't exist.
     *
     * @throws IOException if the file can't be read or written, or doesn't hold a valid key (the
     *                     message names the file, never its content)
     */
    public static Loaded loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) {
            String text = Files.readString(file, StandardCharsets.US_ASCII).strip();
            byte[] secret;
            try {
                secret = Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException e) {
                throw new IOException(file.getFileName() + " isn't a valid key (not Base64)");
            }
            if (secret.length != BYTES) {
                throw new IOException(file.getFileName() + " isn't a valid key (" + secret.length + " bytes, expected "
                        + BYTES + ")");
            }
            return new Loaded(secret, false);
        }
        byte[] secret = new byte[BYTES];
        new SecureRandom().nextBytes(secret);
        Files.createDirectories(file.toAbsolutePath().getParent());
        // CREATE_NEW: never overwrite a key that appeared in the meantime.
        Files.writeString(file, Base64.getEncoder().encodeToString(secret) + "\n", StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new Loaded(secret, true);
    }
}
