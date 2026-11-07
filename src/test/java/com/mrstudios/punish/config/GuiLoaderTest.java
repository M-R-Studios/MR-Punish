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
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
    void unknownKeysWarn() {
        LoadResult<GuiSettings> gui = new GuiLoader().load("gui-version: 1\nfiller: { material: STONE }\n");
        assertEquals(List.of("filler is not a known setting (typo?), ignored"), gui.warnings());
        LoadResult<ScoreboardSettings> scoreboard = new ScoreboardLoader().load("scoreboard-version: 1\ntitle: x\n");
        assertEquals(List.of("title is not a known setting (typo?), ignored"), scoreboard.warnings());
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
