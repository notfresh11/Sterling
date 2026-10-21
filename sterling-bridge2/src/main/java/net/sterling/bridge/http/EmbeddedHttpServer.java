package net.sterling.bridge.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public class EmbeddedHttpServer {

    private final int port;
    private final Path manifestPath;
    private final Path modsDir;
    private HttpServer server;

    public EmbeddedHttpServer(int port, Path manifestPath, Path modsDir) {
        this.port = port;
        this.manifestPath = manifestPath;
        this.modsDir = modsDir;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/manifest.json", new ManifestHandler());
            server.createContext("/mods/", new ModsHandler());

            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private class ManifestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!Files.exists(manifestPath)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            byte[] bytes = Files.readAllBytes(manifestPath);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class ModsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String fileName = path.substring("/mods/".length());
            Path modFile = modsDir.resolve(fileName).normalize();

            if (!modFile.startsWith(modsDir) || Files.notExists(modFile) || !Files.isRegularFile(modFile)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            long fileSize = Files.size(modFile);
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, fileSize);

            try (InputStream is = Files.newInputStream(modFile);
                 OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        }
    }
}
