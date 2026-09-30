package com.fx.ytdlp;

import com.fx.ytdlp.ytdlp.AdvancedOptions;
import com.fx.ytdlp.ytdlp.Cover;
import com.fx.ytdlp.ytdlp.Preset;

import com.fx.ytdlp.ytdlp.Os;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Remembers the last-used form values between launches, in settings.properties inside the data
 * folder (next to the app when portable) rather than the registry.
 */
final class Settings {
    private final Store prefs = new Store(Os.dataDir().resolve("settings.properties"));

    /** Properties file with a java.util.prefs-like API; saved on every change. */
    private static final class Store {
        private final Path file;
        private final Properties props = new Properties();

        Store(Path file) {
            this.file = file;
            if (Files.isRegularFile(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    props.load(r);
                } catch (IOException e) {
                    // unreadable settings: start from defaults
                }
            }
        }

        String get(String key, String def) {
            return props.getProperty(key, def);
        }

        boolean getBoolean(String key, boolean def) {
            String v = props.getProperty(key);
            return v == null ? def : Boolean.parseBoolean(v);
        }

        void put(String key, String value) {
            if (!value.equals(props.put(key, value))) {
                save();
            }
        }

        void putBoolean(String key, boolean value) {
            put(key, Boolean.toString(value));
        }

        private void save() {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    props.store(w, "fx-ytdlp settings");
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    Path outputDir() {
        String def = Path.of(System.getProperty("user.home"), "Downloads").toString();
        return Path.of(prefs.get("outputDir", def));
    }

    void outputDir(Path dir) {
        prefs.put("outputDir", dir.toString());
    }

    Preset preset() {
        try {
            return Preset.valueOf(prefs.get("preset", Preset.BEST_VIDEO.name()));
        } catch (IllegalArgumentException e) {
            return Preset.BEST_VIDEO;
        }
    }

    void preset(Preset preset) {
        prefs.put("preset", preset.name());
    }

    Cover cover() {
        try {
            return Cover.valueOf(prefs.get("cover", Cover.THUMBNAIL.name()));
        } catch (IllegalArgumentException e) {
            return Cover.THUMBNAIL;
        }
    }

    void cover(Cover cover) {
        prefs.put("cover", cover.name());
    }

    AdvancedOptions advanced() {
        AdvancedOptions d = AdvancedOptions.defaults();
        AdvancedOptions.SponsorBlock sponsorBlock;
        try {
            sponsorBlock = AdvancedOptions.SponsorBlock.valueOf(prefs.get("adv.sponsorBlock", d.sponsorBlock().name()));
        } catch (IllegalArgumentException e) {
            sponsorBlock = d.sponsorBlock();
        }
        return new AdvancedOptions(
                prefs.getBoolean("adv.subtitles", d.subtitles()),
                prefs.get("adv.subtitleLangs", d.subtitleLangs()),
                prefs.getBoolean("adv.autoSubtitles", d.autoSubtitles()),
                sponsorBlock,
                prefs.getBoolean("adv.embedChapters", d.embedChapters()),
                prefs.getBoolean("adv.skipDownloaded", d.skipDownloaded()),
                prefs.get("adv.speedLimit", d.speedLimit()),
                prefs.get("adv.cookiesBrowser", d.cookiesBrowser()),
                prefs.get("adv.outputTemplate", d.outputTemplate()),
                prefs.get("adv.extraArgs", d.extraArgs()));
    }

    void advanced(AdvancedOptions o) {
        prefs.putBoolean("adv.subtitles", o.subtitles());
        prefs.put("adv.subtitleLangs", o.subtitleLangs());
        prefs.putBoolean("adv.autoSubtitles", o.autoSubtitles());
        prefs.put("adv.sponsorBlock", o.sponsorBlock().name());
        prefs.putBoolean("adv.embedChapters", o.embedChapters());
        prefs.putBoolean("adv.skipDownloaded", o.skipDownloaded());
        prefs.put("adv.speedLimit", o.speedLimit());
        prefs.put("adv.cookiesBrowser", o.cookiesBrowser());
        prefs.put("adv.outputTemplate", o.outputTemplate());
        prefs.put("adv.extraArgs", o.extraArgs());
    }

    boolean advancedExpanded() {
        return prefs.getBoolean("adv.expanded", false);
    }

    void advancedExpanded(boolean value) {
        prefs.putBoolean("adv.expanded", value);
    }

    boolean playlist() {
        return prefs.getBoolean("playlist", false);
    }

    void playlist(boolean value) {
        prefs.putBoolean("playlist", value);
    }
}
