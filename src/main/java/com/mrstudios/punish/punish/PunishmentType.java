package com.mrstudios.punish.punish;

/** The five kinds of punishment. */
public enum PunishmentType {
    WARN,
    MUTE,
    BAN,
    IP_MUTE,
    IP_BAN;

    /** Applies to an IP address rather than one account. */
    public boolean isIp() {
        return this == IP_MUTE || this == IP_BAN;
    }

    /** Has a length from the escalation ladder. Warnings don't. */
    public boolean hasDuration() {
        return this != WARN;
    }

    public boolean blocksLogin() {
        return this == BAN || this == IP_BAN;
    }

    public boolean blocksChat() {
        return this == MUTE || this == IP_MUTE;
    }
}
