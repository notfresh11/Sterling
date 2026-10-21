package net.sterling.bridge.handler;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.util.TextHelper;

import java.util.HashMap;
import java.util.Map;

public class PlayerJoinHandler {

    private final SterlingConfig config;

    public PlayerJoinHandler(SterlingConfig config) {
        this.config = config;
    }

    public void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            checkPlayerAndKickIfNeeded(handler);
        });
    }

    public void checkPlayerAndKickIfNeeded(ServerGamePacketListenerImpl handler) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("server_ip", config.getServerIp());
        placeholders.put("server_port", String.valueOf(config.getServerPort()));

        String baseUrl = config.getPublicBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String manifestUrl = baseUrl + "/manifest.json";
        placeholders.put("manifest_url", manifestUrl);
        placeholders.put("download_url", baseUrl);

        String uri = TextHelper.replacePlaceholders(config.getUriProtocol(), placeholders);
        placeholders.put("uri_protocol", uri);

        Component kickMessageComponent = TextHelper.formatComponent(config.getKickMessage(), placeholders);
        handler.disconnect(kickMessageComponent);
    }
}
