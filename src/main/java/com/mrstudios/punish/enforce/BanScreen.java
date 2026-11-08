package com.mrstudios.punish.enforce;

import com.mrstudios.punish.config.DurationParser;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.punish.Punishment;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/** The screen a banned player sees when kicked or refused at login (DESIGN.md 7.8). */
public final class BanScreen {

    private BanScreen() {
    }

    public static Component of(Messages messages, Settings.Display display, Punishment ban, long now) {
        boolean permanent = ban.expiresAt() == null;
        List<Component> lines = messages.components(permanent ? "target.ban-screen-permanent" : "target.ban-screen",
                Placeholder.parsed("reason", ban.reasonDisplay()),
                Placeholder.unparsed("staff", ban.issuerName()),
                Placeholder.unparsed("expires", permanent ? "" : display.date(ban.expiresAt())),
                Placeholder.unparsed("remaining", permanent ? "" : DurationParser.format(ban.expiresAt() - now)),
                Placeholder.unparsed("id", String.valueOf(ban.id())),
                Placeholder.unparsed("appeal-link", display.appealLink()));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** {@code target.login-unverified}: the database couldn't be asked. */
    public static Component unverified(Messages messages) {
        return Component.join(JoinConfiguration.newlines(), messages.components("target.login-unverified"));
    }
}
