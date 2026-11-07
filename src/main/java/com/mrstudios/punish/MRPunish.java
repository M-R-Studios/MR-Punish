package com.mrstudios.punish;

import org.bukkit.plugin.java.JavaPlugin;

// Not final: MockBukkit subclasses the plugin class in tests.
public class MRPunish extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("MR-Punish " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
    }
}
