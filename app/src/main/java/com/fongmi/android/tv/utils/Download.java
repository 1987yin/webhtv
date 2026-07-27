package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.common.net.HttpHeaders;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

import okhttp3.Response;

public class Download {

    private final File file;
    private final String url;
    private Callback callback;
    private Future<?> future;
    private String tag;
    private Map<String, String> headers;
    private volatile boolean canceled;
    private volatile boolean paused;

    public static Download create(String url, File file) {
        return new Download(GithubProxy.apply(url), file);
    }

    public Download(String url, File file) {
        this.tag = url;
        this.url = url;
        this.file = file;
    }

    public Download tag(String tag) {
        this.tag = tag;
        return this;
    }

    public Download headers(Map<String, String> headers) {
        this.headers = headers;
        return this;
    }

    public File get() {
        doInBackground();
        return file;
    }

    public void start(Callback callback) {
        this.callback = callback;
        this.canceled = false;
        this.paused = false;
        future = Task.submit(this::doInBackground);
    }

    public void pause() {
        paused = true;
        OkHttp.cancel(tag);
        if (future != null) future.cancel(true);
    }

    public void resume() {
        if (!paused) return;
        paused = false;
        future = Task.submit(this::doInBackground);
    }

    public Download cancel() {
        canceled = true;
        paused = false;
        if (future != null) future.cancel(true);
        OkHttp.cancel(tag);
        Path.clear(file);
        future = null;
        return this;
    }

    private void doInBackground() {
        long offset = file.exists() ? file.length() : 0;
        try {
            try (Response res = open(offset)) {
                if (!res.isSuccessful()) throw new IOException("Download failed: HTTP " + res.code());
                if (res.body() == null) throw new IOException("Download failed: empty response");
                boolean partial = res.code() == 206;
                long remaining = getLength(res);
                long total;
                if (partial) {
                    String contentRange = res.header(HttpHeaders.CONTENT_RANGE);
                    total = parseTotal(contentRange, offset + (remaining > 0 ? remaining : 0));
                } else {
                    // Server ignored the Range header; restart from the beginning.
                    offset = 0;
                    total = remaining;
                }
                boolean completed = download(res.body().byteStream(), offset, total);
                if (!completed || canceled) {
                    if (paused) return;
                    Path.clear(file);
                    return;
                }
                if (callback != null) App.post(() -> {
                    if (!canceled && !paused) callback.success(file);
                });
            } catch (Exception e) {
                if (canceled) return;
                if (paused) return;
                if (isCanceled(e)) return;
                Path.clear(file);
                if (callback != null) App.post(() -> callback.error(e.getMessage()));
                else throw new RuntimeException(e.getMessage(), e);
            }
        } finally {
            // 无论成功/失败/暂停/取消，下载线程退出时都通知一次，便于上层释放并发名额。
            if (callback != null) App.post(callback::finish);
        }
    }

    private Response open(long offset) throws IOException {
        if (offset <= 0) {
            return headers != null ? OkHttp.newCall(url, headers, tag).execute() : OkHttp.newCall(url, tag).execute();
        }
        Map<String, String> hdrs = new HashMap<>();
        if (headers != null) hdrs.putAll(headers);
        hdrs.put(HttpHeaders.RANGE, "bytes=" + offset + "-");
        return OkHttp.newCall(url, hdrs, tag).execute();
    }

    private boolean download(InputStream is, long offset, long total) throws IOException {
        boolean append = offset > 0;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        // 不能用 Path.create(file)：文件已存在时会先删除再创建，导致 append 续传时文件被清空、
        // 数据从位置 0 错位写入而损坏。这里直接以 append 模式打开已有文件。
        try (BufferedInputStream input = new BufferedInputStream(is); FileOutputStream os = new FileOutputStream(file, append)) {
            byte[] buffer = new byte[16384];
            int readBytes;
            int lastProgress = -1;
            long totalBytes = offset;
            long startTime = System.currentTimeMillis();
            long lastNotifyTime = startTime;
            long lastNotifyBytes = offset;
            while ((readBytes = input.read(buffer)) != -1) {
                if (canceled || Thread.currentThread().isInterrupted()) return false;
                if (paused) return false;
                totalBytes += readBytes;
                os.write(buffer, 0, readBytes);
                if (callback == null) continue;
                long now = System.currentTimeMillis();
                int progress = total > 0 ? (int) (totalBytes * 100.0 / total) : -1;
                boolean shouldNotify = progress != lastProgress || now - lastNotifyTime >= 1000;
                if (!shouldNotify) continue;
                long deltaTime = Math.max(1, now - lastNotifyTime);
                long speed = (totalBytes - lastNotifyBytes) * 1000 / deltaTime;
                long elapsed = now - startTime;
                lastProgress = progress;
                lastNotifyTime = now;
                lastNotifyBytes = totalBytes;
                long bytes = totalBytes;
                long tot = total;
                App.post(() -> callback.progress(progress, bytes, tot, speed, elapsed));
            }
            if (total > 0 && totalBytes < total) throw new IOException("Download incomplete");
            return !canceled && !paused;
        }
    }

    private boolean isCanceled(Exception e) {
        String message = e.getMessage();
        return "Canceled".equals(message) || "Socket closed".equals(message) || "Paused".equals(message);
    }

    private long getLength(Response res) {
        try {
            String header = res.header(HttpHeaders.CONTENT_LENGTH);
            return header != null ? Long.parseLong(header) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private long parseTotal(String contentRange, long fallback) {
        if (TextUtils.isEmpty(contentRange)) return fallback;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return fallback;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public interface Callback {

        void progress(int progress);

        default void progress(int progress, long bytes, long total, long speed, long elapsed) {
            progress(progress);
        }

        void error(String msg);

        void success(File file);

        // 下载线程退出时（成功/失败/暂停/取消任意路径）回调一次，供上层释放并发名额。
        default void finish() {
        }
    }
}
