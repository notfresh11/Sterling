package net.sterling.bridge.command;

import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.manifest.ManifestGenerator;
import net.sterling.bridge.manifest.ManifestModel;
import net.sterling.bridge.service.ModrinthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SterlingCommandTest {

    @Test
    void testSterlingCommandExecution(@TempDir Path tempDir) {
        Path configPath = tempDir.resolve("sterling.yml");
        Path modsDir = tempDir.resolve("mods");
        Path manifestPath = tempDir.resolve("manifest.json");

        SterlingConfig config = SterlingConfig.load(configPath);
        ModrinthService modrinthService = new ModrinthService();
        ManifestGenerator generator = new ManifestGenerator(modsDir, manifestPath, config, modrinthService);

        SterlingCommand command = new SterlingCommand(config, generator, configPath);

        int reloadResult = command.executeReload(null);
        assertEquals(1, reloadResult);

        int syncResult = command.executeSync(null);
        assertEquals(1, syncResult);

        int statusResult = command.executeStatus(null);
        assertEquals(1, statusResult);
    }
}
