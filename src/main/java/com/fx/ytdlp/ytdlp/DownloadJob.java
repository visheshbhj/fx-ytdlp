package com.fx.ytdlp.ytdlp;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;

/**
 * One queued download. Observable properties must only be mutated on the FX thread.
 */
public final class DownloadJob {
    public enum Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    private final String url;
    private final Preset preset;
    private final boolean embedThumbnail;
    private final boolean playlist;
    private final Path outputDir;

    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.QUEUED);
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty title = new SimpleStringProperty();
    private final StringProperty detail = new SimpleStringProperty("");
    private final StringBuilder log = new StringBuilder();

    private volatile boolean cancelRequested;

    public DownloadJob(String url, Preset preset, boolean embedThumbnail, boolean playlist, Path outputDir) {
        this.url = url;
        this.preset = preset;
        this.embedThumbnail = embedThumbnail;
        this.playlist = playlist;
        this.outputDir = outputDir;
        this.title.set(url);
    }

    /** A fresh copy of this job for retrying. */
    public DownloadJob copy() {
        return new DownloadJob(url, preset, embedThumbnail, playlist, outputDir);
    }

    public String url() { return url; }
    public Preset preset() { return preset; }
    public boolean embedThumbnail() { return embedThumbnail; }
    public boolean playlist() { return playlist; }
    public Path outputDir() { return outputDir; }

    public ObjectProperty<Status> statusProperty() { return status; }
    public DoubleProperty progressProperty() { return progress; }
    public StringProperty titleProperty() { return title; }
    /** Speed / ETA / playlist position, human readable. */
    public StringProperty detailProperty() { return detail; }

    public boolean isFinished() {
        Status s = status.get();
        return s == Status.DONE || s == Status.FAILED || s == Status.CANCELLED;
    }

    public boolean isCancelRequested() { return cancelRequested; }
    void requestCancel() { cancelRequested = true; }

    public synchronized String log() { return log.toString(); }
    synchronized void appendLog(String line) { log.append(line).append('\n'); }
}
