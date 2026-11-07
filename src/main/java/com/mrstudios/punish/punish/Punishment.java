package com.mrstudios.punish.punish;

import java.util.UUID;

/**
 * One row of {@code punishments} (DESIGN.md 7.1). Immutable; history is never deleted, so a
 * removal or replacement is a new version of the row with its status and who/when/why filled in.
 *
 * @param targetName   for IP types: the account the command named
 * @param ipHash       IP types only, else null
 * @param reasonDisplay copied at issue time, so history survives config edits
 * @param offence      the offence number (1-based) this punishment was
 * @param step         the escalation step used (1-based)
 * @param durationMs   null for permanent and for warnings
 * @param expiresAt    null for permanent (warnings: issuedAt + warnings.active-for, or null)
 * @param issuerUuid   null for console
 */
public record Punishment(
        long id,
        PunishmentType type,
        UUID targetUuid,
        String targetName,
        String ipHash,
        String reasonKey,
        String reasonDisplay,
        String note,
        int offence,
        int step,
        Long durationMs,
        long issuedAt,
        Long expiresAt,
        UUID issuerUuid,
        String issuerName,
        int issuerWeight,
        int targetWeight,
        WeightSource targetWeightSource,
        String server,
        boolean silent,
        Origin origin,
        StoredStatus status,
        UUID removedByUuid,
        String removedByName,
        Long removedAt,
        String removedReason,
        Long replacedById,
        Long replacedAt) {

    /** Who issued or how, for {@code origin}. */
    public enum Origin {
        COMMAND,
        GUI,
        FREEZE_QUIT,
        IMPORT_VANILLA
    }

    /** What the database says; expiry is never stored (DESIGN.md 2.3). */
    public enum StoredStatus {
        ACTIVE,
        REMOVED,
        REPLACED
    }

    /** What a punishment is right now, computed from the stored status and the time. */
    public enum EffectiveStatus {
        ACTIVE,
        EXPIRED,
        REMOVED,
        REPLACED
    }

    /** Where the target's rank weight came from when it was punished. */
    public enum WeightSource {
        LIVE,
        LUCKPERMS,
        CACHED,
        CONSOLE
    }

    public EffectiveStatus effectiveStatus(long now) {
        return switch (status) {
            case REMOVED -> EffectiveStatus.REMOVED;
            case REPLACED -> EffectiveStatus.REPLACED;
            case ACTIVE -> expiresAt != null && expiresAt <= now ? EffectiveStatus.EXPIRED : EffectiveStatus.ACTIVE;
        };
    }

    public boolean isActive(long now) {
        return effectiveStatus(now) == EffectiveStatus.ACTIVE;
    }

    public boolean permanent() {
        return expiresAt == null;
    }
}
