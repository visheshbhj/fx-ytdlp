package com.fx.ytdlp.ytdlp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** OS detection and where the app keeps its tools and settings. */
public final class Os {
    public enum Kind { WINDOWS, MAC, LINUX }

    public static final Kind KIND;
    public static final boolean ARM;

    static {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        KIND = name.startsWith("windows") ? Kind.WINDOWS : name.startsWith("mac") ? Kind.MAC : Kind.LINUX;
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        ARM = arch.equals("aarch64") || arch.equals("arm64");
    }

    private static Path dataDir;

    private Os() {
    }

    public static boolean windows() {
        return KIND == Kind.WINDOWS;
    }

    /** "ffmpeg" → "ffmpeg.exe" on Windows. */
    public static String exe(String name) {
        return windows() ? name + ".exe" : name;
    }

    /**
     * Portable when packaged: a {@code data} folder next to the app, so the whole folder can be moved
     * or put on a USB stick. Falls back to the per-user location when running from the IDE or when
     * the app folder isn't writable (e.g. under Program Files).
     */
    public static synchronized Path dataDir() {
        if (dataDir == null) {
            dataDir = portableDir()
                    .filter(Os::writable)
                    .orElseGet(Os::userDataDir);
        }
        return dataDir;
    }

    public static Path binDir() {
        return dataDir().resolve("bin");
    }

    /** Finds an executable on PATH. */
    public static Optional<Path> onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir.strip(), exe(name));
                if (Files.isRegularFile(candidate) && (windows() || Files.isExecutable(candidate))) {
                    return Optional.of(candidate);
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // malformed PATH entry
            }
        }
        return Optional.empty();
    }

    /** Opens a folder in the system file manager. */
    public static void openFolder(Path dir) throws IOException {
        String opener = switch (KIND) {
            case WINDOWS -> "explorer.exe";
            case MAC -> "open";
            case LINUX -> "xdg-open";
        };
        new ProcessBuilder(opener, dir.toString()).start();
    }

    private static Optional<Path> portableDir() {
        // set by jpackage launchers: <app>\fx-ytdlp.exe on Windows, <app>/bin/fx-ytdlp on Linux
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null) {
            return Optional.empty();
        }
        Path root = Path.of(appPath).toAbsolutePath().getParent();
        if (root.getFileName() != null && root.getFileName().toString().equals("bin")) {
            root = root.getParent();
        }
        return Optional.of(root.resolve("data"));
    }

    private static boolean writable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".write", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static Path userDataDir() {
        String home = System.getProperty("user.home");
        Path base = switch (KIND) {
            case WINDOWS -> {
                String local = System.getenv("LOCALAPPDATA");
                yield local != null ? Path.of(local) : Path.of(home, "AppData", "Local");
            }
            case MAC -> Path.of(home, "Library", "Application Support");
            case LINUX -> {
                String xdg = System.getenv("XDG_DATA_HOME");
                yield xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share");
            }
        };
        return base.resolve("fx-ytdlp");
    }
}
