package com.fx.ytdlp.ytdlp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Owns the app-managed yt-dlp binary in ~/.local/share/fx-ytdlp/bin.
 * Uses the standalone (PyInstaller) release so no system Python is required.
 */
public final class YtDlpBinary {
    private static final String RELEASE_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";

    private final Path binary;

    public YtDlpBinary() {
        String dataHome = System.getenv("XDG_DATA_HOME");
        Path base = (dataHome == null || dataHome.isBlank())
                ? Path.of(System.getProperty("user.home"), ".local", "share")
                : Path.of(dataHome);
        this.binary = base.resolve("fx-ytdlp").resolve("bin").resolve("yt-dlp");
    }

    public Path path() {
        return binary;
    }

    public boolean isInstalled() {
        return Files.isExecutable(binary);
    }

    /** Downloads the latest release, replacing any existing binary atomically. */
    public void install() throws IOException, InterruptedException {
        Files.createDirectories(binary.getParent());
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASE_BASE + assetName())).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Downloading yt-dlp failed: HTTP " + response.statusCode());
        }
        Path tmp = binary.resolveSibling("yt-dlp.download");
        try (InputStream in = response.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!tmp.toFile().setExecutable(true)) {
            throw new IOException("Could not mark " + tmp + " executable");
        }
        Files.move(tmp, binary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public String version() throws IOException, InterruptedException {
        return run("--version").trim();
    }

    /** Runs yt-dlp's self-updater; returns its output. */
    public String update() throws IOException, InterruptedException {
        return run("-U").trim();
    }

    private String run(String arg) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(binary.toString(), arg).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("yt-dlp " + arg + " exited with " + code + ": " + out.trim());
        }
        return out;
    }

    private static String assetName() {
        String arch = System.getProperty("os.arch");
        return switch (arch) {
            case "aarch64", "arm64" -> "yt-dlp_linux_aarch64";
            default -> "yt-dlp_linux";
        };
    }
}
