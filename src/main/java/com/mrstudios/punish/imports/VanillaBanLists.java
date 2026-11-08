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

    /** The vanilla IP ban entries. */
    @SuppressWarnings({"deprecation", "unchecked"})
    public static java.util.Set<org.bukkit.BanEntry<?>> ips(Server server) {
        return (java.util.Set<org.bukkit.BanEntry<?>>) (java.util.Set<?>) server.getBanList(BanList.Type.IP)
                .getEntries();
    }

    /**
     * The address an IP ban entry is for: an {@link java.net.InetAddress}, or an IP written as text
     * (older API). Text is only parsed as a literal, never looked up, and never logged.
     */
    public static java.net.InetAddress address(Object target) {
        if (target instanceof java.net.InetAddress address) {
            return address;
        }
        if (target instanceof String text && com.google.common.net.InetAddresses.isInetAddress(text)) {
            return com.google.common.net.InetAddresses.forString(text);
        }
        return null;
    }

    /** Pardons the vanilla IP bans whose address hashes to one of {@code hashes}; returns how many. */
    @SuppressWarnings({"deprecation", "unchecked", "rawtypes"})
    public static int pardonIps(Server server, java.util.Collection<String> hashes,
            com.mrstudios.punish.ip.IpFeatures ip) {
        if (hashes.isEmpty()) {
            return 0;
        }
        BanList list = server.getBanList(BanList.Type.IP);
        int pardoned = 0;
        for (Object e : java.util.List.copyOf(list.getEntries())) {
            org.bukkit.BanEntry<?> entry = (org.bukkit.BanEntry<?>) e;
            java.net.InetAddress address = address(entry.getBanTarget());
            if (address != null && ip.hash(address).filter(hashes::contains).isPresent()) {
                entry.remove();
                pardoned++;
            }
        }
        return pardoned;
    }
}
