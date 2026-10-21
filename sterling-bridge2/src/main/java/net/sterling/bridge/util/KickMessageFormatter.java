package net.sterling.bridge.util;

import net.minecraft.network.chat.Component;
import net.sterling.bridge.config.SterlingConfig;

import java.util.HashMap;
import java.util.Map;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class KickMessageFormatter {

    private KickMessageFormatter() {
    }

    public static Component create(SterlingConfig config) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("server_ip", config.getServerIp());
        placeholders.put("server_port", String.valueOf(config.getServerPort()));

        String baseUrl = config.getPublicBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String manifestUrl = config.isModpack()
                ? config.getModpackDownloadLink()
                : baseUrl + "/manifest.json";
        config.validate();
        placeholders.put("manifest_url", config.isModpack()
                ? URLEncoder.encode(manifestUrl, StandardCharsets.UTF_8)
                : manifestUrl);
        placeholders.put("modpack_download_link", config.isModpack() ? manifestUrl : "");
        placeholders.put("download_url", config.isModpack() ? manifestUrl : config.getLauncherDownloadUrl());

        String uri = TextHelper.replacePlaceholders(config.getUriProtocol(), placeholders);
        placeholders.put("uri_protocol", uri);
        placeholders.put("sterling_uri", uri);
        placeholders.put("manifest_url", manifestUrl);

        return TextHelper.formatComponent(config.getKickMessage(), placeholders);
    }
}
