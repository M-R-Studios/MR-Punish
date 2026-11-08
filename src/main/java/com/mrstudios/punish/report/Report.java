package com.mrstudios.punish.report;

import java.util.UUID;

/**
 * One row of {@code reports} (DESIGN.md 13). Immutable; every change goes through
 * {@link ReportService} with the {@link #version} it was read at (optimistic lock).
 *
 * @param reasonKey a configured report reason, or null for a typed one
 * @param world     the reporter's location when reporting (world null if unknown)
 */
public record Report(long id, UUID reporterUuid, String reporterName, UUID targetUuid, String targetName,
        String reasonKey, String reasonText, String server, String world, Integer x, Integer y, Integer z,
        long createdAt, Status status, UUID claimedByUuid, String claimedByName, Long claimedAt, UUID closedByUuid,
        String closedByName, Long closedAt, String decisionNote, int version) {

    public enum Status {
        OPEN, CLAIMED, RESOLVED, REJECTED;

        public boolean closed() {
            return this == RESOLVED || this == REJECTED;
        }
    }

    public boolean claimedBy(UUID uuid) {
        return status == Status.CLAIMED && uuid.equals(claimedByUuid);
    }

    /** A note on a report: internal, never deleted. @param authorUuid null for console */
    public record Note(long id, long reportId, UUID authorUuid, String authorName, String text, long createdAt) {
    }
}
