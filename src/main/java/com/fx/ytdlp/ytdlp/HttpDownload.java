package com.fx.ytdlp.ytdlp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/** Plain HTTPS file download with progress, used to fetch the tools the app manages. */
public final class HttpDownload {
    /** Receives bytes downloaded so far and the total (-1 if unknown). */
    @FunctionalInterface
    public interface Progress {
        void update(long done, long total);
    }

    private HttpDownload() {
    }

    static void fetch(String url, Path target, Progress progress) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Downloading " + url + " failed: HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("content-length").orElse(-1);
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            long lastReport = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (done - lastReport >= 1 << 20) {
                    progress.update(done, total);
                    lastReport = done;
                }
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
            progress.update(done, total);
        }
    }
}
