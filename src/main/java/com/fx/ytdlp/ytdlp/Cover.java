package com.fx.ytdlp.ytdlp;

import java.util.List;

/** Where the embedded cover art comes from. */
public enum Cover {
    NONE("No cover", List.of()),
    THUMBNAIL("Thumbnail", List.of(
            "--embed-thumbnail", "--convert-thumbnails", "jpg",
            "--ppa", "ThumbnailsConvertor+FFmpeg_o:-c:v mjpeg -q:v 2")),
    THUMBNAIL_SQUARE("Thumbnail, square crop", List.of(
            "--embed-thumbnail", "--convert-thumbnails", "jpg",
            // centre crop to the shorter side; drops the blurred bars around square album art
            "--ppa", "ThumbnailsConvertor+FFmpeg_o:-c:v mjpeg -q:v 2 "
                    + "-vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"")),
    /**
     * Audio only: after download, grab a full-resolution frame from the video stream and embed it.
     * The thumbnail is still embedded first as a fallback. Behaves like THUMBNAIL for video presets.
     */
    VIDEO_FRAME("Video frame (full res)", THUMBNAIL.args);

    private final String label;
    private final List<String> args;

    Cover(String label, List<String> args) {
        this.label = label;
        this.args = args;
    }

    public List<String> args() {
        return args;
    }

    @Override
    public String toString() {
        return label;
    }
}
