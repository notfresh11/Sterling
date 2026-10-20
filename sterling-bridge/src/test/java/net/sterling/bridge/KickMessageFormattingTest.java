package net.sterling.bridge.handler;

import net.sterling.bridge.config.SterlingConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KickMessageFormattingTest {

    @Test
    void testKickMessageFormatting() {
        SterlingConfig config = new SterlingConfig();
        PlayerJoinHandler handler = new PlayerJoinHandler(config);

        String rawTemplate = config.getKickMessageTemplate();
        assertNotNull(rawTemplate);

        String serverIp = "123.45.67.89";
        int serverPort = 25565;
        String manifestUrl = "http://" + serverIp + ":" + config.getHttpPort() + "/manifest.json";
        String sterlingUri = "sterling://connect?ip=" + serverIp + "&port=" + serverPort + "&manifest=" + manifestUrl;

        String formatted = rawTemplate
                .replace("{server_ip}", serverIp)
                .replace("{server_port}", String.valueOf(serverPort))
                .replace("{manifest_url}", manifestUrl)
                .replace("{download_url}", config.getLauncherDownloadUrl())
                .replace("{sterling_uri}", sterlingUri)
                .replace("{player_name}", "Steve");

        assertTrue(formatted.contains(sterlingUri));
        assertTrue(formatted.contains(config.getLauncherDownloadUrl()));
        assertTrue(formatted.contains("Sterling AutoMod Required!"));
    }
}
