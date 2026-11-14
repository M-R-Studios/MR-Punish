package com.mrstudios.punish.punish;

import com.mrstudios.punish.config.GuiSettings;
import com.mrstudios.punish.config.MessagesLoader;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.RankLoader;
import com.mrstudios.punish.config.ScoreboardSettings;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.config.SettingsLoader;
import com.mrstudios.punish.punish.Requests.Actor;
import com.mrstudios.punish.punish.Requests.Target;
import com.mrstudios.punish.rank.Authority.Standing;
import com.mrstudios.punish.rank.OfflineRanks;
import com.mrstudios.punish.storage.Migrations;
import com.mrstudios.punish.storage.PlayerRepo;
import com.mrstudios.punish.storage.Storage;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Test-only driver for the punishment core (step 3 has no commands): a real SQLite database in a
 * temp folder, the shipped config, a controllable clock and no server. Follow-ups run inline.
 */
final class ServiceHarness implements AutoCloseable {

    static final long DAY = 24L * 60 * 60 * 1000;
    static final long HOUR = 60L * 60 * 1000;
    static final long MINUTE = 60L * 1000;

    final AtomicLong clock = new AtomicLong(100 * DAY);
    final List<String> logged = new ArrayList<>();
    final Storage storage;
    final PluginState state;
    final PunishmentService service;
    volatile OfflineRanks offline = OfflineRanks.NONE;

    ServiceHarness(Path dir) {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Settings settings = new SettingsLoader(name -> true, List.of(), Set.of(), SettingsLoader.bundledDefaults())
                .defaults();
        state = new PluginState(settings, new MessagesLoader(MessagesLoader.bundledDefaults()).defaults(),
                new com.mrstudios.punish.config.GuiLoader().defaults(), new RankLoader(name -> true, RankLoader.bundledDefaults()).defaults(),
                ScoreboardSettings.DEFAULTS);
        storage = new Storage(settings.storage(), s -> com.mrstudios.punish.storage.TestDatabases.open(dir),
                Migrations.shipped(clock::get), log);
        if (!storage.start(10_000)) {
            throw new IllegalStateException("storage didn't start");
        }
        service = new PunishmentService(storage, () -> state, uuid -> offline.load(uuid), Runnable::run,
                new Notifier(List::of, log), clock::get, log);
    }

    @Override
    public void close() {
        com.mrstudios.punish.storage.Database db = storage.database();
        if (db != null && db.dialect() == com.mrstudios.punish.storage.Dialect.MYSQL) {
            // The test's own tables go first; the pool closes with the storage below.
            com.mrstudios.punish.storage.TestDatabases.dispose(db);
        }
        storage.shutdown(5000);
    }

    Reason reason(String key) {
        return state.settings().reasons().byKey(key).orElseThrow();
    }

    // ---- People ----

    static Actor staff(UUID uuid, String name, int weight) {
        return new Actor(new Standing(uuid, name, weight, false), permission -> true);
    }

    static Actor staff(UUID uuid, String name, int weight, Predicate<String> permissions) {
        return new Actor(new Standing(uuid, name, weight, false), permissions);
    }

    static final Actor CONSOLE = Actor.console("Console");

    /** Records a visit so the player is known with this cached rank. */
    void known(UUID uuid, String name, int weight, boolean exempt) throws Exception {
        get(storage.executor().submit("test", c -> {
            new PlayerRepo(storage.database()).upsert(c,
                    new PlayerRepo.Visit(uuid, name, clock.get(), weight == 0 ? null : "rank" + weight, weight, exempt,
                            false));
            return null;
        }));
    }

    static Target online(UUID uuid, String name, int weight) {
        return Target.online(new Standing(uuid, name, weight, false));
    }

    static Target offline(UUID uuid, String name) {
        return Target.offline(uuid, name);
    }

    // ---- Actions ----

    Requests.IssueResult issue(Actor actor, Target target, String reason) throws Exception {
        return issue(actor, target, reason, null, null);
    }

    Requests.IssueResult issue(Actor actor, Target target, String reason, String note, Preview expected)
            throws Exception {
        return get(service.issue(new Requests.Issue(actor, target, reason(reason), note, false,
                Punishment.Origin.COMMAND, expected)));
    }

    Requests.RemoveResult remove(Actor actor, Target target, PunishmentType type, Long id, String why)
            throws Exception {
        return get(service.remove(new Requests.Remove(actor, target, type, id, why)));
    }

    Punishment committed(Requests.IssueResult result) {
        if (result instanceof Requests.IssueResult.Committed c) {
            return c.punishment();
        }
        throw new AssertionError("Not committed: " + result);
    }

    // ---- Reading back ----

    List<Punishment> history(UUID target) throws Exception {
        return get(storage.executor().submit("test",
                c -> new com.mrstudios.punish.storage.PunishmentRepo(storage.database()).ofTarget(c, target)));
    }

    Punishment byId(long id) throws Exception {
        return get(storage.executor().submit("test",
                c -> new com.mrstudios.punish.storage.PunishmentRepo(storage.database()).byId(c, id))).orElseThrow();
    }

    int count(String table) throws Exception {
        return get(storage.executor().submit("test", c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql("SELECT COUNT(*) FROM " + table))) {
                r.next();
                return r.getInt(1);
            }
        }));
    }

    List<Map<String, Object>> rows(String sql) throws Exception {
        return get(storage.executor().submit("test", c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql(sql))) {
                int columns = r.getMetaData().getColumnCount();
                while (r.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(r.getMetaData().getColumnLabel(i), r.getObject(i));
                    }
                    out.add(row);
                }
            }
            return out;
        }));
    }

    void exec(String sql) throws Exception {
        get(storage.executor().submit("test", c -> {
            try (Statement s = c.createStatement()) {
                s.execute(sql(sql));
            }
            return null;
        }));
    }

    /** Test SQL with the shipped table names, for whichever database the tests run on. */
    String sql(String sql) {
        return com.mrstudios.punish.storage.TestDatabases.sql(storage.database(), sql);
    }

    static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
}
