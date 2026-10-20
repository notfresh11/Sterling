package net.sterling.bridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.manifest.ManifestGenerator;
import net.sterling.bridge.manifest.ManifestModel;

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
            context.getSource().sendFeedback(() -> Text.literal("§a[Sterling] Config reloaded and manifest regenerated successfully!"), false);
        }
        return 1;
    }

    public int executeSync(CommandContext<ServerCommandSource> context) {
        ManifestModel manifest = manifestGenerator.generateAndSaveManifest();
        int modCount = manifest.getMods().size();

        if (context != null && context.getSource() != null) {
            context.getSource().sendFeedback(() -> Text.literal("§a[Sterling] Scanned and synced " + modCount + " mods with Modrinth API."), false);
        }
        return 1;
    }

    public int executeStatus(CommandContext<ServerCommandSource> context) {
        if (context != null && context.getSource() != null) {
            boolean httpActive = config.isEnableHttpServer();
            int httpPort = config.getHttpPort();
            int modSourcesCount = config.getModSources().size();

            context.getSource().sendFeedback(() -> Text.literal("§b--- Sterling's Bridge Status ---"), false);
            context.getSource().sendFeedback(() -> Text.literal("§7HTTP Embedded Server: " + (httpActive ? "§aActive (Port " + httpPort + ")" : "§cDisabled")), false);
            context.getSource().sendFeedback(() -> Text.literal("§7Mapped Mod Sources: §e" + modSourcesCount), false);
            context.getSource().sendFeedback(() -> Text.literal("§7Minecraft Version: §a26.3 / Fabric"), false);
        }
        return 1;
    }
}
