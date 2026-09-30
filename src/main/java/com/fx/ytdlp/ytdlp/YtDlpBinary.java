package com.fx.ytdlp.ytdlp;

import java.io.IOException;
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

    private final Path binary = Os.binDir().resolve(Os.exe("yt-dlp"));

    public Path path() {
        return binary;
    }

    public boolean isInstalled() {
        return Files.isRegularFile(binary) && (Os.windows() || Files.isExecutable(binary));
    }

    /** Downloads the latest release, replacing any existing binary. */
    public void install(HttpDownload.Progress progress) throws IOException, InterruptedException {
        Files.createDirectories(binary.getParent());
        Path tmp = binary.resolveSibling(binary.getFileName() + ".download");
        try {
            HttpDownload.fetch(RELEASE_BASE + assetName(), tmp, progress);
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

    /** Runs yt-dlp's self-updater; returns its output. */
    public String update() throws IOException, InterruptedException {
        return run("-U").trim();
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
