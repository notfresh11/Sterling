package net.sterling.bridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.manifest.ManifestGenerator;
import net.sterling.bridge.manifest.ManifestModel;
import net.sterling.bridge.util.TextHelper;

import java.nio.file.Path;

public class SterlingCommand {
    private final SterlingConfig config;
    private final ManifestGenerator manifestGenerator;
    private final Path configPath;

    public SterlingCommand(SterlingConfig config, ManifestGenerator manifestGenerator, Path configPath) {
        this.config = config;
        this.manifestGenerator = manifestGenerator;
        this.configPath = configPath;
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    CommandManager.literal("sterling")
                            .requires(source -> source.hasPermissionLevel(2)) // Require admin/op
                            .then(CommandManager.literal("reload")
                                    .executes(this::executeReload))
                            .then(CommandManager.literal("sync")
                                    .executes(this::executeSync))
                            .then(CommandManager.literal("status")
                                    .executes(this::executeStatus))
            );
        });
    }

    public int executeReload(CommandContext<ServerCommandSource> context) {
        SterlingConfig freshConfig = SterlingConfig.load(configPath);
        ManifestModel manifest = manifestGenerator.generateAndSaveManifest();

        if (context != null && context.getSource() != null) {
            TextHelper.sendFeedback(context.getSource(), "§a[Sterling] Config reloaded and manifest regenerated successfully!");
        }
        return 1;
    }

    public int executeSync(CommandContext<ServerCommandSource> context) {
        ManifestModel manifest = manifestGenerator.generateAndSaveManifest();
        int modCount = manifest.getMods().size();

        if (context != null && context.getSource() != null) {
            TextHelper.sendFeedback(context.getSource(), "§a[Sterling] Scanned and synced " + modCount + " mods with Modrinth API.");
        }
        return 1;
    }

    public int executeStatus(CommandContext<ServerCommandSource> context) {
        if (context != null && context.getSource() != null) {
            boolean httpActive = config.isEnableHttpServer();
            int httpPort = config.getHttpPort();
            int modSourcesCount = config.getModSources().size();

            TextHelper.sendFeedback(context.getSource(), "§b--- Sterling's Bridge Status ---");
            TextHelper.sendFeedback(context.getSource(), "§7HTTP Embedded Server: " + (httpActive ? "§aActive (Port " + httpPort + ")" : "§cDisabled"));
            TextHelper.sendFeedback(context.getSource(), "§7Mapped Mod Sources: §e" + modSourcesCount);
            TextHelper.sendFeedback(context.getSource(), "§7Minecraft Version: §a26.3 / Fabric");
        }
        return 1;
    }
}
