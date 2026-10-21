package net.sterling.bridge;

import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.util.HashUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
        config.save(configPath);

        SterlingConfig reloaded = SterlingConfig.load(configPath);
        assertEquals("Custom Test Server", reloaded.getServerName());
        assertEquals(25575, reloaded.getServerPort());
    }

    @Test
    public void testSHA256Calculation(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello Sterling Bridge 2!");

        String hash = HashUtils.calculateSHA256(testFile);
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }
}
