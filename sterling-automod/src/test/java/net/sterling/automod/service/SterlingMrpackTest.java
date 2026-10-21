package net.sterling.automod.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SterlingMrpackTest {
    @Test
    void readsBundledClientModsAndReportsProgress(@TempDir Path tempDir) throws Exception {
        byte[] modBytes = "bundled mod bytes".getBytes(StandardCharsets.UTF_8);
        Path archive = tempDir.resolve("server.mrpack");
        Files.write(archive, createPack("""
                {"formatVersion":1,"game":"minecraft","files":[]}
                """, "overrides/mods/example.jar", modBytes));
        Path stagedMods = Files.createDirectory(tempDir.resolve("staged"));
        List<String> progress = new java.util.ArrayList<>();

        List<SterlingManifestService.DownloadedMod> mods =
                new SterlingManifestService().readMrpackMods(archive, stagedMods,
                        update -> progress.add(update.completedMods() + "/" + update.totalMods()
                                + "/" + update.currentModName()));

        assertEquals(List.of("0/1/", "0/1/example.jar", "1/1/example.jar"), progress);
        assertEquals(1, mods.size());
        assertEquals("example.jar", mods.get(0).fileName());
        assertArrayEquals(modBytes, Files.readAllBytes(mods.get(0).path()));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(modBytes)),
                mods.get(0).sha256());
    }

    @Test
    void clientOverridesReplaceCommonModOverrides(@TempDir Path tempDir) throws Exception {
        byte[] commonVersion = "common version".getBytes(StandardCharsets.UTF_8);
        byte[] clientVersion = "client version".getBytes(StandardCharsets.UTF_8);
        Path archive = tempDir.resolve("overrides.mrpack");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            addEntry(zip, "modrinth.index.json",
                    "{\"formatVersion\":1,\"game\":\"minecraft\",\"files\":[]}".getBytes(StandardCharsets.UTF_8));
            addEntry(zip, "overrides/mods/example.jar", commonVersion);
            addEntry(zip, "client-overrides/mods/example.jar", clientVersion);
        }
        Files.write(archive, bytes.toByteArray());
        Path stagedMods = Files.createDirectory(tempDir.resolve("staged"));

        List<SterlingManifestService.DownloadedMod> mods =
                new SterlingManifestService().readMrpackMods(archive, stagedMods, update -> {
                });

        assertEquals(1, mods.size());
        assertArrayEquals(clientVersion, Files.readAllBytes(mods.get(0).path()));
    }

    @Test
    void rejectsModrinthEntriesWithInvalidHashes(@TempDir Path tempDir) throws Exception {
        Path archive = tempDir.resolve("invalid.mrpack");
        Files.write(archive, createPack("""
                {"formatVersion":1,"game":"minecraft","files":[{
                  "path":"mods/example.jar",
                  "hashes":{"sha1":"bad","sha512":"bad"},
                  "downloads":["https://cdn.example.com/example.jar"],
                  "fileSize":1
                }]}
                """, null, null));

        IOException exception = assertThrows(IOException.class, () ->
                new SterlingManifestService().readMrpackMods(archive,
                        Files.createDirectory(tempDir.resolve("staged")), update -> {
                        }));

        assertTrue(exception.getMessage().contains("invalid mod entry"));
    }

    private static byte[] createPack(String index, String bundledFile, byte[] bundledContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            addEntry(zip, "modrinth.index.json", index.getBytes(StandardCharsets.UTF_8));
            if (bundledFile != null) {
                addEntry(zip, bundledFile, bundledContents);
            }
        }
        return bytes.toByteArray();
    }

    private static void addEntry(ZipOutputStream zip, String name, byte[] contents) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(contents);
        zip.closeEntry();
    }
}
