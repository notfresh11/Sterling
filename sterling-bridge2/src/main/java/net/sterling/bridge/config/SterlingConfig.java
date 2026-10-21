package net.sterling.bridge.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class SterlingConfig {

    private String serverName = "Sterling Modded Server";
    private String serverIp = "127.0.0.1";
    private int serverPort = 25565;
    private boolean enableHttpServer = true;
    private int httpPort = 8080;
    private String publicBaseUrl = "http://127.0.0.1:8080";
    private String kickMessage = "&c[Sterling Bridge]\n&7You need Sterling Launcher to join this modded server!\n&eClick to sync mods: &b{uri_protocol}\n&eDirect download: &a{download_url}";
    private String uriProtocol = "sterling://connect?ip={server_ip}&port={server_port}&manifest={manifest_url}";

    public static SterlingConfig load(Path configPath) {
        SterlingConfig config = new SterlingConfig();
        try {
            if (Files.notExists(configPath)) {
                if (configPath.getParent() != null) {
                    Files.createDirectories(configPath.getParent());
                }
                config.save(configPath);
                return config;
            }

            Yaml yaml = new Yaml();
            try (InputStream is = Files.newInputStream(configPath)) {
                Map<String, Object> data = yaml.load(is);
                if (data != null) {
                    if (data.containsKey("serverName")) config.setServerName(String.valueOf(data.get("serverName")));
                    if (data.containsKey("serverIp")) config.setServerIp(String.valueOf(data.get("serverIp")));
                    if (data.containsKey("serverPort")) config.setServerPort(((Number) data.get("serverPort")).intValue());
                    if (data.containsKey("enableHttpServer")) config.setEnableHttpServer((Boolean) data.get("enableHttpServer"));
                    if (data.containsKey("httpPort")) config.setHttpPort(((Number) data.get("httpPort")).intValue());
                    if (data.containsKey("publicBaseUrl")) config.setPublicBaseUrl(String.valueOf(data.get("publicBaseUrl")));
                    if (data.containsKey("kickMessage")) config.setKickMessage(String.valueOf(data.get("kickMessage")));
                    if (data.containsKey("uriProtocol")) config.setUriProtocol(String.valueOf(data.get("uriProtocol")));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return config;
    }

    public void save(Path configPath) {
        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Yaml yaml = new Yaml(options);

            Map<String, Object> data = new HashMap<>();
            data.put("serverName", serverName);
            data.put("serverIp", serverIp);
            data.put("serverPort", serverPort);
            data.put("enableHttpServer", enableHttpServer);
            data.put("httpPort", httpPort);
            data.put("publicBaseUrl", publicBaseUrl);
            data.put("kickMessage", kickMessage);
            data.put("uriProtocol", uriProtocol);

            try (OutputStream os = Files.newOutputStream(configPath)) {
                yaml.dump(data, new java.io.OutputStreamWriter(os));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getServerIp() { return serverIp; }
    public void setServerIp(String serverIp) { this.serverIp = serverIp; }

    public int getServerPort() { return serverPort; }
    public void setServerPort(int serverPort) { this.serverPort = serverPort; }

    public boolean isEnableHttpServer() { return enableHttpServer; }
    public void setEnableHttpServer(boolean enableHttpServer) { this.enableHttpServer = enableHttpServer; }

    public int getHttpPort() { return httpPort; }
    public void setHttpPort(int httpPort) { this.httpPort = httpPort; }

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }

    public String getKickMessage() { return kickMessage; }
    public void setKickMessage(String kickMessage) { this.kickMessage = kickMessage; }

    public String getUriProtocol() { return uriProtocol; }
    public void setUriProtocol(String uriProtocol) { this.uriProtocol = uriProtocol; }
}
