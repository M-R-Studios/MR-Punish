package com.mrstudios.punish.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** gui.yml and scoreboard.yml: only their version for now, so the checks are the same. */
class GuiLoaderTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = GuiLoaderTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(String.valueOf((char) 13), "");
        }
    }

    @Test
    void shippedFilesLoadWithoutWarnings() throws Exception {
        LoadResult<GuiSettings> gui = new GuiLoader().load(resource("gui.yml"));
        LoadResult<ScoreboardSettings> scoreboard = new ScoreboardLoader().load(resource("scoreboard.yml"));
        assertTrue(gui.ok(), gui.error());
        assertTrue(scoreboard.ok(), scoreboard.error());
        assertEquals(List.of(), gui.warnings());
        assertEquals(List.of(), scoreboard.warnings());
    }

    @Test
    void unknownKeysWarn() throws Exception {
        LoadResult<GuiSettings> gui = new GuiLoader().load(resource("gui.yml") + "\nfillr: { material: STONE }\n");
        assertEquals(List.of("fillr is not a known setting (typo?), ignored"), gui.warnings());
        LoadResult<ScoreboardSettings> scoreboard = new ScoreboardLoader().load("scoreboard-version: 1\ntitle: x\n");
        assertEquals(List.of("title is not a known setting (typo?), ignored"), scoreboard.warnings());
    }

    @Test
    void badValuesFallBackToTheJarsWithAWarning() throws Exception {
        String text = resource("gui.yml")
                .replace("  mode: GREY", "  mode: SHOW")
                .replace("  close-slot: 40", "  close-slot: 50")
                .replace("  item: { material: GRAY_DYE,", "  item: { material: NOT_AN_ITEM,");
        LoadResult<GuiSettings> result = new GuiLoader(m -> !m.equals("NOT_AN_ITEM"), k -> true,
                GuiLoader.bundledDefaults()).load(text);
        assertTrue(result.ok(), result.error());
        GuiSettings gui = result.value();
        assertEquals(GuiSettings.NoPermissionMode.GREY, gui.noPermissionMode());
        assertEquals(40, gui.number("punish-menu.close-slot"));
        assertEquals("GRAY_DYE", gui.item("no-permission.item").material());
        assertEquals(3, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().contains("no-permission.mode: 'SHOW' is not GREY or HIDE, using GREY"));
    }

    @Test
    void hideModeAndCustomItems() throws Exception {
        String text = resource("gui.yml").replace("  mode: GREY", "  mode: hide")
                .replace("when-on:  { material: ENDER_EYE,", "when-on:  { material: LIME_DYE, glint: true,");
        GuiSettings gui = new GuiLoader().load(text).value();
        assertEquals(GuiSettings.NoPermissionMode.HIDE, gui.noPermissionMode());
        assertEquals("LIME_DYE", gui.item("confirm-menu.silent.when-on").material());
        assertTrue(gui.item("confirm-menu.silent.when-on").glint());
    }

    @Test
    void yamlErrorsAndNewerVersionsFail() {
        LoadResult<GuiSettings> broken = new GuiLoader().load("gui-version: 1\n  bad: [\n");
        assertFalse(broken.ok());
        assertTrue(broken.error().startsWith("gui.yml line "), broken.error());
        LoadResult<ScoreboardSettings> newer = new ScoreboardLoader().load("scoreboard-version: 9\n");
        assertFalse(newer.ok());
        assertTrue(newer.error().contains("newer MR-Punish"), newer.error());
    }
}
