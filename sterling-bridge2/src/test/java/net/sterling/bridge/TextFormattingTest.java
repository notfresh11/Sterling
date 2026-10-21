package net.sterling.bridge;

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
}
