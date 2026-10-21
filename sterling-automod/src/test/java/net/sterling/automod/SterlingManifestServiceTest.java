package net.sterling.automod;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.sterling.automod.service.SterlingManifestService;
import net.sterling.automod.util.SterlingConnectLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class SterlingManifestServiceTest {
    @Test
    void downloadsVerifiesAndCreatesModrinthPack(@TempDir Path tempDir) throws Exception {
        byte[] modBytes = "verified mod bytes".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(modBytes));

        withManifestServer(modBytes, hash, (server, link) -> {
            List<String> progress = new java.util.ArrayList<>();
            Path pack = new SterlingManifestService().downloadAndCreatePack(link, tempDir,
                    update -> progress.add(update.completedMods() + "/" + update.totalMods()
                            + "/" + update.currentModName()));

            assertTrue(pack.getFileName().toString().startsWith("Example Server-"));
            assertTrue(pack.getFileName().toString().endsWith(".mrpack"));
            assertEquals(List.of("0/1/", "0/1/example.jar", "1/1/example.jar"), progress);
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                assertNotNull(zip.getEntry("modrinth.index.json"));
                assertArrayEquals(modBytes, zip.getInputStream(zip.getEntry("overrides/mods/example.jar")).readAllBytes());
                var index = JsonParser.parseString(new String(
                        zip.getInputStream(zip.getEntry("modrinth.index.json")).readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals("Example Server", index.get("name").getAsString());
                assertEquals("0.19.5", index.getAsJsonObject("dependencies").get("fabric-loader").getAsString());
                assertEquals("26.3", index.getAsJsonObject("dependencies").get("minecraft").getAsString());
            }
        });
    }

    @Test
    void rejectsModWithIncorrectSha256(@TempDir Path tempDir) throws Exception {
        byte[] modBytes = "tampered mod bytes".getBytes(StandardCharsets.UTF_8);
        withManifestServer(modBytes, "0".repeat(64), (server, link) ->
                assertThrows(IOException.class,
                        () -> new SterlingManifestService().downloadAndCreatePack(link, tempDir, progress -> {
                        })));
    }

    @Test
    void installsVerifiedModsAndBacksUpReplacedFiles(@TempDir Path gameDirectory) throws Exception {
        byte[] modBytes = "verified mod bytes".getBytes(StandardCharsets.UTF_8);
        byte[] oldModBytes = "old mod version".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(modBytes));
        Path modsDirectory = gameDirectory.resolve("mods");
        Files.createDirectories(modsDirectory);
        Files.write(modsDirectory.resolve("example.jar"), oldModBytes);

        withManifestServer(modBytes, hash, (server, link) -> {
            SterlingManifestService service = new SterlingManifestService();
            var result = service.downloadAndInstallMods(link, modsDirectory, progress -> {
            });

            assertEquals(List.of("example.jar"), result.installedMods());
            assertEquals(List.of(), result.alreadyCurrentMods());
            assertEquals(1, result.installedCount());
            assertEquals(0, result.alreadyCurrentCount());
            assertArrayEquals(modBytes, Files.readAllBytes(modsDirectory.resolve("example.jar")));
            assertNotNull(result.backupDirectory());
            assertArrayEquals(oldModBytes, Files.readAllBytes(result.backupDirectory().resolve("example.jar")));

            var secondResult = service.downloadAndInstallMods(link, modsDirectory, progress -> {
            });
            assertEquals(List.of(), secondResult.installedMods());
            assertEquals(List.of("example.jar"), secondResult.alreadyCurrentMods());
            assertEquals(0, secondResult.installedCount());
            assertEquals(1, secondResult.alreadyCurrentCount());
        });
    }

    @Test
    void rejectsIncorrectHashWithoutChangingModsDirectory(@TempDir Path gameDirectory) throws Exception {
        byte[] modBytes = "tampered mod bytes".getBytes(StandardCharsets.UTF_8);
        byte[] existingModBytes = "existing mod bytes".getBytes(StandardCharsets.UTF_8);
        Path modsDirectory = gameDirectory.resolve("mods");
        Files.createDirectories(modsDirectory);
        Files.write(modsDirectory.resolve("example.jar"), existingModBytes);

        withManifestServer(modBytes, "0".repeat(64), (server, link) -> {
            assertThrows(IOException.class, () ->
                    new SterlingManifestService().downloadAndInstallMods(link, modsDirectory, progress -> {
                    }));
            assertArrayEquals(existingModBytes, Files.readAllBytes(modsDirectory.resolve("example.jar")));
            assertFalse(Files.exists(gameDirectory.resolve("sterling-mod-backups")));
        });
    }

    @Test
    void rejectsCurseForgeArchivesWithAnActionableMessage(@TempDir Path gameDirectory) {
        SterlingConnectLink link = new SterlingConnectLink("localhost", 25565,
                URI.create("https://www.curseforge.com/minecraft/modpacks/example/download/123.zip"));

        IOException exception = assertThrows(IOException.class, () ->
                new SterlingManifestService().downloadAndInstallMods(link, gameDirectory.resolve("mods"),
                        progress -> {
                        }));

        assertTrue(exception.getMessage().contains("CurseForge modpacks are not supported yet"));
    }

    @Test
    void followsModpackLinkPublishedInBridgeManifest(@TempDir Path gameDirectory) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String manifest = """
                {
                  "serverName": "Example Server",
                  "minecraftVersion": "26.3",
                  "loaderType": "fabric",
                  "loaderVersion": "0.19.5",
                  "modpackDownloadLink": "http://127.0.0.1:%d/pack.mrpack",
                  "mods": []
                }
                """.formatted(port);
        server.createContext("/manifest.json", exchange -> {
            byte[] response = manifest.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            SterlingConnectLink link = new SterlingConnectLink("localhost", 25565,
                    URI.create("http://127.0.0.1:" + port + "/manifest.json"));

            IOException exception = assertThrows(IOException.class, () ->
                    new SterlingManifestService().downloadAndInstallMods(link, gameDirectory.resolve("mods"),
                            progress -> {
                            }));

            assertTrue(exception.getMessage().contains("Modrinth modpack links must use HTTPS"));
            assertFalse(Files.exists(gameDirectory.resolve("mods")));
        } finally {
            server.stop(0);
        }
    }

    private static void withManifestServer(byte[] modBytes, String sha256, ServerTest test) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String manifest = """
                {
                  "serverName": "Example Server",
                  "minecraftVersion": "26.3",
                  "loaderType": "fabric",
                  "loaderVersion": "0.19.5",
                  "mods": [{
                    "fileName": "example.jar",
                    "sha256": "%s",
                    "downloadUrl": "http://127.0.0.1:%d/mods/example.jar"
                  }]
                }
                """.formatted(sha256, port);
        server.createContext("/manifest.json", exchange -> {
            byte[] response = manifest.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.createContext("/mods/example.jar", exchange -> {
            exchange.sendResponseHeaders(200, modBytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(modBytes);
            }
        });
        server.start();
        try {
            test.run(server, new SterlingConnectLink("localhost", 25565,
                    URI.create("http://127.0.0.1:" + port + "/manifest.json")));
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface ServerTest {
        void run(HttpServer server, SterlingConnectLink link) throws Exception;
    }
}
