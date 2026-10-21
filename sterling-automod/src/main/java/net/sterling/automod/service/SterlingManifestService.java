package net.sterling.automod.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.sterling.automod.util.SterlingConnectLink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class SterlingManifestService {
    private static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MOD_BYTES = 200 * 1024 * 1024;
    private static final int MAX_MOD_COUNT = 512;
    private static final int MAX_MODPACK_BYTES = 1024 * 1024 * 1024;
    private static final long MAX_TOTAL_MOD_BYTES = 2L * 1024 * 1024 * 1024;
    private static final String MODS_DIRECTORY = "mods/";
    private static final String OVERRIDES_MODS_DIRECTORY = "overrides/mods/";
    private static final String CLIENT_OVERRIDES_MODS_DIRECTORY = "client-overrides/mods/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Gson gson = new Gson();

    public Path downloadAndCreatePack(
            SterlingConnectLink link,
            Path outputDirectory,
            ProgressListener progress
    ) throws Exception {
        SterlingManifest manifest = loadManifest(link);

        Files.createDirectories(outputDirectory);
        Path temporaryDirectory = Files.createTempDirectory(outputDirectory, ".sterling-download-");
        try {
            List<DownloadedMod> mods = downloadMods(manifest, temporaryDirectory, progress);
            Path packPath = uniquePackPath(outputDirectory, manifest.serverName);
            writePack(packPath, manifest, mods);
            return packPath;
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    public InstallResult downloadAndInstallMods(
            SterlingConnectLink link,
            Path modsDirectory,
            ProgressListener progress
    ) throws Exception {
        URI packUri = link.manifestUri();
        validateHttpUri(packUri);
        String host = packUri.getHost().toLowerCase(Locale.ROOT);
        String urlPath = packUri.getPath().toLowerCase(Locale.ROOT);
        if (host.contains("curseforge") || host.contains("forgecdn") || urlPath.endsWith(".zip")) {
            throw new IOException("CurseForge modpacks are not supported yet. Use a direct Modrinth .mrpack link.");
        }
        if (urlPath.endsWith(".mrpack")) {
            return downloadAndInstallMrpack(packUri, modsDirectory, progress);
        }
        if (host.contains("modrinth.com") && !urlPath.endsWith(".json")) {
            throw new IOException("This is not a direct Modrinth .mrpack download URL. Copy the link from its Download button.");
        }

        SterlingManifest manifest = loadManifest(link);
        if (manifest.modpackDownloadLink != null && !manifest.modpackDownloadLink.isBlank()) {
            URI configuredPackUri;
            try {
                configuredPackUri = URI.create(manifest.modpackDownloadLink);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Bridge manifest contains an invalid Modrinth modpack URL.", exception);
            }
            validateHttpUri(configuredPackUri);
            if (!configuredPackUri.getPath().toLowerCase(Locale.ROOT).endsWith(".mrpack")) {
                throw new IOException("Bridge manifest modpack URL must point directly to a .mrpack file.");
            }
            return downloadAndInstallMrpack(configuredPackUri, modsDirectory, progress);
        }
        Path normalizedModsDirectory = modsDirectory.toAbsolutePath().normalize();
        Path gameDirectory = normalizedModsDirectory.getParent();
        if (gameDirectory == null) {
            throw new IOException("The Minecraft mods folder must have a parent directory.");
        }

        Files.createDirectories(gameDirectory);
        Path temporaryDirectory = Files.createTempDirectory(gameDirectory, ".sterling-download-");
        try {
            List<DownloadedMod> mods = downloadMods(manifest, temporaryDirectory, progress);
            return installMods(mods, normalizedModsDirectory, gameDirectory);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private InstallResult downloadAndInstallMrpack(
            URI packUri,
            Path modsDirectory,
            ProgressListener progress
    ) throws Exception {
        if (!"https".equalsIgnoreCase(packUri.getScheme())) {
            throw new IOException("Modrinth modpack links must use HTTPS.");
        }
        Path normalizedModsDirectory = modsDirectory.toAbsolutePath().normalize();
        Path gameDirectory = normalizedModsDirectory.getParent();
        if (gameDirectory == null) {
            throw new IOException("The Minecraft mods folder must have a parent directory.");
        }

        Files.createDirectories(gameDirectory);
        Path temporaryDirectory = Files.createTempDirectory(gameDirectory, ".sterling-modpack-");
        try {
            Path archive = temporaryDirectory.resolve("server.mrpack");
            downloadBounded(packUri, archive, MAX_MODPACK_BYTES);
            Path stagedMods = Files.createDirectory(temporaryDirectory.resolve("mods"));
            List<DownloadedMod> mods = readMrpackMods(archive, stagedMods, progress);
            return installMods(mods, normalizedModsDirectory, gameDirectory);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    List<DownloadedMod> readMrpackMods(
            Path archive,
            Path stagedMods,
            ProgressListener progress
    ) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null || indexEntry.getSize() > MAX_MANIFEST_BYTES) {
                throw new IOException("The Modrinth pack has no valid modrinth.index.json.");
            }
            ModrinthIndex index;
            try (InputStream input = zip.getInputStream(indexEntry)) {
                index = gson.fromJson(new String(readBounded(input, MAX_MANIFEST_BYTES),
                        java.nio.charset.StandardCharsets.UTF_8), ModrinthIndex.class);
            } catch (RuntimeException exception) {
                throw new IOException("The Modrinth pack index is invalid.", exception);
            }
            if (index == null || index.formatVersion != 1 || !"minecraft".equals(index.game)
                    || index.files == null || index.files.size() > MAX_MOD_COUNT) {
                throw new IOException("The Modrinth pack is invalid or uses an unsupported format.");
            }

            List<ModrinthFile> downloadableMods = new ArrayList<>();
            for (ModrinthFile file : index.files) {
                if (!isClientModPath(file.path) || isClientUnsupported(file)) {
                    continue;
                }
                validateModrinthFile(file);
                downloadableMods.add(file);
            }

            Map<String, ZipEntry> bundledMods = new LinkedHashMap<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (isOverrideModEntry(entry)) {
                    String fileName = overrideFileName(entry);
                    String key = fileName.toLowerCase(Locale.ROOT);
                    if (entry.getName().startsWith(CLIENT_OVERRIDES_MODS_DIRECTORY)) {
                        bundledMods.put(key, entry);
                    } else {
                        bundledMods.putIfAbsent(key, entry);
                    }
                }
            }
            if (downloadableMods.size() + bundledMods.size() > MAX_MOD_COUNT) {
                throw new IOException("The Modrinth pack contains too many client mods.");
            }

            int total = downloadableMods.size() + bundledMods.size();
            reportProgress(progress, 0, total, "");
            Map<String, DownloadedMod> mods = new LinkedHashMap<>();
            Set<String> fileNames = new HashSet<>();
            int completed = 0;
            long totalModBytes = 0;
            for (ModrinthFile file : downloadableMods) {
                String fileName = file.path.substring(MODS_DIRECTORY.length());
                ensureUniqueFileName(fileNames, fileName);
                if (totalModBytes + file.fileSize > MAX_TOTAL_MOD_BYTES) {
                    throw new IOException("Modrinth pack exceeds the 2 GB total mod size limit.");
                }
                reportProgress(progress, completed, total, fileName);
                Path destination = stagedMods.resolve(completed + ".jar");
                DownloadedMod downloaded = downloadMrpackFile(file, destination, fileName);
                mods.put(fileName.toLowerCase(Locale.ROOT), downloaded);
                totalModBytes += Files.size(destination);
                reportProgress(progress, ++completed, total, fileName);
            }

            for (Map.Entry<String, ZipEntry> bundledMod : bundledMods.entrySet()) {
                ZipEntry entry = bundledMod.getValue();
                String fileName = overrideFileName(entry);
                if (entry.getSize() > MAX_MOD_BYTES || totalModBytes + Math.max(0, entry.getSize()) > MAX_TOTAL_MOD_BYTES) {
                    throw new IOException("Modrinth pack exceeds the configured mod size limit.");
                }
                reportProgress(progress, completed, total, fileName);
                Path destination = stagedMods.resolve(completed + ".jar");
                String hash = extractBundledMod(zip, entry, destination);
                totalModBytes += Files.size(destination);
                if (totalModBytes > MAX_TOTAL_MOD_BYTES) {
                    throw new IOException("Modrinth pack exceeds the 2 GB total mod size limit.");
                }
                mods.put(bundledMod.getKey(), new DownloadedMod(fileName, destination, hash));
                reportProgress(progress, ++completed, total, fileName);
            }
            if (mods.size() > MAX_MOD_COUNT) {
                throw new IOException("The Modrinth pack contains too many client mods.");
            }
            return new ArrayList<>(mods.values());
        } catch (java.util.zip.ZipException exception) {
            throw new IOException("The Modrinth pack download is not a valid ZIP archive.", exception);
        }
    }

    private DownloadedMod downloadMrpackFile(ModrinthFile file, Path destination, String fileName)
            throws IOException {
        IOException failure = null;
        for (String downloadUrl : file.downloads) {
            URI uri;
            try {
                uri = URI.create(downloadUrl);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    throw new IOException("Modrinth pack contains a non-HTTPS mod download URL.");
                }
                String sha256 = downloadMod(uri, destination);
                if (file.fileSize >= 0 && Files.size(destination) != file.fileSize) {
                    throw new IOException("Downloaded file size does not match the Modrinth index for " + fileName);
                }
                if (!digest(destination, "SHA-512").equalsIgnoreCase(file.hashes.get("sha512"))
                        || !digest(destination, "SHA-1").equalsIgnoreCase(file.hashes.get("sha1"))) {
                    throw new IOException("Modrinth hash verification failed for " + fileName);
                }
                return new DownloadedMod(fileName, destination, sha256);
            } catch (Exception exception) {
                IOException downloadFailure = exception instanceof IOException ioException
                        ? ioException : new IOException("Could not download " + fileName, exception);
                if (failure == null) {
                    failure = downloadFailure;
                } else {
                    failure.addSuppressed(downloadFailure);
                }
            }
        }
        throw failure == null ? new IOException("No download URL is available for " + fileName) : failure;
    }

    private static String extractBundledMod(ZipFile zip, ZipEntry entry, Path destination) throws IOException {
        if (entry.getSize() > MAX_MOD_BYTES) {
            throw new IOException("Bundled mod " + entry.getName() + " exceeds the 200 MB limit.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int totalBytes = 0;
            byte[] buffer = new byte[8192];
            try (InputStream input = new DigestInputStream(zip.getInputStream(entry), digest);
                 OutputStream output = Files.newOutputStream(destination)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > MAX_MOD_BYTES) {
                        throw new IOException("Bundled mod " + entry.getName() + " exceeds the 200 MB limit.");
                    }
                    output.write(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static void validateModrinthFile(ModrinthFile file) throws IOException {
        if (file.hashes == null || !isSha1(file.hashes.get("sha1")) || !isSha512(file.hashes.get("sha512"))
                || file.downloads == null || file.downloads.isEmpty()
                || file.fileSize < 0 || file.fileSize > MAX_MOD_BYTES) {
            throw new IOException("Modrinth pack contains an invalid mod entry: " + file.path);
        }
    }

    private static boolean isClientModPath(String path) {
        if (path == null || !path.startsWith(MODS_DIRECTORY) || !path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        String fileName = path.substring(MODS_DIRECTORY.length());
        return !fileName.isBlank() && !fileName.contains("/") && !fileName.contains("\\")
                && !".".equals(fileName) && !"..".equals(fileName);
    }

    private static boolean isClientUnsupported(ModrinthFile file) {
        return file.env != null && ("unsupported".equalsIgnoreCase(file.env.client)
                || "optional".equalsIgnoreCase(file.env.client));
    }

    private static boolean isOverrideModEntry(ZipEntry entry) {
        if (entry.isDirectory() || (!entry.getName().startsWith(OVERRIDES_MODS_DIRECTORY)
                && !entry.getName().startsWith(CLIENT_OVERRIDES_MODS_DIRECTORY))) {
            return false;
        }
        String fileName = overrideFileName(entry);
        return !fileName.isBlank() && !fileName.contains("/") && !fileName.contains("\\")
                && fileName.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static String overrideFileName(ZipEntry entry) {
        String directory = entry.getName().startsWith(CLIENT_OVERRIDES_MODS_DIRECTORY)
                ? CLIENT_OVERRIDES_MODS_DIRECTORY : OVERRIDES_MODS_DIRECTORY;
        return entry.getName().substring(directory.length());
    }

    private static void ensureUniqueFileName(Set<String> fileNames, String fileName) throws IOException {
        if (!fileNames.add(fileName.toLowerCase(Locale.ROOT))) {
            throw new IOException("Modrinth pack contains duplicate mod filenames: " + fileName);
        }
    }

    private static boolean isSha1(String hash) {
        return hash != null && hash.matches("(?i)[0-9a-f]{40}");
    }

    private static boolean isSha512(String hash) {
        return hash != null && hash.matches("(?i)[0-9a-f]{128}");
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maximumBytes) {
                throw new IOException("Modrinth pack index exceeds the size limit.");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void downloadBounded(URI uri, Path destination, int maximumBytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "Sterling-AutoMod/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body();
             OutputStream output = Files.newOutputStream(destination)) {
            if (response.statusCode() != 200) {
                throw new IOException("Modrinth pack download returned HTTP " + response.statusCode());
            }
            int totalBytes = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > maximumBytes) {
                    throw new IOException("Modrinth pack exceeds the 1 GB download limit.");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private SterlingManifest loadManifest(SterlingConnectLink link) throws Exception {
        byte[] manifestJson = requestBytes(link.manifestUri(), MAX_MANIFEST_BYTES);
        SterlingManifest manifest = gson.fromJson(new String(manifestJson, java.nio.charset.StandardCharsets.UTF_8),
                SterlingManifest.class);
        validateManifest(manifest);
        return manifest;
    }

    private List<DownloadedMod> downloadMods(
            SterlingManifest manifest,
            Path temporaryDirectory,
            ProgressListener progress
    ) throws Exception {
        List<DownloadedMod> mods = new ArrayList<>();
        Set<String> fileNames = new HashSet<>();
        int total = manifest.mods.size();
        reportProgress(progress, 0, total, "");
        for (int index = 0; index < total; index++) {
            SterlingManifest.ModEntry entry = manifest.mods.get(index);
            validateModEntry(entry);
            String normalizedName = entry.fileName.toLowerCase(Locale.ROOT);
            if (!fileNames.add(normalizedName)) {
                throw new IOException("Manifestul conține nume de fișiere duplicate: " + entry.fileName);
            }

            Path downloadedFile = temporaryDirectory.resolve(index + ".jar");
            reportProgress(progress, index, total, entry.fileName);
            String actualHash = downloadMod(URI.create(entry.downloadUrl), downloadedFile);
            if (!actualHash.equalsIgnoreCase(entry.sha256)) {
                throw new IOException("SHA-256 nu corespunde pentru " + entry.fileName);
            }
            mods.add(new DownloadedMod(entry.fileName, downloadedFile, actualHash));
            reportProgress(progress, index + 1, total, entry.fileName);
        }
        return mods;
    }

    private static void reportProgress(
            ProgressListener progress,
            int completedMods,
            int totalMods,
            String currentModName
    ) {
        progress.onProgress(new DownloadProgress(completedMods, totalMods, currentModName));
    }

    private static InstallResult installMods(
            List<DownloadedMod> mods,
            Path modsDirectory,
            Path gameDirectory
    ) throws IOException {
        Files.createDirectories(modsDirectory);
        Path backupDirectory = null;
        List<InstallOperation> operations = new ArrayList<>();
        List<String> installedMods = new ArrayList<>();
        List<String> alreadyCurrentMods = new ArrayList<>();

        try {
            for (DownloadedMod mod : mods) {
                Path target = modsDirectory.resolve(mod.fileName).normalize();
                if (!target.startsWith(modsDirectory) || Files.isSymbolicLink(target)) {
                    throw new IOException("Unsafe mod destination: " + mod.fileName);
                }
                if (Files.exists(target) && !Files.isRegularFile(target)) {
                    throw new IOException("A non-file already exists at the mod destination: " + target);
                }
                if (Files.isRegularFile(target) && sha256(target).equalsIgnoreCase(mod.sha256)) {
                    alreadyCurrentMods.add(mod.fileName);
                    continue;
                }

                Path backup = null;
                if (Files.exists(target)) {
                    if (backupDirectory == null) {
                        Path backupRoot = gameDirectory.resolve("sterling-mod-backups");
                        Files.createDirectories(backupRoot);
                        backupDirectory = Files.createTempDirectory(backupRoot, "mods-");
                    }
                    backup = backupDirectory.resolve(mod.fileName);
                }

                InstallOperation operation = new InstallOperation(target, backup);
                operations.add(operation);
                if (backup != null) {
                    moveFile(target, backup);
                    operation.backedUp = true;
                }
                moveFile(mod.path, target);
                operation.installed = true;
                installedMods.add(mod.fileName);
            }
        } catch (IOException exception) {
            rollbackInstall(operations, exception);
            throw exception;
        }

        return new InstallResult(installedMods, alreadyCurrentMods, backupDirectory);
    }

    private static void rollbackInstall(List<InstallOperation> operations, IOException failure) {
        for (int index = operations.size() - 1; index >= 0; index--) {
            InstallOperation operation = operations.get(index);
            try {
                if (operation.installed) {
                    Files.deleteIfExists(operation.target);
                }
                if (operation.backedUp) {
                    moveFile(operation.backup, operation.target);
                }
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private static void moveFile(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static String sha256(Path file) throws IOException {
        return digest(file, "SHA-256");
    }

    private static String digest(Path file, String algorithm) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is not available.", exception);
        }
    }

    private byte[] requestBytes(URI uri, int maximumBytes) throws Exception {
        validateHttpUri(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "Sterling-AutoMod/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Descărcarea de la " + uri + " a returnat HTTP " + response.statusCode());
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maximumBytes) {
                    throw new IOException("Răspunsul de la " + uri + " depășește limita permisă.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String downloadMod(URI uri, Path destination) throws Exception {
        validateHttpUri(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "Sterling-AutoMod/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Descărcarea de la " + uri + " a returnat HTTP " + response.statusCode());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int totalBytes = 0;
            byte[] buffer = new byte[8192];
            try (OutputStream output = Files.newOutputStream(destination);
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                int read;
                while ((read = digestInput.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > MAX_MOD_BYTES) {
                        throw new IOException("Fișierul " + destination.getFileName() + " depășește limita de 200 MB.");
                    }
                    output.write(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static void validateHttpUri(URI uri) throws IOException {
        if (uri == null || uri.getHost() == null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IOException("Manifestul conține un URL de descărcare invalid.");
        }
    }

    private static void validateManifest(SterlingManifest manifest) throws IOException {
        if (manifest == null || manifest.serverName == null || manifest.serverName.isBlank()
                || manifest.minecraftVersion == null || manifest.minecraftVersion.isBlank()
                || !"fabric".equalsIgnoreCase(manifest.loaderType)
                || manifest.loaderVersion == null || manifest.loaderVersion.isBlank()
                || manifest.mods == null || manifest.mods.size() > MAX_MOD_COUNT) {
            throw new IOException("Manifest Sterling invalid sau incompatibil cu Fabric.");
        }
    }

    private static void validateModEntry(SterlingManifest.ModEntry entry) throws IOException {
        if (entry == null || entry.fileName == null || entry.fileName.isBlank()
                || entry.fileName.contains("/") || entry.fileName.contains("\\")
                || ".".equals(entry.fileName) || "..".equals(entry.fileName)
                || !entry.fileName.toLowerCase(Locale.ROOT).endsWith(".jar")
                || entry.sha256 == null || !entry.sha256.matches("(?i)[0-9a-f]{64}")
                || entry.downloadUrl == null || entry.downloadUrl.isBlank()) {
            throw new IOException("Intrare de mod invalidă în manifest.");
        }
        validateHttpUri(URI.create(entry.downloadUrl));
    }

    private static Path uniquePackPath(Path outputDirectory, String serverName) {
        String safeName = serverName.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").strip();
        if (safeName.isBlank()) {
            safeName = "Sterling Server";
        }
        String suffix = Long.toString(System.currentTimeMillis());
        return outputDirectory.resolve(safeName + "-" + suffix + ".mrpack");
    }

    private static void writePack(Path path, SterlingManifest manifest, List<DownloadedMod> mods) throws IOException {
        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", "sterling-" + System.currentTimeMillis());
        index.addProperty("name", manifest.serverName);
        index.addProperty("summary", "Sterling modpack for " + manifest.serverName);
        index.add("files", new JsonArray());
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", manifest.minecraftVersion);
        dependencies.addProperty("fabric-loader", manifest.loaderVersion);
        index.add("dependencies", dependencies);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            addZipEntry(zip, "modrinth.index.json", index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (DownloadedMod mod : mods) {
                zip.putNextEntry(new ZipEntry("overrides/mods/" + mod.fileName));
                Files.copy(mod.path, zip);
                zip.closeEntry();
            }
        } catch (IOException exception) {
            Files.deleteIfExists(path);
            throw exception;
        }
    }

    private static void deleteTemporaryDirectory(Path temporaryDirectory) throws IOException {
        try (var paths = Files.walk(temporaryDirectory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void addZipEntry(ZipOutputStream zip, String name, byte[] contents) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(contents);
        zip.closeEntry();
    }

    public record DownloadProgress(int completedMods, int totalMods, String currentModName) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(DownloadProgress progress);
    }

    public record InstallResult(List<String> installedMods, List<String> alreadyCurrentMods, Path backupDirectory) {
        public InstallResult {
            installedMods = List.copyOf(installedMods);
            alreadyCurrentMods = List.copyOf(alreadyCurrentMods);
        }

        public int installedCount() {
            return installedMods.size();
        }

        public int alreadyCurrentCount() {
            return alreadyCurrentMods.size();
        }
    }

    record DownloadedMod(String fileName, Path path, String sha256) {
    }

    private static final class ModrinthIndex {
        private int formatVersion;
        private String game;
        private List<ModrinthFile> files;
    }

    private static final class ModrinthFile {
        private String path;
        private java.util.Map<String, String> hashes;
        private List<String> downloads;
        private long fileSize;
        private ModrinthEnvironment env;
    }

    private static final class ModrinthEnvironment {
        private String client;
        private String server;
    }

    private static final class InstallOperation {
        private final Path target;
        private final Path backup;
        private boolean backedUp;
        private boolean installed;

        private InstallOperation(Path target, Path backup) {
            this.target = target;
            this.backup = backup;
        }
    }

    private static final class SterlingManifest {
        private String serverName;
        private String minecraftVersion;
        private String loaderType;
        private String loaderVersion;
        private String modpackDownloadLink;
        private List<ModEntry> mods;

        private static final class ModEntry {
            private String fileName;
            private String sha256;
            private String downloadUrl;
        }
    }
}
