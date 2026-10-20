package net.sterling.bridge.manifest;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

public class ManifestModel {
    @SerializedName("server_name")
    private String serverName = "Sterling Server";

    @SerializedName("minecraft_version")
    private String minecraftVersion = "26.3";

    @SerializedName("loader")
    private LoaderInfo loader = new LoaderInfo("fabric", "26.3");

    @SerializedName("java_version")
    private int javaVersion = 21;

    @SerializedName("recommended_ram_mb")
    private int recommendedRamMb = 4096;

    @SerializedName("mods")
    private List<ModItem> mods = new ArrayList<>();

    public ManifestModel() {}

    public ManifestModel(String serverName, String minecraftVersion, String loaderType, String loaderVersion, List<ModItem> mods) {
        this.serverName = serverName;
        this.minecraftVersion = minecraftVersion;
        this.loader = new LoaderInfo(loaderType, loaderVersion);
        this.mods = mods;
    }

    public String getServerName() { return serverName; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public LoaderInfo getLoader() { return loader; }
    public int getJavaVersion() { return javaVersion; }
    public List<ModItem> getMods() { return mods; }

    public static class LoaderInfo {
        private String type;
        private String version;

        public LoaderInfo(String type, String version) {
            this.type = type;
            this.version = version;
        }

        public String getType() { return type; }
        public String getVersion() { return version; }
    }

    public static class ModItem {
        private String id;
        private String version;

        @SerializedName("file_name")
        private String fileName;

        private String sha256;

        @SerializedName("size_bytes")
        private long sizeBytes;

        @SerializedName("download_url")
        private String downloadUrl;

        public ModItem(String id, String version, String fileName, String sha256, long sizeBytes, String downloadUrl) {
            this.id = id;
            this.version = version;
            this.fileName = fileName;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.downloadUrl = downloadUrl;
        }

        public String getId() { return id; }
        public String getVersion() { return version; }
        public String getFileName() { return fileName; }
        public String getSha256() { return sha256; }
        public long getSizeBytes() { return sizeBytes; }
        public String getDownloadUrl() { return downloadUrl; }
    }
}
