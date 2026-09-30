package com.fx.ytdlp;

import com.fx.ytdlp.ytdlp.Preset;

import java.nio.file.Path;
import java.util.prefs.Preferences;

/** Remembers the last-used form values between launches. */
final class Settings {
    private final Preferences prefs = Preferences.userNodeForPackage(Settings.class);

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

    boolean embedThumbnail() {
        return prefs.getBoolean("embedThumbnail", true);
    }

    void embedThumbnail(boolean value) {
        prefs.putBoolean("embedThumbnail", value);
    }

    boolean playlist() {
        return prefs.getBoolean("playlist", false);
    }

    void playlist(boolean value) {
        prefs.putBoolean("playlist", value);
    }
}
