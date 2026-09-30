package com.fx.ytdlp.ytdlp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Owns the app-managed yt-dlp binary in {@link Os#binDir()}.
 * Uses the standalone release builds so no system Python is required.
 */
public final class YtDlpBinary {
    private static final String RELEASE_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final String TAG_BASE = "https://github.com/yt-dlp/yt-dlp/releases/download/";
    private static final String LATEST_PAGE = "https://github.com/yt-dlp/yt-dlp/releases/latest";

    private final Path binary = Os.binDir().resolve(Os.exe("yt-dlp"));

    public Path path() {
        return binary;
    }

    public boolean isInstalled() {
        return Files.isRegularFile(binary) && (Os.windows() || Files.isExecutable(binary));
    }

    /** Downloads the latest release, replacing any existing binary. */
    public void install(HttpDownload.Progress progress) throws IOException, InterruptedException {
        install(RELEASE_BASE + assetName(), progress);
    }

    private void install(String url, HttpDownload.Progress progress) throws IOException, InterruptedException {
        Files.createDirectories(binary.getParent());
        Path tmp = binary.resolveSibling(binary.getFileName() + ".download");
        try {
            HttpDownload.fetch(url, tmp, progress);
            if (!Os.windows() && !tmp.toFile().setExecutable(true)) {
                throw new IOException("Could not mark " + tmp + " executable");
            }
            Files.move(tmp, binary, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public String version() throws IOException, InterruptedException {
        return run("--version").trim();
    }

    /** The newest stable release, read from where GitHub's "latest" link redirects to. */
    public String latestVersion() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(LATEST_PAGE)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        // Location: https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19
        String location = response.headers().firstValue("location")
                .orElseThrow(() -> new IOException("Could not determine the latest yt-dlp release (HTTP "
                        + response.statusCode() + ")"));
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /**
     * Replaces the binary with the latest release if it differs from the installed one.
     * Done by hand rather than with "yt-dlp -U" so the download can report progress.
     *
     * @return the new version, or null if already up to date
     */
    public String update(HttpDownload.Progress progress) throws IOException, InterruptedException {
        String latest = latestVersion();
        if (isInstalled() && latest.equals(version())) {
            return null;
        }
        install(TAG_BASE + latest + "/" + assetName(), progress);
        return latest;
    }

    /** Starts yt-dlp with UTF-8 output regardless of the Windows console code page. */
    ProcessBuilder processBuilder(List<String> args) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        return pb;
    }

    private String run(String arg) throws IOException, InterruptedException {
        Process p = processBuilder(List.of(binary.toString(), arg)).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("yt-dlp " + arg + " exited with " + code + ": " + out.trim());
        }
        return out;
    }

    private static String assetName() {
        return switch (Os.KIND) {
            case WINDOWS -> Os.ARM ? "yt-dlp_arm64.exe" : "yt-dlp.exe";
            case MAC -> "yt-dlp_macos";
            case LINUX -> Os.ARM ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
        };
    }
}
