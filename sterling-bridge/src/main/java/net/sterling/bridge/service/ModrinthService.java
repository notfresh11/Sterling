package net.sterling.bridge.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

public class ModrinthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModrinthService.class);
    private static final String MODRINTH_API_URL = "https://api.modrinth.com/v2/version_file/";
    private final HttpClient httpClient;

    public ModrinthService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Optional<String> resolveDownloadUrl(String sha256) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MODRINTH_API_URL + sha256))
                    .header("User-Agent", "SterlingBridge/1.0.0 (contact@sterling.net)")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("files") && json.getAsJsonArray("files").size() > 0) {
                    JsonObject primaryFile = json.getAsJsonArray("files").get(0).getAsJsonObject();
                    if (primaryFile.has("url")) {
                        return Optional.of(primaryFile.get("url").getAsString());
                    }
                }
            } else if (response.statusCode() != 404) {
                LOGGER.warn("Modrinth API returned status code {} for hash {}", response.statusCode(), sha256);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to query Modrinth API for hash {}: {}", sha256, e.getMessage());
        }
        return Optional.empty();
    }
}
