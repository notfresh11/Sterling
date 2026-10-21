package net.sterling.bridge;

import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.util.HashUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigurationAndHashTest {

    @Test
    public void testConfigSaveAndLoad(@TempDir Path tempDir) {
        Path configPath = tempDir.resolve("sterling.yml");

        SterlingConfig config = SterlingConfig.load(configPath);
        assertNotNull(config);
        assertEquals("Sterling Modded Server", config.getServerName());
        assertEquals(25565, config.getServerPort());

        config.setServerName("Custom Test Server");
        config.setServerPort(25575);
        config.setExcludedMods(List.of("server-only.jar"));
        config.setModpack(true);
        config.setModpackDownloadLink("https://cdn.modrinth.com/data/abc/versions/1/pack.mrpack");
        config.save(configPath);

        SterlingConfig reloaded = SterlingConfig.load(configPath);
        assertEquals("Custom Test Server", reloaded.getServerName());
        assertEquals(25575, reloaded.getServerPort());
        assertEquals(List.of("server-only.jar"), reloaded.getExcludedMods());
        assertTrue(reloaded.isModpack());
        assertEquals("https://cdn.modrinth.com/data/abc/versions/1/pack.mrpack",
                reloaded.getModpackDownloadLink());
        assertDoesNotThrow(reloaded::validate);
    }

    @Test
    public void testSHA256Calculation(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello Sterling Bridge 2!");

        String hash = HashUtils.calculateSHA256(testFile);
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    public void testLegacyKickConfigIsLoaded(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("sterling.yml");
        Files.writeString(configPath, """
                kick-message: "Custom message: {download_url} {sterling_uri}"
                launcher-download-url: "https://example.com/launcher"
                enable-http-server: false
                excluded-mods:
                  - server-only.jar
                """, StandardCharsets.UTF_8);

        SterlingConfig config = SterlingConfig.load(configPath);

        assertEquals("Custom message: {download_url} {sterling_uri}", config.getKickMessage());
        assertEquals("https://example.com/launcher", config.getLauncherDownloadUrl());
        assertFalse(config.isEnableHttpServer());
        assertEquals(List.of("server-only.jar"), config.getExcludedMods());
    }

    @Test
    public void rejectsUnsupportedCurseForgeModpackLink() {
        SterlingConfig config = new SterlingConfig();
        config.setModpack(true);
        config.setModpackDownloadLink("https://www.curseforge.com/minecraft/modpacks/example/download/123");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("CurseForge modpacks are not supported yet"));
    }
}
