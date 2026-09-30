package com.fx.ytdlp.ytdlp;

import java.util.List;

/** Quality/format presets, each mapped to yt-dlp arguments. */
public enum Preset {
    BEST_VIDEO("Best video (MP4)", false,
            List.of("-f", "bv*+ba/b", "--merge-output-format", "mp4")),
    VIDEO_1080("Video ≤1080p (MP4)", false,
            List.of("-f", "bv*[height<=1080]+ba/b[height<=1080]", "--merge-output-format", "mp4")),
    VIDEO_720("Video ≤720p (MP4)", false,
            List.of("-f", "bv*[height<=720]+ba/b[height<=720]", "--merge-output-format", "mp4")),
    AUDIO_MP3("Audio MP3 (best)", true,
            List.of("-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0")),
    AUDIO_M4A("Audio M4A", true,
            List.of("-f", "ba[ext=m4a]/ba/b", "-x", "--audio-format", "m4a"));

    private final String label;
    private final boolean audioOnly;
    private final List<String> args;

    Preset(String label, boolean audioOnly, List<String> args) {
        this.label = label;
        this.audioOnly = audioOnly;
        this.args = args;
    }

    public List<String> args() {
        return args;
    }

    public boolean audioOnly() {
        return audioOnly;
    }

    @Override
    public String toString() {
        return label;
    }
}
