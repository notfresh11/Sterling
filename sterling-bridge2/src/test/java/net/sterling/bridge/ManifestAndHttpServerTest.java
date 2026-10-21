package net.sterling.bridge;

import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.http.EmbeddedHttpServer;
import net.sterling.bridge.manifest.ManifestGenerator;
import net.sterling.bridge.manifest.ManifestModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ManifestAndHttpServerTest {

    @Test
    public void testManifestGenerationAndHttpServer(@TempDir Path tempDir) throws Exception {
        Path modsDir = tempDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path dummyMod = modsDir.resolve("testmod.jar");
        Files.writeString(dummyMod, "dummy jar content");
        Files.writeString(modsDir.resolve("server-only.jar"), "server-only jar");

        Path manifestPath = tempDir.resolve("manifest.json");
        SterlingConfig config = new SterlingConfig();
        config.setPublicBaseUrl("http://127.0.0.1:18080");
        config.setExcludedMods(List.of("server-only"));

        ManifestGenerator generator = new ManifestGenerator(modsDir, manifestPath, config, null);
        ManifestModel manifest = generator.generateAndSaveManifest();

        assertNotNull(manifest);
        assertEquals("26.3", manifest.getMinecraftVersion());
        assertEquals("fabric", manifest.getLoaderType());
        assertEquals("0.19.5", manifest.getLoaderVersion());
        assertEquals(1, manifest.getMods().size());
        assertEquals("testmod.jar", manifest.getMods().get(0).getFileName());

        assertTrue(Files.exists(manifestPath));

        EmbeddedHttpServer server = new EmbeddedHttpServer(18080, manifestPath, modsDir);
        server.start();

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:18080/manifest.json"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("testmod.jar"));
            assertFalse(response.body().contains("server-only.jar"));

            HttpRequest modRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:18080/mods/testmod.jar"))
                    .GET()
                    .build();

            HttpResponse<String> modResponse = client.send(modRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, modResponse.statusCode());
            assertEquals("dummy jar content", modResponse.body());
        } finally {
            server.stop();
        }
    }

    @Test
    public void modpackModeDoesNotIncludeModsFolderEntries(@TempDir Path tempDir) throws Exception {
        Path modsDir = Files.createDirectory(tempDir.resolve("mods"));
        Files.writeString(modsDir.resolve("server.jar"), "server mod");
        SterlingConfig config = new SterlingConfig();
        config.setModpack(true);
        config.setModpackDownloadLink("https://cdn.modrinth.com/data/abc/versions/1/pack.mrpack");

        ManifestModel manifest = new ManifestGenerator(modsDir, tempDir.resolve("manifest.json"), config, null)
                .generateAndSaveManifest();

        assertTrue(manifest.getMods().isEmpty());
        assertEquals("https://cdn.modrinth.com/data/abc/versions/1/pack.mrpack",
                manifest.getModpackDownloadLink());
    }
}
