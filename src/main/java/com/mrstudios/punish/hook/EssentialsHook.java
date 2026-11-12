package com.mrstudios.punish.hook;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Tells EssentialsX when MR-Punish vanishes someone (DESIGN.md 11.2). EssentialsX doesn't read the
 * {@code vanished} metadata: its {@code /list}, AFK messages and the like use its own "hidden" flag,
 * set here with {@code User#setHidden}. Called by reflection, so MR-Punish has no compile-time
 * dependency on EssentialsX and runs the same without it; if the API ever changes, the hook turns
 * itself off with one warning instead of failing vanish.
 */
public final class EssentialsHook {

    private final Plugin essentials;
    private final Logger log;
    private Method getUser;
    private Method setHidden;
    private boolean broken;

    private EssentialsHook(Plugin essentials, Logger log) {
        this.essentials = essentials;
        this.log = log;
    }

    /** The hook if EssentialsX is enabled, else null. */
    public static EssentialsHook find(org.bukkit.plugin.PluginManager plugins, Logger log) {
        Plugin essentials = plugins.getPlugin("Essentials");
        if (essentials == null || !essentials.isEnabled()) {
            return null;
        }
        EssentialsHook hook = new EssentialsHook(essentials, log);
        return hook.resolve() ? hook : null;
    }

    private boolean resolve() {
        try {
            getUser = essentials.getClass().getMethod("getUser", Player.class);
            setHidden = getUser.getReturnType().getMethod("setHidden", boolean.class);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("EssentialsX is installed but its API isn't the expected one (" + e
                    + "), so Essentials' /list and AFK messages may still show vanished players.");
            return false;
        }
    }

    /** Hides {@code player} from Essentials' lists and messages, or shows them again. Main thread. */
    public void setHidden(Player player, boolean hidden) {
        if (broken || !essentials.isEnabled()) {
            return;
        }
        try {
            Object user = getUser.invoke(essentials, player);
            if (user != null) {
                setHidden.invoke(user, hidden);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            log.warning("Couldn't tell EssentialsX that " + player.getName() + " is " + (hidden ? "" : "no longer ")
                    + "vanished (" + e + "); the EssentialsX hook is off until the next restart.");
        }
    }
}
