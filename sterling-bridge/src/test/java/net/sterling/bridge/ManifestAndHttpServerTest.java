package net.sterling.bridge.manifest;

import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.http.EmbeddedHttpServer;
import net.sterling.bridge.service.ModrinthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ManifestAndHttpServerTest {

    @Test
    void testManifestGeneratorAndHttpServer(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path modsDir = tempDir.resolve("mods");
        Files.createDirectories(modsDir);

        // Create dummy mod jar
        Path dummyMod = modsDir.resolve("BiomesOPlenty-fabric-26.3-26.3.0.0.15.jar");
        Files.writeString(dummyMod, "Dummy mod contents");

        Path manifestPath = tempDir.resolve("manifest.json");

        SterlingConfig config = new SterlingConfig();
        ModrinthService modrinthService = new ModrinthService();

        ManifestGenerator generator = new ManifestGenerator(modsDir, manifestPath, config, modrinthService);
        ManifestModel manifest = generator.generateAndSaveManifest();

        assertNotNull(manifest);
        assertEquals("26.3", manifest.getMinecraftVersion());
        assertEquals(1, manifest.getMods().size());
        assertTrue(Files.exists(manifestPath));

        // Start embedded HTTP Server
        int testPort = 8089;
        EmbeddedHttpServer httpServer = new EmbeddedHttpServer(testPort, manifestPath);
        httpServer.start();

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/manifest.json"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("BiomesOPlenty"));
        } finally {
            httpServer.stop();
        }
    }
}
