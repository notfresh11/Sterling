package net.sterling.bridge.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public class EmbeddedHttpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddedHttpServer.class);

    private final int port;
    private final Path manifestPath;
    private HttpServer server;

    public EmbeddedHttpServer(int port, Path manifestPath) {
        this.port = port;
        this.manifestPath = manifestPath;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/manifest.json", new ManifestHandler(manifestPath));
            server.setExecutor(null); // creates default executor
            server.start();
            LOGGER.info("Sterling Bridge Embedded HTTP Server started on port {}", port);
        } catch (IOException e) {
            LOGGER.error("Failed to start Embedded HTTP Server on port {}: {}", port, e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            LOGGER.info("Sterling Bridge Embedded HTTP Server stopped.");
        }
    }

    private static class ManifestHandler implements HttpHandler {
        private final Path manifestPath;

        public ManifestHandler(Path manifestPath) {
            this.manifestPath = manifestPath;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (Files.exists(manifestPath)) {
                    byte[] bytes = Files.readAllBytes(manifestPath);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } else {
                    String error = "{\"error\": \"manifest.json not generated yet\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(404, error.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(error.getBytes());
                    }
                }
            } else {
                exchange.sendResponseHeaders(45, -1); // 405 Method Not Allowed
            }
        }
    }
}
