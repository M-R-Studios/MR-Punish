package com.mrstudios.punish.hook;

import com.mrstudios.punish.gui.BedrockForms;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Bedrock forms through Floodgate (DESIGN.md 12.2). Only loaded when Floodgate is installed and
 * {@code bedrock.floodgate} is on, so the plugin runs without it.
 */
public final class FloodgateHook implements BedrockForms {

    private final FloodgateApi api = FloodgateApi.getInstance();

    @Override
    public boolean isBedrock(UUID uuid) {
        return api.isFloodgatePlayer(uuid);
    }

    @Override
    public void askText(Player player, String title, String label, String current, Consumer<String> submitted,
            Runnable closed) {
        CustomForm form = CustomForm.builder()
                .title(title)
                .input(label, "", current == null ? "" : current)
                .validResultHandler(response -> {
                    String text = response.asInput(0);
                    submitted.accept(text == null ? "" : text);
                })
                .closedOrInvalidResultHandler(closed)
                .build();
        if (!api.sendForm(player.getUniqueId(), form)) {
            closed.run();
        }
    }
}
