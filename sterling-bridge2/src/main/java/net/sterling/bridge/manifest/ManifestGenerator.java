package net.sterling.bridge.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.service.ModrinthService;
import net.sterling.bridge.util.HashUtils;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Optional;

public class ManifestGenerator {

    private final Path modsDir;
    private final Path manifestPath;
    private final SterlingConfig config;
    private final ModrinthService modrinthService;
    private final Gson gson;

    public ManifestGenerator(Path modsDir, Path manifestPath, SterlingConfig config, ModrinthService modrinthService) {
        this.modsDir = modsDir;
        this.manifestPath = manifestPath;
        this.config = config;
        this.modrinthService = modrinthService;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public ManifestModel generateAndSaveManifest() {
        ManifestModel manifest = new ManifestModel();
        manifest.setServerName(config.getServerName());
        manifest.setMinecraftVersion("26.3");
        manifest.setLoaderType("fabric");
        manifest.setLoaderVersion("0.19.5");
        manifest.setGeneratedAt(Instant.now().toString());
        if (config.isModpack()) {
            manifest.setModpackDownloadLink(config.getModpackDownloadLink());
        }

        List<ManifestModel.ModEntry> modEntries = new ArrayList<>();
        List<String> excludedMods = config.getExcludedMods().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        if (!config.isModpack() && Files.exists(modsDir) && Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry)) {
                        String fileName = entry.getFileName().toString();
                        String modId = fileName.substring(0, fileName.length() - ".jar".length());
                        if (excludedMods.contains(fileName.toLowerCase(Locale.ROOT))
                                || excludedMods.contains(modId.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        String sha256 = HashUtils.calculateSHA256(entry);

                        String downloadUrl = null;
                        if (modrinthService != null) {
                            Optional<String> modrinthUrl = modrinthService.getDirectDownloadUrlByHash(sha256);
                            if (modrinthUrl.isPresent()) {
                                downloadUrl = modrinthUrl.get();
                            }
                        }

                        if (downloadUrl == null) {
                            String baseUrl = config.getPublicBaseUrl();
                            if (baseUrl.endsWith("/")) {
                                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                            }
                            downloadUrl = baseUrl + "/mods/" + fileName;
                        }

                        ManifestModel.ModEntry modEntry = new ManifestModel.ModEntry(
                                fileName,
                                sha256,
                                downloadUrl,
                                modId,
                                "1.0.0"
                        );
                        modEntries.add(modEntry);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        manifest.setMods(modEntries);

        try {
            if (manifestPath.getParent() != null) {
                Files.createDirectories(manifestPath.getParent());
            }
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(manifestPath), StandardCharsets.UTF_8)) {
                gson.toJson(manifest, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return manifest;
    }
}
