package com.mrstudios.punish.imports;

import org.bukkit.BanList;
import org.bukkit.Server;
import org.bukkit.ban.ProfileBanList;

/**
 * Access to vanilla's ban lists. Uses {@code getBanList(BanList.Type)}: deprecated in favour of
 * {@code BanListType}, but it returns the same {@link ProfileBanList} on Paper and, unlike the newer
 * method, is also implemented by MockBukkit, so the import and startup hint are covered by tests.
 */
public final class VanillaBanLists {

    private VanillaBanLists() {
    }

    @SuppressWarnings("deprecation")
    public static ProfileBanList profiles(Server server) {
        return (ProfileBanList) server.getBanList(BanList.Type.PROFILE);
    }

    @SuppressWarnings("deprecation")
    public static int ipCount(Server server) {
        return server.getBanList(BanList.Type.IP).getEntries().size();
    }
}
