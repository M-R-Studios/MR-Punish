package com.mrstudios.punish.imports;

import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.Punishment.Origin;
import com.mrstudios.punish.punish.Punishment.StoredStatus;
import com.mrstudios.punish.punish.Punishment.WeightSource;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.Database;
import com.mrstudios.punish.storage.PlayerRepo;
import com.mrstudios.punish.storage.PunishmentRepo;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Copies vanilla's player ban list into MR-Punish history once (DESIGN.md 7.9). Idempotent: each
 * entry gets the import key {@code vanilla:profile:<uuid>}, so running it again skips what's already
 * there. The whole import is one transaction. Reading and clearing the vanilla list happen on the
 * main thread (in the caller); this class only touches the database.
 */
public final class VanillaImporter {

    public static final String REASON_KEY = "imported-vanilla";
    private static final String DEFAULT_REASON = "Banned by an operator.";

    /**
     * One entry of {@code banned-players.json}, copied on the main thread.
     *
     * @param uuid    null if the entry has no readable profile id
     * @param expires null = permanent
     */
    public record Entry(UUID uuid, String name, String reason, String source, long created, Long expires) {
    }

    /**
     * One entry of {@code banned-ips.json}, hashed on the main thread: the address never gets here.
     *
     * @param hash null if the entry isn't a readable IP
     */
    public record IpEntry(String hash, String reason, String source, long created, Long expires) {
    }

    /** The account an imported IP ban is filed under: vanilla IP bans aren't tied to an account. */
    public static final UUID NO_ACCOUNT = new UUID(0, 0);

    /**
     * @param imported         the UUIDs now in MR-Punish (new and already imported), for {@code --clear}
     * @param ips              IP bans imported now
     * @param importedIpHashes the hashes now in MR-Punish, for {@code --clear}
     */
    public record Result(int players, int skipped, int unknown, List<UUID> imported, int ips,
            List<String> importedIpHashes) {
    }

    private final PunishmentRepo punishments;
    private final PlayerRepo players;
    private final ActionRepo actions;

    public VanillaImporter(Database database) {
        this.punishments = new PunishmentRepo(database);
        this.players = new PlayerRepo(database);
        this.actions = new ActionRepo(database);
    }

    static String importKey(UUID uuid) {
        return "vanilla:profile:" + uuid;
    }

    /** Runs inside one transaction. */
    public Result run(Connection c, List<Entry> entries, String actorName, UUID actorUuid, String server, long now)
            throws SQLException {
        return run(c, entries, List.of(), actorName, actorUuid, server, now);
    }

    public Result run(Connection c, List<Entry> entries, List<IpEntry> ipEntries, String actorName, UUID actorUuid,
            String server, long now) throws SQLException {
        int imported = 0;
        int skipped = 0;
        int unknown = 0;
        List<UUID> inMrPunish = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.uuid() == null) {
                unknown++;
                continue;
            }
            String key = importKey(entry.uuid());
            if (punishments.importKeyExists(c, key)) {
                skipped++;
                inMrPunish.add(entry.uuid());
                continue;
            }
            String name = entry.name() == null || entry.name().isBlank() ? entry.uuid().toString() : entry.name();
            if (players.byUuid(c, entry.uuid()).isEmpty()) {
                players.upsert(c, new PlayerRepo.Visit(entry.uuid(), name, entry.created(), null, 0, false, false));
            }
            String reason = entry.reason() == null || entry.reason().isBlank() ? DEFAULT_REASON : entry.reason();
            String source = entry.source() == null || entry.source().isBlank() ? "Server" : entry.source();
            Long duration = entry.expires() == null ? null : Math.max(0, entry.expires() - entry.created());
            Punishment ban = new Punishment(0, PunishmentType.BAN, entry.uuid(), name, null, REASON_KEY,
                    "Imported: " + MiniMessage.miniMessage().escapeTags(reason), reason, 1, 1, duration, entry.created(),
                    entry.expires(), null, truncate(source, 32), 0, 0, WeightSource.CACHED, server, true,
                    Origin.IMPORT_VANILLA, StoredStatus.ACTIVE, null, null, null, null, null, null);
            long id = punishments.insert(c, ban);
            punishments.setImportKey(c, id, key);
            imported++;
            inMrPunish.add(entry.uuid());
        }
        int ips = 0;
        List<String> hashesInMrPunish = new ArrayList<>();
        for (IpEntry entry : ipEntries) {
            if (entry.hash() == null) {
                unknown++;
                continue;
            }
            String key = "vanilla:ip:" + entry.hash();
            if (punishments.importKeyExists(c, key)) {
                skipped++;
                hashesInMrPunish.add(entry.hash());
                continue;
            }
            String reason = entry.reason() == null || entry.reason().isBlank() ? DEFAULT_REASON : entry.reason();
            String source = entry.source() == null || entry.source().isBlank() ? "Server" : entry.source();
            Long duration = entry.expires() == null ? null : Math.max(0, entry.expires() - entry.created());
            Punishment ban = new Punishment(0, PunishmentType.IP_BAN, NO_ACCOUNT, "(imported IP ban)", entry.hash(),
                    REASON_KEY, "Imported: " + MiniMessage.miniMessage().escapeTags(reason), reason, 1, 1, duration,
                    entry.created(), entry.expires(), null, truncate(source, 32), 0, 0, WeightSource.CACHED, server,
                    true, Origin.IMPORT_VANILLA, StoredStatus.ACTIVE, null, null, null, null, null, null);
            long id = punishments.insert(c, ban);
            punishments.setImportKey(c, id, key);
            ips++;
            hashesInMrPunish.add(entry.hash());
        }
        actions.insert(c, new ActionRepo.Action(now, server, actorUuid, actorName, "IMPORT", null, null, null,
                "vanilla: imported=" + imported + " ip=" + ips + " skipped=" + skipped + " unreadable=" + unknown));
        return new Result(imported, skipped, unknown, inMrPunish, ips, hashesInMrPunish);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
