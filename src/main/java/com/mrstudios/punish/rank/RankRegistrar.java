package com.mrstudios.punish.rank;

import com.mrstudios.punish.config.RankSettings;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * Turns each rank into a Bukkit permission whose children are the rank's grants (its own and every
 * inherited rank's), so {@code hasPermission("mrpunish.ban")} is the only feature check the code
 * makes. A rank node declared in {@code plugin.yml} keeps that default (e.g. {@code op} for
 * {@code mrpunish.owner}); other rank nodes default to false.
 */
public final class RankRegistrar {

    private final PluginManager plugins;
    private final Map<String, Permission> declared;
    private final Supplier<Collection<? extends Player>> online;
    /** The rank nodes this registrar registered last time. */
    private final Set<String> registered = new HashSet<>();

    /**
     * @param declared the permissions in {@code plugin.yml}
     * @param online   the players to recalculate after a change
     */
    public RankRegistrar(PluginManager plugins, List<Permission> declared, Supplier<Collection<? extends Player>> online) {
        this.plugins = plugins;
        this.declared = new LinkedHashMap<>();
        for (Permission permission : declared) {
            this.declared.put(permission.getName().toLowerCase(java.util.Locale.ROOT), permission);
        }
        this.online = online;
    }

    /** Registers the ranks of {@code settings}, replacing what was registered before, and recalculates everyone. */
    public void apply(RankSettings settings) {
        Set<String> now = new HashSet<>();
        for (Rank rank : settings.ranks()) {
            now.add(rank.permission());
        }
        // A rank removed from permissions.yml: its node goes back to its plugin.yml form (or away).
        for (String old : registered) {
            if (!now.contains(old)) {
                plugins.removePermission(old);
                Permission original = declared.get(old);
                if (original != null) {
                    plugins.addPermission(copy(original, original.getChildren()));
                }
            }
        }
        for (Rank rank : settings.ranks()) {
            Permission original = declared.get(rank.permission());
            Map<String, Boolean> children = new LinkedHashMap<>();
            for (String grant : rank.allGrants()) {
                children.put(grant, true);
            }
            Permission permission = new Permission(rank.permission(),
                    original != null ? original.getDescription() : "MR-Punish rank " + rank.name() + ".",
                    original != null ? original.getDefault() : PermissionDefault.FALSE,
                    children);
            plugins.removePermission(rank.permission());
            plugins.addPermission(permission);
        }
        registered.clear();
        registered.addAll(now);
        for (Player player : online.get()) {
            player.recalculatePermissions();
        }
    }

    private static Permission copy(Permission original, Map<String, Boolean> children) {
        return new Permission(original.getName(), original.getDescription(), original.getDefault(),
                new LinkedHashMap<>(children));
    }
}
