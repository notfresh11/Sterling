package net.sterling.bridge.config;

import net.sterling.bridge.util.HashUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationAndHashTest {

    @Test
    void testConfigSaveAndLoad(@TempDir Path tempDir) {
        Path configPath = tempDir.resolve("sterling.yml");
        SterlingConfig config = SterlingConfig.load(configPath);

        assertNotNull(config);
        assertEquals(8080, config.getHttpPort());
        assertTrue(config.isEnableHttpServer());

        config.getModSources().put("test-mod.jar", "https://modrinth.com/test");
        config.save(configPath);

        SterlingConfig reloaded = SterlingConfig.load(configPath);
        assertEquals("https://modrinth.com/test", reloaded.getModSources().get("test-mod.jar"));
    }

    @Test
    void testSHA256Calculation(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("sample.jar");
        Files.writeString(testFile, "Hello Sterling Bridge");

        String sha256 = HashUtils.calculateSHA256(testFile);
        assertNotNull(sha256);
        assertEquals(64, sha256.length()); // SHA-256 is 64 hex chars
    }
}
