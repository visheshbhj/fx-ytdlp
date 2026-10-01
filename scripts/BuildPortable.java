import java.io.IOException;
import java.io.OutputStream;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a portable, self-contained package of fx-ytdlp for the OS this runs on:
 *
 * <pre>
 *   java scripts/BuildPortable.java          # app folder only, ready to run
 *   java scripts/BuildPortable.java --zip    # also an archive for copying to another machine
 * </pre>
 *
 * Needs JDK 21+ (with jpackage) and nothing else. jpackage can't cross-build, so run it on each
 * target OS. Output in target/dist (archives only with --zip):
 * <ul>
 *   <li>Windows: fx-ytdlp-VERSION-windows-x64.zip containing fx-ytdlp\fx-ytdlp.exe</li>
 *   <li>Linux:   fx-ytdlp-VERSION-linux-x64.tar.gz containing fx-ytdlp/bin/fx-ytdlp</li>
 *   <li>macOS:   fx-ytdlp-VERSION-macos-arm64.tar.gz containing fx-ytdlp.app</li>
 * </ul>
 * The app keeps yt-dlp, ffmpeg and its settings in a data folder next to itself (see Os.dataDir).
 */
public class BuildPortable {
    static final String NAME = "fx-ytdlp";
    static final String MAIN = "com.fx.ytdlp/com.fx.ytdlp.App";

    static final String OS;
    static final String ARCH;

    static {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        OS = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos" : "linux";
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        ARCH = arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "x64";
    }

    public static void main(String[] args) throws Exception {
        boolean archiveWanted = List.of(args).contains("--zip");
        Path root = projectRoot();
        String version = appVersion(root.resolve("pom.xml"));
        Path target = root.resolve("target");
        Path dist = target.resolve("dist");
        String archiveBase = NAME + "-" + version + "-" + OS + "-" + ARCH;
        System.out.println("==> Building " + archiveBase + " with Java " + Runtime.version());

        // "clean" wipes target/, including the data folder (settings, yt-dlp, ffmpeg) of a
        // previous build you've been running from target/dist; park it outside and put it back
        Path data = dist.resolve(NAME).resolve("data");
        Path parked = root.resolve(".portable-data");
        if (Files.isDirectory(data)) {
            deleteRecursively(parked);
            Files.move(data, parked);
            System.out.println("==> Keeping data folder from previous build");
        }

        mavenPackage(root);

        Path appJar = findAppJar(target);
        List<Path> modulePath = new ArrayList<>();
        modulePath.add(appJar);
        try (Stream<Path> libs = Files.list(target.resolve("libs"))) {
            // the classifier-less javafx-*.jar are empty placeholders; the -linux/-win/-mac ones are real
            libs.filter(p -> !p.getFileName().toString().matches("javafx-[a-z]+-[0-9.]+\\.jar"))
                    .sorted()
                    .forEach(modulePath::add);
        }

        deleteRecursively(dist);
        Files.createDirectories(dist);

        List<String> jpackage = new ArrayList<>(List.of(
                "--type", "app-image",
                "--name", NAME,
                "--app-version", version,
                "--vendor", "fx-ytdlp",
                "--dest", dist.toString(),
                "--module-path", String.join(java.io.File.pathSeparator, modulePath.stream().map(Path::toString).toList()),
                "--module", MAIN));
        // HTTPS to GitHub needs EC crypto; a separate module up to JDK 21, merged into java.base later
        if (ModuleFinder.ofSystem().find("jdk.crypto.ec").isPresent()) {
            jpackage.addAll(List.of("--add-modules", "jdk.crypto.ec"));
        }
        // JavaFX 21 trips the native-access / Unsafe warnings added in JDK 24; the bundled runtime is
        // this JDK, and older JVMs would refuse the flags, so only add them when they apply
        if (Runtime.version().feature() >= 24) {
            jpackage.addAll(List.of(
                    "--java-options", "--enable-native-access=javafx.graphics",
                    "--java-options", "--sun-misc-unsafe-memory-access=allow"));
        }
        System.out.println("==> jpackage (" + OS + "/" + ARCH + ")");
        ToolProvider tool = ToolProvider.findFirst("jpackage")
                .orElseThrow(() -> new IllegalStateException("jpackage not found; run this with a full JDK 21+"));
        int code = tool.run(System.out, System.err, jpackage.toArray(String[]::new));
        if (code != 0) {
            fail("jpackage failed with exit code " + code);
        }

        String image = OS.equals("macos") ? NAME + ".app" : NAME;
        if (archiveWanted) {
            archive(dist, image, archiveBase);
        }
        // after archiving, so the archive doesn't carry your settings and downloaded tools
        if (Files.isDirectory(parked) && !OS.equals("macos")) {
            Files.move(parked, data);
        }
        System.out.printf("==> Done%n    app: %s%n    run: %s%n", dist.resolve(image), launcher(dist.resolve(image)));
    }

    static void archive(Path dist, String image, String archiveBase) throws IOException, InterruptedException {
        Path archive;
        if (OS.equals("windows")) {
            archive = dist.resolve(archiveBase + ".zip");
            zip(dist, image, archive);
        } else {
            // tar keeps the executable bits that zip (via java.util.zip) would lose
            archive = dist.resolve(archiveBase + ".tar.gz");
            run(dist, List.of("tar", "-czf", archive.getFileName().toString(), image));
        }

        System.out.printf("==> Archive: %s (%d MB)%n", archive, Files.size(archive) >> 20);
    }

    static Path projectRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("pom.xml")) && Files.isDirectory(p.resolve("scripts"))) {
                return p;
            }
        }
        return fail("Run this from the fx-ytdlp project folder");
    }

    /** jpackage wants a plain numeric version, so "1.0-SNAPSHOT" becomes "1.0". */
    static String appVersion(Path pom) throws IOException {
        String xml = Files.readString(pom);
        // the project's own <version> is the first one after its <artifactId>, before <dependencies>
        Matcher m = Pattern.compile("<artifactId>ytdlp</artifactId>\\s*<version>([^<]+)</version>").matcher(xml);
        if (!m.find()) {
            return "1.0";
        }
        Matcher numeric = Pattern.compile("^\\d+(\\.\\d+){0,2}").matcher(m.group(1).strip());
        return numeric.find() ? numeric.group() : "1.0";
    }

    static void mavenPackage(Path root) throws IOException, InterruptedException {
        System.out.println("==> Maven package");
        List<String> cmd = OS.equals("windows")
                ? List.of("cmd.exe", "/c", "mvnw.cmd", "-q", "-DskipTests", "clean", "package")
                : List.of("./mvnw", "-q", "-DskipTests", "clean", "package");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).inheritIO();
        // build with the same JDK that runs this script
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        int code = pb.start().waitFor();
        if (code != 0) {
            fail("Maven build failed with exit code " + code);
        }
    }

    static Path findAppJar(Path target) throws IOException {
        try (Stream<Path> s = Files.list(target)) {
            return s.filter(p -> p.getFileName().toString().matches("ytdlp-.*\\.jar"))
                    .findFirst()
                    .orElseGet(() -> fail("No ytdlp-*.jar in " + target));
        }
    }

    static void zip(Path baseDir, String folder, Path archive) throws IOException {
        System.out.println("==> Zipping " + archive.getFileName());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive));
             Stream<Path> files = Files.walk(baseDir.resolve(folder))) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String entry = baseDir.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p)) {
                    out.putNextEntry(new ZipEntry(entry + "/"));
                } else {
                    out.putNextEntry(new ZipEntry(entry));
                    Files.copy(p, out);
                }
                out.closeEntry();
            }
        }
    }

    static String launcher(Path image) {
        return switch (OS) {
            case "windows" -> image.resolve(NAME + ".exe").toString();
            case "macos" -> "open " + image;
            default -> image.resolve("bin").resolve(NAME).toString();
        };
    }

    static void run(Path dir, List<String> cmd) throws IOException, InterruptedException {
        int code = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start().waitFor();
        if (code != 0) {
            fail(String.join(" ", cmd) + " failed with exit code " + code);
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    static <T> T fail(String message) {
        System.err.println("ERROR: " + message);
        System.exit(1);
        return null;
    }
}
