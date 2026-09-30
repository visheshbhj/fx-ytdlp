package com.fx.ytdlp.ytdlp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Function;

/**
 * Replaces an audio file's cover with a frame taken from the video stream. ffmpeg seeks over
 * HTTP, so only a few MB of the video are fetched.
 */
final class VideoFrameCover {
    private final Path ytDlp;
    /** Starts a process so that it can be cancelled along with the job. */
    private final Function<ProcessBuilder, Process> starter;

    VideoFrameCover(Path ytDlp, Function<ProcessBuilder, Process> starter) {
        this.ytDlp = ytDlp;
        this.starter = starter;
    }

    void apply(Path audio, String videoUrl, double durationSeconds)
            throws IOException, InterruptedException {
        String streamUrl = run(List.of(ytDlp.toString(), "-g", "-f", "bv*", "--no-playlist", "--", videoUrl))
                .lines().findFirst()
                .orElseThrow(() -> new IOException("no video stream for " + videoUrl));

        Path frame = Files.createTempFile("fx-ytdlp-cover", ".jpg");
        String name = audio.getFileName().toString();
        Path tmp = audio.resolveSibling(".cover-" + name);
        try {
            // a quarter in skips intros/fade-ins; static-art uploads look the same everywhere
            String seek = String.valueOf(Math.max(0, durationSeconds / 4));
            run(List.of("ffmpeg", "-v", "error", "-y", "-ss", seek, "-i", streamUrl,
                    "-frames:v", "1", "-q:v", "2", frame.toString()));
            run(List.of("ffmpeg", "-v", "error", "-y", "-i", audio.toString(), "-i", frame.toString(),
                    "-map", "0:a", "-map", "1:0", "-c", "copy", "-map_metadata", "0",
                    "-id3v2_version", "3", "-disposition:v:0", "attached_pic", tmp.toString()));
            Files.move(tmp, audio, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(frame);
            Files.deleteIfExists(tmp);
        }
    }

    private String run(List<String> cmd) throws IOException, InterruptedException {
        Process p;
        try {
            p = starter.apply(new ProcessBuilder(cmd).redirectErrorStream(true));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException(cmd.get(0) + " exited with " + code + ": " + out.trim());
        }
        return out;
    }
}
