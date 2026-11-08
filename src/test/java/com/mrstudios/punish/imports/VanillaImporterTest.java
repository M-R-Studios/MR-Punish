package com.mrstudios.punish.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.Punishment.EffectiveStatus;
import com.mrstudios.punish.storage.Migrations;
import com.mrstudios.punish.storage.PlayerRepo;
import com.mrstudios.punish.storage.PunishmentRepo;
import com.mrstudios.punish.storage.SqliteDatabase;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DESIGN.md 7.9: the vanilla import is complete, idempotent and keeps the original times. */
class VanillaImporterTest {

    @TempDir
    Path dir;
    private SqliteDatabase db;

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        db = new SqliteDatabase(dir.resolve("punishments.db").toFile());
        Migrations.shipped(() -> 0L).migrate(db, line -> { });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private VanillaImporter.Result run(List<VanillaImporter.Entry> entries) throws Exception {
        return db.with(c -> new VanillaImporter(db).run(c, entries, "Console", null, "default", 100 * DAY));
    }

    private static List<VanillaImporter.Entry> entries() {
        return List.of(
                new VanillaImporter.Entry(A, "Griefer", "Griefed spawn <red>badly</red>", "Notch", 10 * DAY, null),
                new VanillaImporter.Entry(B, "TempGuy", null, null, 50 * DAY, 60 * DAY),     // expired on day 60
                new VanillaImporter.Entry(C, "Current", "Hacks", "Admin", 90 * DAY, 120 * DAY),
                new VanillaImporter.Entry(null, null, "Broken", "x", 0, null));
    }

    @Test
    void importsEveryReadableEntryWithItsTimesAndReason() throws Exception {
        VanillaImporter.Result result = run(entries());
        assertEquals(3, result.players());
        assertEquals(0, result.skipped());
        assertEquals(1, result.unknown());
        assertEquals(List.of(A, B, C), result.imported());

        Punishment griefer = db.with(c -> new PunishmentRepo(db).ofTarget(c, A)).get(0);
        assertEquals("imported-vanilla", griefer.reasonKey());
        assertEquals("Imported: Griefed spawn \\<red>badly\\</red>", griefer.reasonDisplay(), "tags are escaped");
        assertEquals("Griefed spawn <red>badly</red>", griefer.note());
        assertEquals("Notch", griefer.issuerName());
        assertNull(griefer.issuerUuid());
        assertEquals(0, griefer.issuerWeight(), "any admin can remove an imported ban");
        assertEquals(Punishment.Origin.IMPORT_VANILLA, griefer.origin());
        assertEquals(10 * DAY, griefer.issuedAt());
        assertNull(griefer.expiresAt());
        assertEquals(EffectiveStatus.ACTIVE, griefer.effectiveStatus(100 * DAY));

        Punishment temp = db.with(c -> new PunishmentRepo(db).ofTarget(c, B)).get(0);
        assertEquals(EffectiveStatus.EXPIRED, temp.effectiveStatus(100 * DAY), "already expired: shown as expired");
        assertEquals("Imported: Banned by an operator.", temp.reasonDisplay());
        assertEquals("Server", temp.issuerName());
        assertEquals(10 * DAY, temp.durationMs());

        assertEquals("Griefer", db.with(c -> new PlayerRepo(db).byUuid(c, A)).orElseThrow().name(),
                "the player becomes known so they can be found and unbanned");
    }

    @Test
    void runningAgainImportsNothing() throws Exception {
        run(entries());
        VanillaImporter.Result again = run(entries());
        assertEquals(0, again.players());
        assertEquals(3, again.skipped());
        assertEquals(List.of(A, B, C), again.imported(), "still listed for --clear");
        int rows = db.with(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM punishments")) {
                r.next();
                return r.getInt(1);
            }
        });
        assertEquals(3, rows);
        int imports = db.with(c -> {
            try (Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SELECT COUNT(*) FROM staff_actions WHERE action = 'IMPORT'")) {
                r.next();
                return r.getInt(1);
            }
        });
        assertEquals(2, imports, "each run is logged");
    }

    @Test
    void anExistingPlayerRowIsKept() throws Exception {
        db.with(c -> {
            new PlayerRepo(db).upsert(c, new PlayerRepo.Visit(A, "RealName", 99 * DAY, "mod", 20, false, false));
            return null;
        });
        run(entries());
        PlayerRepo.PlayerRecord row = db.with(c -> new PlayerRepo(db).byUuid(c, A)).orElseThrow();
        assertEquals("RealName", row.name());
        assertEquals(20, row.rankWeight());
        assertTrue(row.lastSeen() == 99 * DAY);
    }
}
