package com.mrstudios.punish.gui;

import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** Text input through a Bedrock form (DESIGN.md 12.2). {@link #NONE} when Floodgate isn't used. */
public interface BedrockForms {

    BedrockForms NONE = new BedrockForms() {
        @Override
        public boolean isBedrock(UUID uuid) {
            return false;
        }

        @Override
        public void askText(Player player, String title, String label, String current, Consumer<String> submitted,
                Runnable closed) {
            closed.run();
        }
    };

    boolean isBedrock(UUID uuid);

    /**
     * Shows a form with one text field. Exactly one of the callbacks runs, on any thread: the
     * caller hops to the main thread.
     */
    void askText(Player player, String title, String label, String current, Consumer<String> submitted,
            Runnable closed);
}
