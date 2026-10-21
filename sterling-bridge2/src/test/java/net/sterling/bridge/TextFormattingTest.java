package net.sterling.bridge;

import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.util.KickMessageFormatter;
import net.sterling.bridge.util.TextHelper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TextFormattingTest {

    @Test
    public void testReplacePlaceholders() {
        String template = "Connect to {server_ip}:{server_port} via {uri_protocol}";
        Map<String, String> placeholders = Map.of(
                "server_ip", "play.sterling.net",
                "server_port", "25565",
                "uri_protocol", "sterling://connect"
        );

        String result = TextHelper.replacePlaceholders(template, placeholders);
        assertEquals("Connect to play.sterling.net:25565 via sterling://connect", result);
    }

    @Test
    public void testKickMessageIncludesLegacySterlingPlaceholder() {
        SterlingConfig config = new SterlingConfig();
        config.setServerIp("play.sterling.net");
        config.setServerPort(25565);
        config.setPublicBaseUrl("https://mods.sterling.net");
        config.setLauncherDownloadUrl("https://sterling.net/download");
        config.setKickMessage("Download: {download_url}\nJoin: {sterling_uri}");

        String result = KickMessageFormatter.create(config).getString();

        assertTrue(result.contains("Download: https://sterling.net/download"));
        assertTrue(result.contains("sterling://connect?ip=play.sterling.net&port=25565&manifest=https://mods.sterling.net/manifest.json"));
    }

    @Test
    public void modpackModeUsesEncodedPackLinkAndSkipsManifestLink() {
        SterlingConfig config = new SterlingConfig();
        config.setModpack(true);
        config.setModpackDownloadLink("https://cdn.modrinth.com/pack.mrpack?token=abc&version=2");
        config.setPublicBaseUrl("https://mods.sterling.net");
        config.setKickMessage("Pack: {modpack_download_link}\nJoin: {sterling_uri}");

        String result = KickMessageFormatter.create(config).getString();

        assertTrue(result.contains("Pack: https://cdn.modrinth.com/pack.mrpack?token=abc&version=2"));
        assertTrue(result.contains("manifest=https%3A%2F%2Fcdn.modrinth.com%2Fpack.mrpack%3Ftoken%3Dabc%26version%3D2"));
    }
}
