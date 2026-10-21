package net.sterling.automod;

import net.sterling.automod.connection.AutoModConnectionState;
import net.sterling.automod.util.SterlingConnectLink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SterlingConnectLinkTest {
    @Test
    void parsesEncodedManifestLinkFromDisconnectText() {
        SterlingConnectLink link = SterlingConnectLink.parse(
                "Join with sterling://connect?ip=play.example.net&port=25570&manifest=https%3A%2F%2Fmods.example.net%2Fmanifest.json"
        ).orElseThrow();

        assertEquals("play.example.net", link.serverIp());
        assertEquals(25570, link.serverPort());
        assertEquals("https://mods.example.net/manifest.json", link.manifestUri().toString());
    }

    @Test
    void parsesEncodedModpackLinkWithQueryParameters() {
        SterlingConnectLink link = SterlingConnectLink.parse(
                "sterling://connect?ip=play.example.net&port=25565&manifest=https%3A%2F%2Fcdn.modrinth.com%2Fpack.mrpack%3Ftoken%3Done%26version%3D2"
        ).orElseThrow();

        assertEquals("https://cdn.modrinth.com/pack.mrpack?token=one&version=2",
                link.manifestUri().toString());
    }

    @Test
    void ignoresNonSterlingOrInvalidLinks() {
        assertTrue(SterlingConnectLink.parse("Normal disconnect").isEmpty());
        assertTrue(SterlingConnectLink.parse(
                "sterling://connect?ip=localhost&manifest=file%3A%2F%2F%2Ftmp%2Fmanifest.json").isEmpty());
    }

    @Test
    void detectsFabricRegistryMismatchAndUsesServerManifestEndpoint() {
        String reason = "Received 1071 registry entries that are unknown to this client. "
                + "This is usually caused by a mismatched mod set.";
        assertTrue(SterlingConnectLink.isRegistryMismatch(reason));
        SterlingConnectLink link = SterlingConnectLink.forRegistryMismatch("localhost:25565").orElseThrow();
        assertEquals("localhost", link.serverIp());
        assertEquals(25565, link.serverPort());
        assertEquals("http://localhost:8080/manifest.json", link.manifestUri().toString());
    }

    @Test
    void detectsFabricDisconnectUsingPluralRegistriesWording() {
        String reason = "Received 6 registries that are unknown to this client. "
                + "The following registries are not present on the client.";

        assertTrue(SterlingConnectLink.isRegistryMismatch(reason));
    }

    @Test
    void usesRememberedServerAddressAfterMinecraftClearsActiveConnection() {
        AutoModConnectionState.rememberServerAddress("localhost:25565");

        SterlingConnectLink link = SterlingConnectLink.forRegistryMismatch(
                AutoModConnectionState.getLastServerAddress()).orElseThrow();

        assertEquals("localhost", link.serverIp());
        assertEquals(25565, link.serverPort());
        assertEquals("http://localhost:8080/manifest.json", link.manifestUri().toString());
    }

    @Test
    void doesNotClassifyOtherDisconnectReasonsAsRegistryMismatches() {
        assertFalse(SterlingConnectLink.isRegistryMismatch("You are banned from this server."));
        assertTrue(SterlingConnectLink.forRegistryMismatch(null).isEmpty());
    }
}
