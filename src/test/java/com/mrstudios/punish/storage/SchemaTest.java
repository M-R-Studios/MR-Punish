package com.mrstudios.punish.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.punish.NameIndex;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MySQL can't be run here, so its DDL and dialect SQL are checked as text. */
class SchemaTest {

    private static String table(List<String> ddl, String name) {
        return ddl.stream().filter(s -> s.startsWith("CREATE TABLE IF NOT EXISTS `" + name + "`")).findFirst()
                .orElseThrow();
    }

    @Test
    void mysqlDdlUsesPrefixedNamesVarcharsInlineIndexesAndForeignKeys() {
        List<String> ddl = Schema.ddl(Schema.v1(), Dialect.MYSQL, "mr_");
        assertEquals(11, ddl.size(), "one statement per table, indexes inline");
        String punishments = table(ddl, "mr_punishments");
        assertTrue(punishments.contains("id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"), punishments);
        assertTrue(punishments.contains("target_uuid CHAR(36) NOT NULL"), punishments);
        assertTrue(punishments.contains("note VARCHAR(500)"), punishments);
        assertTrue(punishments.contains("import_key VARCHAR(128) UNIQUE"), punishments);
        assertTrue(punishments.contains("FOREIGN KEY (replaced_by_id) REFERENCES `mr_punishments`(id)"), punishments);
        assertTrue(punishments.contains("INDEX `mr_pun_active` (status, type, expires_at)"), punishments);
        assertTrue(punishments.endsWith("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"), punishments);
        String players = table(ddl, "mr_players");
        assertTrue(players.contains("PRIMARY KEY (uuid)") && players.contains("rank_weight INT NOT NULL DEFAULT 0"),
                players);
        assertTrue(table(ddl, "mr_player_ips").contains("PRIMARY KEY (uuid, ip_hash)"));
        // Text columns that are keys or indexed must have a length on MySQL.
        for (String statement : ddl) {
            assertFalse(statement.matches(".*\\b(uuid|name_lower|ip_hash|k|status|type) TEXT\\b.*"), statement);
        }
    }

    @Test
    void sqliteDdlHasSeparateIndexes() {
        List<String> ddl = Schema.ddl(Schema.v1(), Dialect.SQLITE, "");
        assertTrue(ddl.contains("CREATE INDEX IF NOT EXISTS \"players_name\" ON \"players\" (name_lower, last_seen)"));
        assertTrue(ddl.stream().anyMatch(s -> s.contains("replaced_by_id INTEGER REFERENCES \"punishments\"(id)")));
        assertTrue(ddl.stream().allMatch(s -> s.contains("IF NOT EXISTS")), "every statement can run twice");
    }

    @Test
    void upsertsPerDialect() {
        List<String> cols = List.of("uuid", "name");
        assertEquals("INSERT INTO t (uuid, name) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name",
                Dialect.SQLITE.upsert("t", cols, List.of("uuid"), List.of("name")));
        assertEquals("INSERT INTO t (uuid, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name)",
                Dialect.MYSQL.upsert("t", cols, List.of("uuid"), List.of("name")));
        assertEquals("", Dialect.SQLITE.forUpdate());
        assertEquals(" FOR UPDATE", Dialect.MYSQL.forUpdate());
        assertEquals("INSERT IGNORE INTO", Dialect.MYSQL.insertIgnore());
    }

    @Test
    void nameIndexFollowsTheMostRecentOwnerOfAName() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        NameIndex index = new NameIndex();
        index.load(List.of(new NameIndex.Entry(a, "Alex", 100), new NameIndex.Entry(b, "alex", 50)));
        assertEquals(a, index.byName("ALEX").orElseThrow().uuid(), "seen most recently wins");

        index.record(a, "AlexNew", 200); // a renamed
        assertEquals(a, index.byName("alexnew").orElseThrow().uuid());
        assertTrue(index.byName("alex").isEmpty(), "the old name is no longer a's; b is found in the database");
        assertEquals("AlexNew", index.byUuid(a).orElseThrow().name());

        index.record(b, "Alex", 300);
        assertEquals(b, index.byName("alex").orElseThrow().uuid());
        index.record(a, "Old", 10); // stale data never overwrites newer
        assertEquals("AlexNew", index.byUuid(a).orElseThrow().name());

        assertEquals(List.of("AlexNew"), index.suggest("alexn", 20));
        assertEquals(2, index.size());
    }
}
