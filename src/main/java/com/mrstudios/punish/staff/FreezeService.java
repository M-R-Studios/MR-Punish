package com.mrstudios.punish.staff;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.enforce.CommandLabelResolver;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.Reason;
import com.mrstudios.punish.punish.Requests;
import com.mrstudios.punish.punish.Requests.IssueResult;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.SyncRepo;
import com.mrstudios.punish.storage.FrozenRepo;
import com.mrstudios.punish.storage.FrozenRepo.Frozen;
import com.mrstudios.punish.storage.StorageException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Freeze (DESIGN.md 11.3). The {@code frozen} row is the truth: it is written (committed) before the
 * effect is applied and deleted before the effect is lifted, so a freeze survives logout, restart
 * and a server switch, and {@code /unfreeze} works offline. The effect is in memory only: on plugin
 * disable it is lifted (flight restored), and enable re-applies it from the rows, so nobody stays
 * frozen without a row and no row is ever left without its effect.
 */
public final class FreezeService implements Listener {

    public static final String PERMISSION = "mrpunish.freeze";
    private static final String NOTIFY = "mrpunish.notify";
    private static final long BLOCKED_NOTICE_MS = 2000;

    /** A frozen player's effect: what to restore, and where they stand. */
    private static final class Effect {

        final Frozen record;
        final boolean allowFlight;
        final boolean flying;
        Location at;
        long seconds;
        long lastNotice;

        Effect(Frozen record, boolean allowFlight, boolean flying, Location at) {
            this.record = record;
            this.allowFlight = allowFlight;
            this.flying = flying;
            this.at = at;
        }
    }

    public sealed interface Outcome {

        record Done(Frozen frozen) implements Outcome {
        }

        record Already() implements Outcome {
        }

        record NotFrozen() implements Outcome {
        }

        record Refused(Authority.Verdict verdict) implements Outcome {
        }

        record Unavailable() implements Outcome {
        }

        record Failed(String errorId) implements Outcome {
        }
    }

    private final MRPunish plugin;
    private final Map<UUID, Effect> frozen = new ConcurrentHashMap<>();
    /** Rows found at pre-login, applied at join. */
    private final Map<UUID, Frozen> pending = new ConcurrentHashMap<>();

    public FreezeService(MRPunish plugin) {
        this.plugin = plugin;
    }

    private PluginState state() {
        return plugin.state();
    }

    private Settings.Freeze settings() {
        return state().settings().freeze();
    }

    private FrozenRepo repo() {
        return new FrozenRepo(plugin.storage().database());
    }

    private ActionRepo actions() {
        return new ActionRepo(plugin.storage().database());
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.containsKey(uuid);
    }

    public int count() {
        return frozen.size();
    }

    private boolean down() {
        return plugin.storage().executor().isShutDown() || !plugin.storage().health().isUp();
    }

    private void toStaff(String key, TagResolver... placeholders) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.hasPermission(NOTIFY)) {
                state().messages().send(p, key, placeholders);
            }
        }
        state().messages().send(plugin.getServer().getConsoleSender(), key, placeholders);
    }

    // ---- Freeze and unfreeze ----

    /**
     * Freezes an online player (main thread): permission and authority read now, the row committed,
     * then the effect applied. The future completes on the main thread.
     */
    public CompletableFuture<Outcome> freeze(CommandSender sender, Authority.Standing actor, Player target) {
        Authority.Verdict verdict = Authority.check(actor, plugin.rankCache().standing(target), Authority.Action.FREEZE);
        if (verdict != Authority.Verdict.OK) {
            return CompletableFuture.completedFuture(new Outcome.Refused(verdict));
        }
        if (down()) {
            return CompletableFuture.completedFuture(new Outcome.Unavailable());
        }
        long now = System.currentTimeMillis();
        String server = state().settings().effectiveServerName();
        Frozen row = new Frozen(target.getUniqueId(), target.getName(), actor.uuid(), actor.name(),
                actor.isConsole() ? Integer.MAX_VALUE : actor.weight(), now, server);
        return plugin.storage().executor().transaction("freezing " + target.getName(), c -> {
            if (!repo().insert(c, row)) {
                return (Outcome) new Outcome.Already();
            }
            actions().insert(c, new ActionRepo.Action(now, server, actor.uuid(), actor.name(), "FREEZE",
                    target.getUniqueId(), target.getName(), null, null));
            new SyncRepo(plugin.storage().database()).insert(c, now, server, SyncRepo.FREEZE, target.getUniqueId(),
                    null, null);
            return new Outcome.Done(row);
        }).handle((r, e) -> e == null ? r : failure(e, "freezing " + target.getName()))
                .thenApplyAsync(r -> {
                    if (r instanceof Outcome.Done d && target.isOnline()) {
                        apply(target, d.frozen(), true);
                        toStaff("notify.froze", Placeholder.unparsed("staff", actor.name()),
                                Placeholder.unparsed("player", target.getName()));
                    }
                    return r;
                }, task -> plugin.getServer().getScheduler().runTask(plugin, task));
    }

    /** Unfreezes (online or offline): the row deleted, then the effect lifted. No authority needed. */
    public CompletableFuture<Outcome> unfreeze(Authority.Standing actor, UUID uuid, String name) {
        if (down()) {
            return CompletableFuture.completedFuture(new Outcome.Unavailable());
        }
        long now = System.currentTimeMillis();
        String server = state().settings().effectiveServerName();
        return plugin.storage().executor().transaction("unfreezing " + name, c -> {
            Optional<Frozen> row = repo().get(c, uuid);
            if (row.isEmpty() || !repo().delete(c, uuid)) {
                return (Outcome) new Outcome.NotFrozen();
            }
            actions().insert(c, new ActionRepo.Action(now, server, actor.uuid(), actor.name(), "UNFREEZE", uuid, name,
                    null, "frozen by " + row.get().byName()));
            new SyncRepo(plugin.storage().database()).insert(c, now, server, SyncRepo.UNFREEZE, uuid, null, null);
            return new Outcome.Done(row.get());
        }).handle((r, e) -> e == null ? r : failure(e, "unfreezing " + name))
                .thenApplyAsync(r -> {
                    if (r instanceof Outcome.Done) {
                        Player online = plugin.getServer().getPlayer(uuid);
                        if (online != null) {
                            lift(online);
                            state().messages().send(online, "staff.unfrozen");
                        }
                        pending.remove(uuid);
                        toStaff("notify.unfroze", Placeholder.unparsed("staff", actor.name()),
                                Placeholder.unparsed("player", name));
                    }
                    return r;
                }, task -> plugin.getServer().getScheduler().runTask(plugin, task));
    }

    private Outcome failure(Throwable e, String what) {
        Throwable t = e;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof StorageException se && se.errorId() != null) {
            return new Outcome.Failed(se.errorId());
        }
        if (t instanceof StorageException) {
            return new Outcome.Unavailable();
        }
        String id = String.format("%08x", java.util.concurrent.ThreadLocalRandom.current().nextInt());
        plugin.getLogger().log(Level.SEVERE, "Error " + id + " while " + what, t);
        return new Outcome.Failed(id);
    }

    // ---- The effect ----

    /**
     * Another server sharing the database froze this player (DESIGN.md 9.4): the effect, if
     * they're here. {@code row} was read from the database.
     */
    public void remoteFrozen(Frozen row) {
        Player p = plugin.getServer().getPlayer(row.uuid());
        if (p != null) {
            apply(p, row, true);
        }
    }

    /** Another server unfroze this player (their row is gone): the effect lifted if they're here. */
    public void remoteUnfrozen(UUID uuid) {
        pending.remove(uuid);
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null && isFrozen(uuid)) {
            lift(p);
            state().messages().send(p, "staff.unfrozen");
        }
    }

    /** Applies the effect (main thread). {@code tell} sends the frozen message, title and sound. */
    void apply(Player p, Frozen row, boolean tell) {
        if (frozen.containsKey(p.getUniqueId())) {
            return;
        }
        Effect effect = new Effect(row, p.getAllowFlight(), p.isFlying(), p.getLocation());
        frozen.put(p.getUniqueId(), effect);
        if (p.isInsideVehicle()) {
            p.leaveVehicle();
        }
        if (!((Entity) p).isOnGround()) {
            // Mid-air: flying keeps the server from kicking them for it.
            p.setAllowFlight(true);
            p.setFlying(true);
        }
        if (p.isGliding()) {
            p.setGliding(false);
        }
        if (tell) {
            remind(p);
            Settings.SoundSetting sound = settings().sound();
            if (sound.enabled()) {
                p.playSound(Sound.sound(sound.sound(), Sound.Source.MASTER, sound.volume(), sound.pitch()));
            }
        }
    }

    private void remind(Player p) {
        state().messages().sendLines(p, "staff.frozen-chat");
        p.showTitle(Title.title(state().messages().component("staff.frozen-title"),
                state().messages().component("staff.frozen-subtitle"),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))));
    }

    /** Lifts the effect (main thread): flight restored, title cleared. The row is not touched. */
    void lift(Player p) {
        Effect effect = frozen.remove(p.getUniqueId());
        if (effect == null) {
            return;
        }
        p.setFlying(false);
        p.setAllowFlight(effect.allowFlight);
        if (effect.allowFlight && effect.flying) {
            p.setFlying(true);
        }
        p.clearTitle();
    }

    // ---- Join, quit, lifecycle ----

    /** Looks the row up before the player joins (async), so the effect is on from the first tick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || down()) {
            return;
        }
        UUID uuid = event.getUniqueId();
        try {
            plugin.storage().executor().submit("checking whether " + event.getName() + " is frozen",
                    c -> repo().get(c, uuid)).get(3, TimeUnit.SECONDS).ifPresent(row -> pending.put(uuid, row));
        } catch (Exception e) {
            // Checked again after the join.
            plugin.getLogger().warning("Couldn't check whether " + event.getName() + " is frozen before they joined ("
                    + e + "); checking after the join.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Frozen row = pending.remove(p.getUniqueId());
        if (row != null) {
            joinedFrozen(p, row);
            return;
        }
        reload(p, true);
    }

    private void joinedFrozen(Player p, Frozen row) {
        apply(p, row, true);
        toStaff("notify.freeze-join", Placeholder.unparsed("player", p.getName()),
                Placeholder.unparsed("staff", row.byName()));
    }

    /** Reads the row of an online player and applies the effect if there is one. */
    private void reload(Player p, boolean join) {
        if (down()) {
            return;
        }
        UUID uuid = p.getUniqueId();
        plugin.storage().executor().submit("checking whether " + p.getName() + " is frozen", c -> repo().get(c, uuid))
                .thenAccept(row -> row.ifPresent(r -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (p.isOnline() && !isFrozen(uuid)) {
                        if (join) {
                            joinedFrozen(p, r);
                        } else {
                            apply(p, r, true);
                        }
                    }
                })));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Effect effect = frozen.get(p.getUniqueId());
        if (effect == null) {
            return;
        }
        lift(p); // the row stays: they're frozen again when they join
        toStaff("notify.freeze-quit", Placeholder.unparsed("player", p.getName()));
        long now = System.currentTimeMillis();
        String server = state().settings().effectiveServerName();
        plugin.storage().executor().submit("logging a freeze logout", c -> {
            actions().insert(c, new ActionRepo.Action(now, server, p.getUniqueId(), p.getName(), "FREEZE_QUIT",
                    p.getUniqueId(), p.getName(), null, "frozen by " + effect.record.byName()));
            return null;
        });
        if (settings().onQuit() == Settings.FreezeQuit.BAN) {
            banForLogout(p.getUniqueId(), p.getName(), effect.record);
        }
    }

    /**
     * {@code on-quit: BAN}: the freeze-logout reason through the normal pipeline, issued by the
     * freezer (live if online, else with the weight stored at the freeze). Refusals and failures are
     * logged and told to staff.
     */
    private void banForLogout(UUID uuid, String name, Frozen row) {
        Optional<Reason> reason = state().settings().reasons().byKey("freeze-logout");
        if (reason.isEmpty() || !reason.get().enabled()) {
            refusedBan(name, "the freeze-logout reason is missing or disabled in config.yml");
            return;
        }
        Requests.Actor actor;
        Player freezer = row.byUuid() == null ? null : plugin.getServer().getPlayer(row.byUuid());
        if (freezer != null) {
            actor = new Requests.Actor(plugin.rankCache().standing(freezer), freezer::hasPermission);
        } else if (row.byUuid() == null) {
            actor = Requests.Actor.console(row.byName() + " (freeze)");
        } else {
            actor = new Requests.Actor(new Authority.Standing(row.byUuid(), row.byName() + " (freeze)", row.byWeight(),
                    false), permission -> true);
        }
        Requests.Issue request = new Requests.Issue(actor, Requests.Target.offline(uuid, name), reason.get(), null, false,
                Punishment.Origin.FREEZE_QUIT, null);
        plugin.punishments().issue(request).thenAccept(result -> {
            switch (result) {
                case IssueResult.Committed c -> plugin.getLogger().info(name + " was banned for logging out while frozen"
                        + " (#" + c.punishment().id() + ").");
                case IssueResult.Refused r -> refusedBan(name, "refused (" + r.verdict() + ")");
                case IssueResult.WouldShorten w -> refusedBan(name, "an active ban (#" + w.blocking().id() + ") ends later");
                case IssueResult.NoPermission n -> refusedBan(name, "no permission (" + n.permission() + ")");
                case IssueResult.Failed f -> refusedBan(name, "the database failed (error " + f.errorId() + ")");
                case IssueResult.Unavailable u -> refusedBan(name, "the database is unavailable");
                default -> refusedBan(name, result.getClass().getSimpleName());
            }
        });
    }

    private void refusedBan(String name, String why) {
        plugin.getLogger().warning("Couldn't ban " + name + " for logging out while frozen: " + why);
        toStaff("notify.freeze-ban-failed", Placeholder.unparsed("player", name), Placeholder.unparsed("why", why));
    }

    /** Plugin enable with players online (after /reload): re-applies the effect from the rows. */
    public void enableOnline() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            reload(p, false);
        }
    }

    /** Plugin disable: the effect is lifted for everyone (flight restored); the rows stay. */
    public void disable() {
        for (UUID uuid : java.util.Set.copyOf(frozen.keySet())) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                lift(p);
            }
        }
        frozen.clear();
        pending.clear();
    }

    /** Every second: reminders, and the void rescue. */
    public void tick() {
        int every = settings().reminderSeconds();
        for (Map.Entry<UUID, Effect> entry : frozen.entrySet()) {
            Player p = plugin.getServer().getPlayer(entry.getKey());
            if (p == null) {
                continue;
            }
            Effect effect = entry.getValue();
            effect.seconds++;
            if (FreezeRules.reminderDue(effect.seconds, every)) {
                remind(p);
            }
            rescueFromVoid(p, effect);
        }
    }

    private void rescueFromVoid(Player p, Effect effect) {
        if (p.getLocation().getY() < p.getWorld().getMinHeight()) {
            p.teleport(effect.at, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    // ---- Blocks ----

    private Effect effect(Entity entity) {
        return entity instanceof Player p ? frozen.get(p.getUniqueId()) : null;
    }

    private void block(Cancellable event, Player p) {
        event.setCancelled(true);
        Effect effect = frozen.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (effect != null && now - effect.lastNotice >= BLOCKED_NOTICE_MS) {
            effect.lastNotice = now;
            state().messages().send(p, "staff.frozen-blocked");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        Effect effect = frozen.get(event.getPlayer().getUniqueId());
        if (effect == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (FreezeRules.moveBlocked(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ())) {
            // Snap back, keeping where they look.
            Location back = effect.at.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            event.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        Effect effect = frozen.get(event.getPlayer().getUniqueId());
        if (effect == null) {
            return;
        }
        if (FreezeRules.teleportAllowed(event.getCause())) {
            // Staff moved them (or our own rescue): they're frozen where they land.
            effect.at = event.getTo().clone();
            return;
        }
        block(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPortal(PlayerPortalEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            block(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicle(VehicleEnterEvent event) {
        if (effect(event.getEntered()) != null) {
            block(event, (Player) event.getEntered());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            block(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            block(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            block(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            block(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void onInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            block(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            block(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            block(event, event.getPlayer());
        }
    }

    /** Frozen players can't open containers; MR-Punish menus still open (they're view-only). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent event) {
        if (effect(event.getPlayer()) != null
                && com.mrstudios.punish.gui.MenuHolder.of(event.getInventory()) == null) {
            block(event, (Player) event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (effect(event.getWhoClicked()) != null) {
            block(event, (Player) event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (effect(event.getWhoClicked()) != null) {
            block(event, (Player) event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            block(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (effect(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            block(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            block(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLaunch(ProjectileLaunchEvent event) {
        ProjectileSource shooter = event.getEntity().getShooter();
        if (shooter instanceof Player p && isFrozen(p.getUniqueId())) {
            block(event, p);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && effect(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    /** No damage to a frozen player (they can't defend themselves); the void sends them back instead. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamaged(EntityDamageEvent event) {
        Effect effect = effect(event.getEntity());
        if (effect == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            event.getEntity().teleport(effect.at, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    /** No damage by a frozen player, in melee or with a projectile. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            damager = shooter;
        }
        if (damager instanceof Player p && isFrozen(p.getUniqueId())) {
            block(event, p);
        }
    }

    /**
     * Commands: only {@code freeze.allowed-commands} (by name or alias, resolved like mute's blocked
     * commands), plus staff chat for staff.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!isFrozen(p.getUniqueId())) {
            return;
        }
        CommandLabelResolver.Lookup lookup = plugin.commandLookup();
        if (CommandLabelResolver.blocked(event.getMessage(), settings().allowedCommands(), lookup)) {
            return;
        }
        if (p.hasPermission(StaffChat.PERMISSION)
                && CommandLabelResolver.blocked(event.getMessage(), java.util.Set.of("sc", "staffchat"), lookup)) {
            return;
        }
        block(event, p);
    }
}
