package com.mrstudios.punish.punish;

import com.mrstudios.punish.config.TimeSpan;
import java.util.List;

/**
 * One punishment reason from {@code config.yml}. Its key is stored with every punishment given for
 * it, so escalation counts by key.
 *
 * @param key            unique ID, lower case
 * @param display        name shown to players (MiniMessage)
 * @param description    lore lines for the reason menu (MiniMessage)
 * @param icon           item material name for the reason menu
 * @param slot           fixed slot in the reason menu, or -1 to fill the free slots in order
 * @param aliases        other words that pick this reason, lower case (may include words another
 *                       reason won; {@link ReasonRegistry} decides who gets each word)
 * @param escalation     duration of the 1st, 2nd, 3rd... offence; empty for warnings
 * @param historyPeriod  overrides {@code escalation.history-period}, or null
 * @param permission     extra permission needed to use the reason, or null
 * @param enabled        false hides the reason from menus and commands
 */
public record Reason(
        String key,
        PunishmentType type,
        String display,
        List<String> description,
        String icon,
        int slot,
        List<String> aliases,
        List<TimeSpan> escalation,
        TimeSpan historyPeriod,
        String permission,
        boolean enabled) {

    public Reason {
        description = List.copyOf(description);
        aliases = List.copyOf(aliases);
        escalation = List.copyOf(escalation);
    }

    /** How far back earlier offences count: the reason's own period, else {@code global}. */
    public TimeSpan period(TimeSpan global) {
        return historyPeriod != null ? historyPeriod : global;
    }
}
