package net.sterling.bridge.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.service.ModrinthService;
import net.sterling.bridge.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class ManifestGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ManifestGenerator.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path modsDir;
    private final Path outputManifestPath;
    private final SterlingConfig config;
    private final ModrinthService modrinthService;

    public ManifestGenerator(Path modsDir, Path outputManifestPath, SterlingConfig config, ModrinthService modrinthService) {
        this.modsDir = modsDir;
        this.outputManifestPath = outputManifestPath;
        this.config = config;
        this.modrinthService = modrinthService;
    }

    public ManifestModel generateAndSaveManifest() {
        List<ManifestModel.ModItem> modItems = new ArrayList<>();

        if (Files.exists(modsDir) && Files.isDirectory(modsDir)) {
            try (Stream<Path> stream = Files.list(modsDir)) {
                List<Path> jarFiles = stream.filter(p -> p.toString().endsWith(".jar")).toList();

                for (Path jarPath : jarFiles) {
                    String fileName = jarPath.getFileName().toString();

                    // Check excluded mods
                    boolean isExcluded = config.getExcludedMods().stream()
                            .anyMatch(excluded -> fileName.toLowerCase().contains(excluded.toLowerCase()));

                    if (isExcluded) {
                        LOGGER.info("Skipping excluded mod: {}", fileName);
                        continue;
                    }

                    String sha256 = HashUtils.calculateSHA256(jarPath);
                    long sizeBytes = Files.size(jarPath);

                    // Check config mod-sources first
                    String downloadUrl = config.getModSources().get(fileName);

                    // If not in config, query Modrinth API
                    if (downloadUrl == null || downloadUrl.isBlank()) {
                        LOGGER.info("Querying Modrinth API for file {} (hash: {})", fileName, sha256);
                        Optional<String> modrinthUrl = modrinthService.resolveDownloadUrl(sha256);
                        if (modrinthUrl.isPresent()) {
                            downloadUrl = modrinthUrl.get();
                            LOGGER.info("Found Modrinth URL for {}: {}", fileName, downloadUrl);
                            config.getModSources().put(fileName, downloadUrl); // cache in config
                        } else {
                            LOGGER.warn("No download URL found for {}. Specify it in config.yml under mod-sources.", fileName);
                            downloadUrl = "https://modrinth.com/mods"; // fallback placeholder
                        }
                    }

                    String modId = fileName.replace(".jar", "").toLowerCase();
                    modItems.add(new ManifestModel.ModItem(modId, "26.3", fileName, sha256, sizeBytes, downloadUrl));
                }
            } catch (IOException e) {
                LOGGER.error("Failed to read mods directory: {}", e.getMessage());
            }
        } else {
            LOGGER.warn("Mods directory does not exist at: {}", modsDir);
        }

        ManifestModel manifest = new ManifestModel(
                "Sterling Modded Server",
                "26.3",
                "fabric",
                "26.3",
                modItems
        );

        saveManifest(manifest);
        return manifest;
    }

    public void saveManifest(ManifestModel manifest) {
        try {
            if (outputManifestPath.getParent() != null) {
                Files.createDirectories(outputManifestPath.getParent());
            }
            String json = GSON.toJson(manifest);
            Files.writeString(outputManifestPath, json);
            LOGGER.info("Successfully saved manifest.json to {}", outputManifestPath.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to save manifest.json: {}", e.getMessage());
        }
    }
}
