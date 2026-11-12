package com.mrstudios.punish.staff;

import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.Storage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The daily staff log files (DESIGN.md 13.6): {@code logs/staff-YYYY-MM-DD.log}, one line per
 * action. It tails the {@code staff_actions} table, so only committed actions are written (a
 * rolled-back punishment never appears) and, on a shared database, other servers' actions too. One
 * writer thread; files older than {@code logs.file.keep-days} are deleted by the daily job.
 */
public final class StaffLogFile {

    private static final int BATCH = 500;

    private final Path dir;
    private final Storage storage;
    private final Supplier<PluginState> state;
    private final Logger log;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MR-Punish staff log writer");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastId = -1;
    private volatile boolean polling;

    public StaffLogFile(Path dataFolder, Storage storage, Supplier<PluginState> state, Logger log) {
        this.dir = dataFolder.resolve("logs");
        this.storage = storage;
        this.state = state;
        this.log = log;
    }

    private ZoneId zone() {
        return state.get().settings().display().zone();
    }

    /** Called every few seconds: writes the actions logged since the last call. */
    public void poll() {
        if (polling || storage.executor().isShutDown() || !storage.health().isUp()) {
            return;
        }
        polling = true;
        long from = lastId;
        storage.executor().submit("reading new staff actions for the file log", c -> {
            ActionRepo repo = new ActionRepo(storage.database());
            if (from < 0) {
                // Start after what's already there: older actions were written by the previous run.
                return new Object[] {repo.maxId(c), List.<ActionRepo.Logged>of()};
            }
            return new Object[] {from, repo.after(c, from, BATCH)};
        }).whenComplete((read, error) -> {
            polling = false;
            if (error != null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<ActionRepo.Logged> rows = (List<ActionRepo.Logged>) read[1];
            if (rows.isEmpty()) {
                lastId = Math.max(lastId, (long) read[0]);
                return;
            }
            lastId = rows.get(rows.size() - 1).id();
            if (state.get().settings().logs().fileEnabled()) {
                writer.execute(() -> write(rows));
            }
        });
    }

    private void write(List<ActionRepo.Logged> rows) {
        ZoneId zone = zone();
        try {
            Files.createDirectories(dir);
            for (ActionRepo.Logged row : rows) {
                ActionRepo.Action a = row.action();
                String line = StaffLog.line(a.at(), zone, a.server(), a.actorName(), a.action(), a.targetName(),
                        a.refId(), a.details()) + System.lineSeparator();
                Files.writeString(dir.resolve(StaffLog.fileName(a.at(), zone)), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warning("Couldn't write the staff log file (" + e.getMessage() + "); the actions are still in the"
                    + " database.");
        }
    }

    /** Deletes log files older than {@code logs.file.keep-days} (the daily job). */
    public void prune() {
        int keep = state.get().settings().logs().fileKeepDays();
        if (keep <= 0) {
            return;
        }
        LocalDate oldest = LocalDate.now(zone()).minusDays(keep);
        writer.execute(() -> {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.matches("staff-\\d{4}-\\d{2}-\\d{2}\\.log")) {
                        continue;
                    }
                    LocalDate day = LocalDate.parse(name.substring(6, 16));
                    if (day.isBefore(oldest)) {
                        Files.deleteIfExists(file);
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warning("Couldn't prune old staff log files: " + e.getMessage());
            }
        });
    }

    /** Plugin disable: the last actions are written, then the writer stops. */
    public void close() {
        if (lastId >= 0 && !storage.executor().isShutDown() && storage.health().isUp()) {
            try {
                List<ActionRepo.Logged> rows = storage.executor().submit("flushing the staff log file",
                        c -> new ActionRepo(storage.database()).after(c, lastId, BATCH)).get(3, TimeUnit.SECONDS);
                if (!rows.isEmpty() && state.get().settings().logs().fileEnabled()) {
                    writer.execute(() -> write(rows));
                }
            } catch (Exception e) {
                // The actions are in the database either way.
            }
        }
        writer.shutdown();
        try {
            writer.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
