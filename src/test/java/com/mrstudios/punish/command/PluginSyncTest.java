package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.punish.Notifier;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.PunishmentService;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.Requests;
import com.mrstudios.punish.rank.OfflineRanks;
import com.mrstudios.punish.report.Report;
import com.mrstudios.punish.storage.FrozenRepo;
import com.mrstudios.punish.storage.ReportRepo;
import com.mrstudios.punish.storage.SyncRepo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Changes from "another server" on the same database reach the players here (DESIGN.md 9.4): a
 * second {@link PunishmentService} without this server's listeners stands in for the other server,
 * and its events are written as another server's. The plugin's own poller applies them.
 */
class PluginSyncTest {

    private static final String OTHER = "lobby#0badf00d";

    private ServerMock server;
    private MRPunish plugin;
    private PlayerMock steve;
    private PlayerMock alex;
    private PlayerMock mod;
    private PunishmentService other;
    private final Requests.Actor console = Requests.Actor.console("Console");

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PluginMuteTest.WithAliases.class);
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
        mod = server.addPlayer("Mod");
        mod.addAttachment(plugin, "mrpunish.mod", true);
        Logger log = Logger.getAnonymousLogger();
        other = new PunishmentService(plugin.storage(), plugin::state, OfflineRanks.NONE, Runnable::run,
                new Notifier(List::of, log), System::currentTimeMillis, log);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void until(BooleanSupplier done) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) {
            server.getScheduler().performOneTick();
            Thread.sleep(10);
        }
    }

    private <T> T await(CompletableFuture<T> f) throws Exception {
        until(f::isDone);
        return f.get(1, TimeUnit.SECONDS);
    }

    private void poll() throws Exception {
        await(plugin.sync().poll());
    }

    private static List<String> drain(PlayerMock p) {
        List<String> lines = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(c));
        }
        return lines;
    }

    private void event(String kind, UUID target, Long ref) throws Exception {
        await(plugin.storage().executor().submit("test", c -> {
            new SyncRepo(plugin.storage().database(), true).insertAs(c, System.currentTimeMillis(), OTHER, kind, target,
                    null, ref);
            return null;
        }));
    }

    /** Issued "on the other server": committed, but nothing applied here yet. */
    private Punishment issue(PlayerMock target, String reason) throws Exception {
        Requests.IssueResult r = await(other.issue(new Requests.Issue(console,
                Requests.Target.offline(target.getUniqueId(), target.getName()),
                plugin.state().settings().reasons().byKey(reason).orElseThrow(), null, false,
                Punishment.Origin.COMMAND, null)));
        return ((Requests.IssueResult.Committed) r).punishment();
    }

    @Test
    void aBanFromAnotherServerKicksThePlayerHere() throws Exception {
        poll();
        Punishment ban = issue(steve, "hacking");
        assertTrue(steve.isOnline(), "the other server can't kick them");
        event(SyncRepo.PUNISH, steve.getUniqueId(), ban.id());
        poll();
        assertFalse(steve.isOnline());
        assertTrue(plugin.bans().cache().get(steve.getUniqueId(), System.currentTimeMillis()).isPresent());

        await(other.remove(new Requests.Remove(console, Requests.Target.offline(steve.getUniqueId(), "Steve"),
                PunishmentType.BAN, ban.id(), "appeal")));
        event(SyncRepo.REMOVE, steve.getUniqueId(), ban.id());
        poll();
        assertTrue(plugin.bans().cache().get(steve.getUniqueId(), System.currentTimeMillis()).isEmpty());
    }

    @Test
    void aMuteFromAnotherServerAppliesHereAndItsRemovalLiftsIt() throws Exception {
        poll();
        Punishment mute = issue(alex, "spam");
        assertTrue(plugin.mutes().muteOf(alex).isEmpty());
        drain(alex);
        event(SyncRepo.PUNISH, alex.getUniqueId(), mute.id());
        poll();
        assertTrue(plugin.mutes().muteOf(alex).isPresent());
        assertTrue(drain(alex).stream().anyMatch(l -> l.contains("muted")));

        await(other.remove(new Requests.Remove(console, Requests.Target.offline(alex.getUniqueId(), "Alex"),
                PunishmentType.MUTE, mute.id(), "mistake")));
        event(SyncRepo.REMOVE, alex.getUniqueId(), mute.id());
        poll();
        assertTrue(plugin.mutes().muteOf(alex).isEmpty());
        assertTrue(drain(alex).stream().anyMatch(l -> l.contains("no longer muted")));
    }

    @Test
    void aWarningFromAnotherServerIsShownNowAndOnlyOnce() throws Exception {
        poll();
        Punishment warn = issue(alex, "spam-warn");
        drain(alex);
        event(SyncRepo.PUNISH, alex.getUniqueId(), warn.id());
        event(SyncRepo.PUNISH, alex.getUniqueId(), warn.id());
        poll();
        List<String> got = new ArrayList<>();
        // Delivered from a database task: wait until it's shown, then a little more for a second copy.
        until(() -> {
            got.addAll(drain(alex));
            return got.stream().anyMatch(l -> l.contains("warned"));
        });
        for (int i = 0; i < 20; i++) {
            server.getScheduler().performOneTick();
            Thread.sleep(10);
        }
        got.addAll(drain(alex));
        assertEquals(1, got.stream().filter(l -> l.contains("warned")).count(), got.toString());
    }

    @Test
    void freezesAndUnfreezesFromAnotherServer() throws Exception {
        poll();
        FrozenRepo repo = new FrozenRepo(plugin.storage().database());
        await(plugin.storage().executor().submit("t", c -> repo.insert(c, new FrozenRepo.Frozen(alex.getUniqueId(),
                "Alex", mod.getUniqueId(), "Mod", 20, System.currentTimeMillis(), "lobby"))));
        event(SyncRepo.FREEZE, alex.getUniqueId(), null);
        poll();
        assertTrue(plugin.freeze().isFrozen(alex.getUniqueId()));
        await(plugin.storage().executor().submit("t", c -> repo.delete(c, alex.getUniqueId())));
        event(SyncRepo.UNFREEZE, alex.getUniqueId(), null);
        drain(alex);
        poll();
        assertFalse(plugin.freeze().isFrozen(alex.getUniqueId()));
        assertTrue(drain(alex).stream().anyMatch(l -> l.contains("no longer frozen")));
    }

    @Test
    void aReportFromAnotherServerIsAnnouncedToStaffHere() throws Exception {
        poll();
        long id = await(plugin.storage().executor().submit("t", c -> new ReportRepo(plugin.storage().database())
                .insert(c, new Report(0, UUID.randomUUID(), "Faraway", steve.getUniqueId(), "Steve", null,
                        "flying around", "lobby", "world", 1, 64, -3, System.currentTimeMillis(), Report.Status.OPEN,
                        null, null, null, null, null, null, null, 0))));
        drain(mod);
        drain(alex);
        event(SyncRepo.REPORT, steve.getUniqueId(), id);
        poll();
        List<String> heard = drain(mod);
        assertTrue(heard.stream().anyMatch(l -> l.contains("Faraway") && l.contains("Steve") && l.contains("#" + id)),
                heard.toString());
        assertTrue(drain(alex).stream().noneMatch(l -> l.contains("Faraway")), "players aren't told");
    }

    @Test
    void sqliteServersDontRunThePoller() {
        assertFalse(plugin.sync().active(), "SQLite is one server");
    }
}
