package com.fx.ytdlp.ytdlp;

import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs download jobs one at a time by spawning yt-dlp and parsing its templated output.
 */
public final class DownloadQueue {
    private static final String SEP = "\u001F"; // unit separator; won't appear in titles

    private final YtDlpBinary binary;
    private final Ffmpeg ffmpeg;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "yt-dlp-worker");
        t.setDaemon(true);
        return t;
    });

    /** Opened once yt-dlp/ffmpeg setup has finished (successfully or not). */
    private final CountDownLatch toolsReady = new CountDownLatch(1);
    private volatile Process current;
    private volatile DownloadJob currentJob;

    public DownloadQueue(YtDlpBinary binary, Ffmpeg ffmpeg) {
        this.binary = binary;
        this.ffmpeg = ffmpeg;
    }

    /** Lets queued jobs start; call when tool setup is done, even if it failed. */
    public void toolsReady() {
        toolsReady.countDown();
    }

    public void submit(DownloadJob job) {
        worker.submit(() -> run(job));
    }

    public void cancel(DownloadJob job) {
        job.requestCancel();
        if (job == currentJob) {
            killCurrent();
        } else if (!job.isFinished()) {
            fx(() -> job.statusProperty().set(DownloadJob.Status.CANCELLED));
        }
    }

    public void shutdown() {
        worker.shutdownNow();
        killCurrent();
    }

    private void killCurrent() {
        Process p = current;
        if (p != null) {
            // yt-dlp spawns ffmpeg (and the standalone build is a bootloader + python child)
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }

    List<String> buildCommand(DownloadJob job) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.path().toString());
        // piped output would otherwise use the Windows console code page and mangle titles
        cmd.addAll(List.of("--encoding", "utf-8"));
        ffmpeg.find().ifPresent(f -> cmd.addAll(List.of("--ffmpeg-location", f.toString())));
        cmd.addAll(job.preset().args());
        cmd.addAll(job.cover().args());
        if (wantsVideoFrame(job)) {
            cmd.addAll(List.of("--print", "after_move:FILE" + SEP + "%(filepath)s" + SEP
                    + "%(webpage_url)s" + SEP + "%(duration|0)s"));
        }
        cmd.add(job.playlist() ? "--yes-playlist" : "--no-playlist");
        cmd.addAll(List.of(
                "--embed-metadata",
                "--ignore-errors",
                "-P", job.outputDir().toString(),
                "-o", job.options().template(),
                // --print implies --quiet; --progress keeps the progress lines coming
                "--progress", "--newline", "--no-simulate",
                "--print", "before_dl:TITLE" + SEP + "%(title)s" + SEP
                        + "%(playlist_index|)s" + SEP + "%(n_entries|)s",
                "--progress-template", "download:PROG" + SEP
                        + "%(progress.downloaded_bytes|0)s" + SEP
                        + "%(progress.total_bytes,progress.total_bytes_estimate|0)s" + SEP
                        + "%(progress.speed|0)s" + SEP
                        + "%(progress.eta|)s",
                "--progress-template", "postprocess:POST" + SEP + "%(progress.postprocessor)s"));
        // last, so extra args can override the defaults above
        cmd.addAll(job.options().args(job.preset(), job.outputDir()));
        cmd.addAll(List.of("--", job.url()));
        return cmd;
    }

    private void run(DownloadJob job) {
        try {
            toolsReady.await();
        } catch (InterruptedException e) {
            return;
        }
        if (job.isCancelRequested()) {
            return;
        }
        currentJob = job;
        fx(() -> job.statusProperty().set(DownloadJob.Status.RUNNING));
        String lastError = null;
        String position = "";
        List<String[]> finished = new ArrayList<>();
        try {
            ProcessBuilder pb = binary.processBuilder(buildCommand(job)).redirectErrorStream(true);
            job.appendLog("$ " + String.join(" ", pb.command()));
            current = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split(SEP, -1);
                    switch (f[0]) {
                        case "TITLE" -> {
                            position = f[2].isEmpty() ? "" : "[" + f[2] + "/" + f[3] + "] ";
                            String pos = position;
                            fx(() -> {
                                job.titleProperty().set(pos + f[1]);
                                job.progressProperty().set(0);
                            });
                        }
                        case "PROG" -> updateProgress(job, position, f);
                        case "POST" -> {
                            String detail = position + "Post-processing: " + f[1];
                            fx(() -> job.detailProperty().set(detail));
                        }
                        case "FILE" -> finished.add(f);
                        default -> {
                            job.appendLog(line);
                            if (line.startsWith("ERROR:")) {
                                lastError = line;
                            }
                        }
                    }
                }
            }
            int code = current.waitFor();
            String coverError = applyVideoFrames(job, finished);
            String err = lastError != null ? lastError : coverError;
            fx(() -> {
                if (job.isCancelRequested()) {
                    job.statusProperty().set(DownloadJob.Status.CANCELLED);
                    job.detailProperty().set("");
                } else if (code == 0 && coverError == null) {
                    job.statusProperty().set(DownloadJob.Status.DONE);
                    job.progressProperty().set(1);
                    job.detailProperty().set("");
                } else {
                    // with --ignore-errors a playlist can partially succeed and still exit 1
                    job.statusProperty().set(DownloadJob.Status.FAILED);
                    job.detailProperty().set(err != null ? err : "yt-dlp exited with " + code);
                }
            });
        } catch (IOException e) {
            job.appendLog(e.toString());
            fx(() -> {
                job.statusProperty().set(DownloadJob.Status.FAILED);
                job.detailProperty().set(e.getMessage());
            });
        } catch (InterruptedException e) {
            killCurrent();
            Thread.currentThread().interrupt();
        } finally {
            current = null;
            currentJob = null;
        }
    }

    private static boolean wantsVideoFrame(DownloadJob job) {
        return job.cover() == Cover.VIDEO_FRAME && job.preset().audioOnly();
    }

    /**
     * Swaps each downloaded file's thumbnail cover for a video frame.
     * Returns an error message if any failed (those files keep the thumbnail), else null.
     */
    private String applyVideoFrames(DownloadJob job, List<String[]> files) throws InterruptedException {
        Path ffmpegPath = ffmpeg.find().orElse(null);
        if (ffmpegPath == null) {
            return "ffmpeg not found, kept thumbnail";
        }
        VideoFrameCover covers = new VideoFrameCover(binary, ffmpegPath, this::startTracked);
        int failed = 0;
        for (int i = 0; i < files.size() && !job.isCancelRequested(); i++) {
            String[] f = files.get(i);
            String detail = "Cover art from video" + (files.size() > 1 ? " " + (i + 1) + "/" + files.size() : "");
            fx(() -> job.detailProperty().set(detail));
            try {
                covers.apply(Path.of(f[1]), f[2], parse(f[3]));
            } catch (IOException e) {
                failed++;
                job.appendLog("Video frame cover failed for " + f[1] + ": " + e.getMessage());
            }
        }
        return failed == 0 ? null
                : "Video frame cover failed for " + failed + " file(s), kept thumbnail (see log)";
    }

    private Process startTracked(ProcessBuilder pb) {
        try {
            current = pb.start();
            return current;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void updateProgress(DownloadJob job, String position, String[] f) {
        double done = parse(f[1]);
        double total = parse(f[2]);
        double speed = parse(f[3]);
        String eta = f[4].isEmpty() || f[4].equals("NA") ? "" : " · ETA " + formatEta((long) parse(f[4]));
        double fraction = total > 0 ? done / total : -1; // -1 = indeterminate
        String detail = position + formatBytes(done)
                + (total > 0 ? " / " + formatBytes(total) : "")
                + (speed > 0 ? " · " + formatBytes(speed) + "/s" : "")
                + eta;
        fx(() -> {
            job.progressProperty().set(fraction);
            job.detailProperty().set(detail);
        });
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String formatBytes(double b) {
        String[] units = {"B", "KiB", "MiB", "GiB"};
        int i = 0;
        while (b >= 1024 && i < units.length - 1) {
            b /= 1024;
            i++;
        }
        return String.format(i == 0 ? "%.0f %s" : "%.1f %s", b, units[i]);
    }

    private static String formatEta(long s) {
        return s >= 3600
                ? String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
    }

    private static void fx(Runnable r) {
        Platform.runLater(r);
    }
}
