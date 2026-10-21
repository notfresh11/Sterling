package net.sterling.bridge.manifest;

import java.util.ArrayList;
import java.util.List;

public class ManifestModel {
    private String serverName;
    private String minecraftVersion;
    private String loaderType;
    private String loaderVersion;
    private String generatedAt;
    private String modpackDownloadLink;
    private List<ModEntry> mods = new ArrayList<>();

    public ManifestModel() {}

    public ManifestModel(String serverName, String minecraftVersion, String loaderType, String loaderVersion, String generatedAt) {
        this.serverName = serverName;
        this.minecraftVersion = minecraftVersion;
        this.loaderType = loaderType;
        this.loaderVersion = loaderVersion;
        this.generatedAt = generatedAt;
    }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getMinecraftVersion() { return minecraftVersion; }
    public void setMinecraftVersion(String minecraftVersion) { this.minecraftVersion = minecraftVersion; }

    public String getLoaderType() { return loaderType; }
    public void setLoaderType(String loaderType) { this.loaderType = loaderType; }

    public String getLoaderVersion() { return loaderVersion; }
    public void setLoaderVersion(String loaderVersion) { this.loaderVersion = loaderVersion; }

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    public String getModpackDownloadLink() { return modpackDownloadLink; }
    public void setModpackDownloadLink(String modpackDownloadLink) { this.modpackDownloadLink = modpackDownloadLink; }

    public List<ModEntry> getMods() { return mods; }
    public void setMods(List<ModEntry> mods) { this.mods = mods; }

    public static class ModEntry {
        private String fileName;
        private String sha256;
        private String downloadUrl;
        private String modId;
        private String version;

        public ModEntry() {}

        public ModEntry(String fileName, String sha256, String downloadUrl, String modId, String version) {
            this.fileName = fileName;
            this.sha256 = sha256;
            this.downloadUrl = downloadUrl;
            this.modId = modId;
            this.version = version;
        }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        public String getSha256() { return sha256; }
        public void setSha256(String sha256) { this.sha256 = sha256; }

        public String getDownloadUrl() { return downloadUrl; }
        public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }

        public String getModId() { return modId; }
        public void setModId(String modId) { this.modId = modId; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }
}
