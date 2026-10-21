package net.sterling.bridge.handler;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.util.TextHelper;
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
            Object player = null;
            if (handler != null) {
                try {
                    player = handler.getClass().getMethod("getPlayer").invoke(handler);
                } catch (Exception e1) {
                    try {
                        player = handler.getClass().getField("player").get(handler);
                    } catch (Exception e2) {
                        try {
                            player = handler.getClass().getMethod("player").invoke(handler);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            String playerName = getPlayerName(player);

            // Perform client/brand check or sterling handshake verification
            boolean isSterlingClient = checkIsSterlingClient(player);

            if (!isSterlingClient) {
                LOGGER.info("Player {} connected without Sterling Launcher. Disconnecting...", playerName);
                String kickText = formatKickMessage(player, playerName);
                TextHelper.disconnectPlayer(handler, kickText);
            } else {
                LOGGER.info("Player {} verified with Sterling Launcher.", playerName);
            }
        });
    }

    private boolean checkIsSterlingClient(Object player) {
        // In actual network packet processing, inspect custom channel / brand.
        // For standard vanilla players connecting directly without custom Sterling protocol handshake, return false.
        return false;
    }

    private String getPlayerName(Object player) {
        if (player == null) return "Unknown";
        try {
            Object nameObj = player.getClass().getMethod("getName").invoke(player);
            if (nameObj != null) {
                try {
                    Object strObj = nameObj.getClass().getMethod("getString").invoke(nameObj);
                    if (strObj != null) return strObj.toString();
                } catch (Exception ignored) {
                }
                return nameObj.toString();
            }
        } catch (Exception ignored) {
        }
        return "Player";
    }

    public String formatKickMessage(Object player, String playerName) {
        String serverIp = "127.0.0.1";
        int serverPort = 25565;

        if (player != null) {
            try {
                Object serverObj = player.getClass().getMethod("getServer").invoke(player);
                if (serverObj != null) {
                    Object ipObj = serverObj.getClass().getMethod("getServerIp").invoke(serverObj);
                    if (ipObj != null && !ipObj.toString().isBlank()) {
                        serverIp = ipObj.toString();
                    }
                    Object portObj = serverObj.getClass().getMethod("getServerPort").invoke(serverObj);
                    if (portObj instanceof Integer) {
                        serverPort = (Integer) portObj;
                    }
                }
            } catch (Exception ignored) {
            }
        }

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
                .replace("{player_name}", playerName);
    }
}
