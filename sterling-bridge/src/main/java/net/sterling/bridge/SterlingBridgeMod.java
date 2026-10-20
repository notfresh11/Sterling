package net.sterling.bridge;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.sterling.bridge.command.SterlingCommand;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.handler.PlayerJoinHandler;
import net.sterling.bridge.http.EmbeddedHttpServer;
import net.sterling.bridge.manifest.ManifestGenerator;
import net.sterling.bridge.service.ModrinthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class SterlingBridgeMod implements ModInitializer {
    public static final String MOD_ID = "sterling-bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private SterlingConfig config;
    private EmbeddedHttpServer httpServer;
    private ManifestGenerator manifestGenerator;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Sterling's Bridge Mod (Version 26.3 / Fabric)...");

        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path configDir = gameDir.resolve("config").resolve("sterling");
        Path configPath = configDir.resolve("sterling.yml");
        Path modsDir = gameDir.resolve("mods");
        Path manifestPath = configDir.resolve("manifest.json");

        // Load Configuration
        config = SterlingConfig.load(configPath);

        // Modrinth API lookup service
        ModrinthService modrinthService = new ModrinthService();

        // Manifest Generator
        manifestGenerator = new ManifestGenerator(modsDir, manifestPath, config, modrinthService);
        manifestGenerator.generateAndSaveManifest();

        // Embedded HTTP Server
        if (config.isEnableHttpServer()) {
            httpServer = new EmbeddedHttpServer(config.getHttpPort(), manifestPath);
            httpServer.start();
        }

        // Event Handlers
        PlayerJoinHandler joinHandler = new PlayerJoinHandler(config);
        joinHandler.register();

        // Admin Command (/sterling reload, /sterling sync, /sterling status)
        SterlingCommand command = new SterlingCommand(config, manifestGenerator, configPath);
        command.register();

        LOGGER.info("Sterling's Bridge Mod successfully initialized!");
    }
}
