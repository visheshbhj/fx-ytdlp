package com.fx.ytdlp.ytdlp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extra yt-dlp options from the Advanced panel, captured per job when it is queued.
 *
 * @param speedLimit      e.g. "5M", "500K"; blank for unlimited
 * @param cookiesBrowser  browser name for --cookies-from-browser; blank for none
 * @param outputTemplate  yt-dlp -o template, relative to the output folder
 * @param extraArgs       raw arguments appended verbatim (shell-style quoting)
 */
public record AdvancedOptions(
        boolean subtitles,
        String subtitleLangs,
        boolean autoSubtitles,
        SponsorBlock sponsorBlock,
        boolean embedChapters,
        boolean skipDownloaded,
        String speedLimit,
        String cookiesBrowser,
        String outputTemplate,
        String extraArgs) {

    /**
     * Playlists go in their own folder with a zero-padded index prefix. A "/" inside a field gets
     * sanitized, so single videos fall back to "." instead.
     */
    public static final String DEFAULT_TEMPLATE =
            "%(playlist_title|.)s/%(playlist_index&{:03d} - |)s%(title)s [%(id)s].%(ext)s";
    public static final String DEFAULT_SUB_LANGS = "en";
    public static final List<String> BROWSERS =
            List.of("", "firefox", "chrome", "chromium", "brave", "edge", "opera", "vivaldi");
    /** Stored in the output folder, so each folder remembers what it already has. */
    public static final String ARCHIVE_FILE = ".fx-ytdlp-archive.txt";

    private static final Pattern RATE = Pattern.compile("\\d+(\\.\\d+)?[KMG]?", Pattern.CASE_INSENSITIVE);

    public enum SponsorBlock {
        OFF("Off", List.of()),
        MARK("Mark as chapters", List.of("--sponsorblock-mark", "all")),
        REMOVE("Cut sponsors/self-promo", List.of("--sponsorblock-remove", "sponsor,selfpromo,interaction")),
        REMOVE_MUSIC("Cut + non-music parts", List.of("--sponsorblock-remove",
                "sponsor,selfpromo,interaction,intro,outro,preview,music_offtopic"));

        private final String label;
        private final List<String> args;

        SponsorBlock(String label, List<String> args) {
            this.label = label;
            this.args = args;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static AdvancedOptions defaults() {
        return new AdvancedOptions(false, DEFAULT_SUB_LANGS, false, SponsorBlock.OFF, true, false,
                "", "", DEFAULT_TEMPLATE, "");
    }

    /** Returns a problem description for the UI, or null if the options are usable. */
    public String validate() {
        if (!speedLimit.isBlank() && !RATE.matcher(speedLimit.strip()).matches()) {
            return "Speed limit must look like 500K, 5M or 1.5M";
        }
        try {
            splitArgs(extraArgs);
        } catch (IllegalArgumentException e) {
            return "Extra arguments: " + e.getMessage();
        }
        return null;
    }

    public String template() {
        return outputTemplate.isBlank() ? DEFAULT_TEMPLATE : outputTemplate.strip();
    }

    List<String> args(Preset preset, Path outputDir) {
        List<String> a = new ArrayList<>();
        // subtitles can't be embedded into audio files
        if (subtitles && !preset.audioOnly()) {
            // --embed-subs alone fetches them and deletes the files after; --write-subs would keep them
            a.addAll(List.of("--embed-subs",
                    "--sub-langs", subtitleLangs.isBlank() ? DEFAULT_SUB_LANGS : subtitleLangs.strip()));
            if (autoSubtitles) {
                a.add("--write-auto-subs");
            }
        }
        a.addAll(sponsorBlock.args);
        if (embedChapters) {
            a.add("--embed-chapters");
        }
        if (skipDownloaded) {
            a.addAll(List.of("--download-archive", outputDir.resolve(ARCHIVE_FILE).toString()));
        }
        if (!speedLimit.isBlank()) {
            a.addAll(List.of("--limit-rate", speedLimit.strip()));
        }
        if (!cookiesBrowser.isBlank()) {
            a.addAll(List.of("--cookies-from-browser", cookiesBrowser));
        }
        a.addAll(splitArgs(extraArgs));
        return a;
    }

    /** Splits on whitespace, honouring single/double quotes and backslash escapes. */
    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
            } else if (c == '\\' && i + 1 < s.length()) {
                cur.append(s.charAt(++i));
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("unclosed " + quote + " quote");
        }
        if (inToken) {
            out.add(cur.toString());
        }
        return out;
    }
}
