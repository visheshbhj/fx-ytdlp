package com.fx.ytdlp.ytdlp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Locates ffmpeg: the app-managed copy in {@link Os#binDir()} first, then PATH. On Windows, where
 * ffmpeg is rarely installed, it can be downloaded from yt-dlp's own ffmpeg builds.
 */
public final class Ffmpeg {
    private static final String WINDOWS_BUILD = "https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/"
            + (Os.ARM ? "ffmpeg-master-latest-winarm64-gpl.zip" : "ffmpeg-master-latest-win64-gpl.zip");

    private final Path managed = Os.binDir().resolve(Os.exe("ffmpeg"));

    /** The ffmpeg executable to use, if any. */
    public Optional<Path> find() {
        if (Files.isRegularFile(managed)) {
            return Optional.of(managed);
        }
        return Os.onPath("ffmpeg");
    }

    /** Whether {@link #install} is supported here; elsewhere ffmpeg comes from the system package manager. */
    public boolean canInstall() {
        return Os.windows();
    }

    /** Downloads the Windows build and extracts ffmpeg.exe and ffprobe.exe into the bin folder. */
    public void install(HttpDownload.Progress progress) throws IOException, InterruptedException {
        if (!canInstall()) {
            throw new IOException("Install ffmpeg with your package manager (e.g. sudo apt install ffmpeg)");
        }
        Files.createDirectories(managed.getParent());
        Path zip = managed.resolveSibling("ffmpeg.zip.download");
        try {
            HttpDownload.fetch(WINDOWS_BUILD, zip, progress);
            extractFrom(zip, managed.getParent());
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    /** Pulls bin/ffmpeg.exe and bin/ffprobe.exe out of a yt-dlp FFmpeg-Builds zip. */
    static void extractFrom(Path zip, Path dir) throws IOException {
        int found = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                // entries look like ffmpeg-master-latest-win64-gpl/bin/ffmpeg.exe
                String name = e.getName();
                if (name.endsWith("/bin/ffmpeg.exe") || name.endsWith("/bin/ffprobe.exe")) {
                    extract(in, dir.resolve(name.substring(name.lastIndexOf('/') + 1)));
                    found++;
                }
            }
        }
        if (found < 2) {
            throw new IOException("ffmpeg.exe/ffprobe.exe not found in " + WINDOWS_BUILD);
        }
    }

    private static void extract(InputStream in, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
