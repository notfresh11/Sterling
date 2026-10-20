package net.sterling.bridge.config;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SterlingConfig {
    private int httpPort = 8080;
    private boolean enableHttpServer = true;
    private String customManifestUrl = "";
    private String kickMessageTemplate = "§c§lSterling AutoMod Required!§r\n\n§7Acest server necesită moduri de joc pentru a te putea conecta.§r\n\n§e[Descarcă Sterling AutoMod Launcher]: {download_url}§r\n§a[Conectează-te automat via Sterling Launcher]: {sterling_uri}§r";
    private String launcherDownloadUrl = "https://sterling.net/download";
    private List<String> excludedMods = new ArrayList<>(Arrays.asList("sterling-bridge", "fabric-api", "luckperms"));
    private Map<String, String> modSources = new HashMap<>();

    public SterlingConfig() {
        // Default entries as example
        modSources.put("BiomesOPlenty-fabric-26.3-26.3.0.0.15.jar", "https://cdn.modrinth.com/data/HXF82T3G/versions/MAQI6dsY/BiomesOPlenty-fabric-26.3-26.3.0.0.15.jar?mr_download_reason=standalone");
    }

    public static SterlingConfig load(Path configPath) {
        Yaml yaml = new Yaml();
        SterlingConfig config = new SterlingConfig();

        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                Map<String, Object> data = yaml.load(in);
                if (data != null) {
                    if (data.containsKey("http-port")) {
                        config.httpPort = ((Number) data.get("http-port")).intValue();
                    }
                    if (data.containsKey("enable-http-server")) {
                        config.enableHttpServer = (Boolean) data.get("enable-http-server");
                    }
                    if (data.containsKey("custom-manifest-url")) {
                        config.customManifestUrl = Objects.toString(data.get("custom-manifest-url"), "");
                    }
                    if (data.containsKey("kick-message")) {
                        config.kickMessageTemplate = Objects.toString(data.get("kick-message"), config.kickMessageTemplate);
                    }
                    if (data.containsKey("launcher-download-url")) {
                        config.launcherDownloadUrl = Objects.toString(data.get("launcher-download-url"), config.launcherDownloadUrl);
                    }
                    if (data.containsKey("excluded-mods") && data.get("excluded-mods") instanceof List) {
                        config.excludedMods = (List<String>) data.get("excluded-mods");
                    }
                    if (data.containsKey("mod-sources") && data.get("mod-sources") instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) data.get("mod-sources");
                        config.modSources.clear();
                        for (Map.Entry<String, Object> entry : map.entrySet()) {
                            if (entry.getValue() != null) {
                                config.modSources.put(entry.getKey(), entry.getValue().toString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to load sterling config, using defaults: " + e.getMessage());
            }
        } else {
            config.save(configPath);
        }

        return config;
    }

    public void save(Path configPath) {
        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enable-http-server", enableHttpServer);
            data.put("http-port", httpPort);
            data.put("custom-manifest-url", customManifestUrl);
            data.put("launcher-download-url", launcherDownloadUrl);
            data.put("kick-message", kickMessageTemplate);
            data.put("excluded-mods", excludedMods);
            data.put("mod-sources", modSources);

            Yaml yaml = new Yaml();
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                yaml.dump(data, writer);
            }
        } catch (Exception e) {
            System.err.println("Failed to save sterling config: " + e.getMessage());
        }
    }

    // Getters and Setters
    public int getHttpPort() { return httpPort; }
    public boolean isEnableHttpServer() { return enableHttpServer; }
    public String getCustomManifestUrl() { return customManifestUrl; }
    public String getKickMessageTemplate() { return kickMessageTemplate; }
    public String getLauncherDownloadUrl() { return launcherDownloadUrl; }
    public List<String> getExcludedMods() { return excludedMods; }
    public Map<String, String> getModSources() { return modSources; }
}
