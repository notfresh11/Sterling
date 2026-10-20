package net.sterling.bridge.handler;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.sterling.bridge.config.SterlingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerJoinHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandler.class);
    private final SterlingConfig config;

    public PlayerJoinHandler(SterlingConfig config) {
        this.config = config;
    }

    public void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String playerName = player.getName().getString();

            // Perform client/brand check or sterling handshake verification
            boolean isSterlingClient = checkIsSterlingClient(player);

            if (!isSterlingClient) {
                LOGGER.info("Player {} connected without Sterling Launcher. Disconnecting...", playerName);
                String kickText = formatKickMessage(player);
                handler.disconnect(Text.literal(kickText));
            } else {
                LOGGER.info("Player {} verified with Sterling Launcher.", playerName);
            }
        });
    }

    private boolean checkIsSterlingClient(ServerPlayerEntity player) {
        // In actual network packet processing, inspect custom channel / brand.
        // For standard vanilla players connecting directly without custom Sterling protocol handshake, return false.
        return false;
    }

    public String formatKickMessage(ServerPlayerEntity player) {
        String serverIp = player.getServer() != null ? player.getServer().getServerIp() : "127.0.0.1";
        if (serverIp == null || serverIp.isBlank()) {
            serverIp = "127.0.0.1";
        }
        int serverPort = player.getServer() != null ? player.getServer().getServerPort() : 25565;

        String manifestUrl = config.getCustomManifestUrl();
        if (manifestUrl == null || manifestUrl.isBlank()) {
            manifestUrl = "http://" + serverIp + ":" + config.getHttpPort() + "/manifest.json";
        }

        String sterlingUri = "sterling://connect?ip=" + serverIp + "&port=" + serverPort + "&manifest=" + manifestUrl;

        String rawMessage = config.getKickMessageTemplate();
        return rawMessage
                .replace("{server_ip}", serverIp)
                .replace("{server_port}", String.valueOf(serverPort))
                .replace("{manifest_url}", manifestUrl)
                .replace("{download_url}", config.getLauncherDownloadUrl())
                .replace("{sterling_uri}", sterlingUri)
                .replace("{player_name}", player.getName().getString());
    }
}
