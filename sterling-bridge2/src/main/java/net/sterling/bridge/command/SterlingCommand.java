package net.sterling.bridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.manifest.ManifestGenerator;

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
            registerCommand(dispatcher);
        });
    }

    public void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sterling")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("reload")
                        .executes(this::executeReload))
                .then(Commands.literal("sync")
                        .executes(this::executeSync))
                .then(Commands.literal("status")
                        .executes(this::executeStatus))
        );
    }

    public int executeReload(CommandContext<CommandSourceStack> context) {
        SterlingConfig newConfig = SterlingConfig.load(configPath);
        newConfig.validate();
        config.setServerName(newConfig.getServerName());
        config.setServerIp(newConfig.getServerIp());
        config.setServerPort(newConfig.getServerPort());
        config.setEnableHttpServer(newConfig.isEnableHttpServer());
        config.setHttpPort(newConfig.getHttpPort());
        config.setPublicBaseUrl(newConfig.getPublicBaseUrl());
        config.setLauncherDownloadUrl(newConfig.getLauncherDownloadUrl());
        config.setKickMessage(newConfig.getKickMessage());
        config.setUriProtocol(newConfig.getUriProtocol());
        config.setExcludedMods(newConfig.getExcludedMods());
        config.setModpack(newConfig.isModpack());
        config.setModpackDownloadLink(newConfig.getModpackDownloadLink());

        manifestGenerator.generateAndSaveManifest();

        context.getSource().sendSuccess(() -> Component.literal("§a[Sterling Bridge] Configuration reloaded and manifest synced!"), true);
        return 1;
    }

    public int executeSync(CommandContext<CommandSourceStack> context) {
        manifestGenerator.generateAndSaveManifest();
        context.getSource().sendSuccess(() -> Component.literal("§a[Sterling Bridge] Manifest successfully re-generated!"), true);
        return 1;
    }

    public int executeStatus(CommandContext<CommandSourceStack> context) {
        String msg = String.format("§e[Sterling Bridge] Status:\n§7- Server: %s\n§7- IP/Port: %s:%d\n§7- HTTP Server: %s (Port: %d)\n§7- Public URL: %s\n§7- Modpack mode: %s",
                config.getServerName(),
                config.getServerIp(),
                config.getServerPort(),
                config.isEnableHttpServer() ? "Enabled" : "Disabled",
                config.getHttpPort(),
                config.getPublicBaseUrl(),
                config.isModpack() ? "Enabled" : "Disabled"
        );
        context.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
