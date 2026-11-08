package com.mrstudios.punish.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.gui.HistoryMenus;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.Punishment.Origin;
import com.mrstudios.punish.punish.Punishment.StoredStatus;
import com.mrstudios.punish.punish.Punishment.WeightSource;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.storage.PunishmentRepo.HistoryFilter;
import com.mrstudios.punish.storage.PunishmentRepo.StatusFilter;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The history query (DESIGN.md 10.5): IP-hash matches, filters in SQL, paging, and the pure paging helpers. */
class HistoryQueryTest {

    private static final long NOW = 1_000_000_000L;
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ALT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final String HASH = "hash-home";
    private static final String ELSEWHERE = "hash-elsewhere";

    @TempDir
    Path dir;
    private SqliteDatabase db;
    private PunishmentRepo repo;

    @BeforeEach
    void setUp() throws Exception {
        db = new SqliteDatabase(dir.resolve("punishments.db").toFile());
        Migrations.shipped(() -> 0L).migrate(db, line -> { });
        repo = new PunishmentRepo(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private long add(PunishmentType type, UUID target, String hash, Long expiresAt, StoredStatus status) throws Exception {
        Punishment p = new Punishment(0, type, target, "x", hash, "r", "R", null, 1, 1, null, NOW - 100, expiresAt, null,
                "Console", 0, 0, WeightSource.CACHED, "default", false, Origin.COMMAND, status, null, null, null, null,
                null, null);
        return db.with(c -> repo.insert(c, p));
    }

    private List<Long> ids(List<String> hashes, HistoryFilter filter, int offset, int limit) throws Exception {
        return db.with(c -> repo.history(c, STEVE, hashes, filter, NOW, offset, limit)).stream().map(Punishment::id)
                .toList();
    }

    private int count(List<String> hashes, HistoryFilter filter) throws Exception {
        return db.with(c -> repo.countHistory(c, STEVE, hashes, filter, NOW));
    }

    @Test
    void theAccountsPunishmentsAndIpPunishmentsOnItsHashesNewestFirst() throws Exception {
        long warn = add(PunishmentType.WARN, STEVE, null, null, StoredStatus.ACTIVE);
        long altIpBan = add(PunishmentType.IP_BAN, ALT, HASH, null, StoredStatus.ACTIVE);
        long otherIp = add(PunishmentType.IP_MUTE, OTHER, ELSEWHERE, null, StoredStatus.ACTIVE);
        long otherBan = add(PunishmentType.BAN, OTHER, null, null, StoredStatus.ACTIVE);
        long ownIp = add(PunishmentType.IP_MUTE, STEVE, ELSEWHERE, NOW + 5, StoredStatus.ACTIVE);
        assertEquals(List.of(ownIp, altIpBan, warn), ids(List.of(HASH), HistoryFilter.ALL, 0, 50));
        assertEquals(List.of(ownIp, otherIp, altIpBan, warn), ids(List.of(HASH, ELSEWHERE), HistoryFilter.ALL, 0, 50));
        assertEquals(List.of(ownIp, warn), ids(List.of(), HistoryFilter.ALL, 0, 50));
        assertTrue(!ids(List.of(HASH, ELSEWHERE), HistoryFilter.ALL, 0, 50).contains(otherBan));
        assertEquals(3, count(List.of(HASH), HistoryFilter.ALL));
    }

    @Test
    void filtersByTypeAndEffectiveStatus() throws Exception {
        long active = add(PunishmentType.MUTE, STEVE, null, NOW + 1000, StoredStatus.ACTIVE);
        long permanent = add(PunishmentType.BAN, STEVE, null, null, StoredStatus.ACTIVE);
        long expired = add(PunishmentType.MUTE, STEVE, null, NOW - 1, StoredStatus.ACTIVE);
        long expiresNow = add(PunishmentType.MUTE, STEVE, null, NOW, StoredStatus.ACTIVE);
        long removed = add(PunishmentType.MUTE, STEVE, null, NOW + 1000, StoredStatus.REMOVED);
        long replaced = add(PunishmentType.BAN, STEVE, null, null, StoredStatus.REPLACED);
        List<String> none = List.of();
        assertEquals(List.of(permanent, active), ids(none, new HistoryFilter(null, StatusFilter.ACTIVE), 0, 50));
        assertEquals(List.of(expiresNow, expired), ids(none, new HistoryFilter(null, StatusFilter.EXPIRED), 0, 50));
        assertEquals(List.of(removed), ids(none, new HistoryFilter(null, StatusFilter.REMOVED), 0, 50));
        assertEquals(List.of(replaced), ids(none, new HistoryFilter(null, StatusFilter.REPLACED), 0, 50));
        assertEquals(List.of(removed, expiresNow, expired, active),
                ids(none, new HistoryFilter(PunishmentType.MUTE, StatusFilter.ALL), 0, 50));
        assertEquals(List.of(active), ids(none, new HistoryFilter(PunishmentType.MUTE, StatusFilter.ACTIVE), 0, 50));
        assertEquals(List.of(), ids(none, new HistoryFilter(PunishmentType.WARN, StatusFilter.ALL), 0, 50));
        assertEquals(1, count(none, new HistoryFilter(PunishmentType.BAN, StatusFilter.REPLACED)));
    }

    @Test
    void pagesOf45() throws Exception {
        for (int i = 0; i < 101; i++) {
            add(PunishmentType.WARN, STEVE, null, null, StoredStatus.ACTIVE);
        }
        assertEquals(101, count(List.of(), HistoryFilter.ALL));
        List<Long> first = ids(List.of(), HistoryFilter.ALL, 0, 45);
        List<Long> second = ids(List.of(), HistoryFilter.ALL, 45, 45);
        List<Long> third = ids(List.of(), HistoryFilter.ALL, 90, 45);
        assertEquals(45, first.size());
        assertEquals(45, second.size());
        assertEquals(11, third.size());
        assertEquals(101L, first.get(0));
        assertEquals(first.get(44) - 1, second.get(0));
        assertEquals(1L, third.get(10));
        assertEquals(List.of(), ids(List.of(), HistoryFilter.ALL, 135, 45));
    }

    @Test
    void pagingAndFilterHelpers() {
        assertEquals(1, HistoryMenus.pages(0, 45));
        assertEquals(1, HistoryMenus.pages(45, 45));
        assertEquals(2, HistoryMenus.pages(46, 45));
        assertEquals(3, HistoryMenus.pages(101, 45));
        assertEquals(11, HistoryMenus.pages(101, 10));
        assertEquals(0, HistoryMenus.clamp(-3, 3));
        assertEquals(2, HistoryMenus.clamp(7, 3));
        assertEquals(1, HistoryMenus.clamp(1, 3));
        assertEquals(PunishmentType.WARN, HistoryMenus.nextType(null));
        assertEquals(PunishmentType.MUTE, HistoryMenus.nextType(PunishmentType.WARN));
        assertEquals(PunishmentType.IP_BAN, HistoryMenus.nextType(PunishmentType.IP_MUTE));
        assertNull(HistoryMenus.nextType(PunishmentType.IP_BAN));
        assertEquals(StatusFilter.ACTIVE, HistoryMenus.nextStatus(StatusFilter.ALL));
        assertEquals(StatusFilter.REPLACED, HistoryMenus.nextStatus(StatusFilter.REMOVED));
        assertEquals(StatusFilter.ALL, HistoryMenus.nextStatus(StatusFilter.REPLACED));
    }
}
