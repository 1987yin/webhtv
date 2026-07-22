package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.DownloadItem;
import com.github.catvod.net.OkHttp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Response;

public class M3u8Downloader {

    private static final int SEGMENT_THREADS = 4;
    private static final int SEGMENT_RETRY = 3;
    private static final long PROGRESS_INTERVAL = 400;

    public interface ProgressListener {
        void onProgress(int percent, long bytes, long total, long speed);
    }

    private static class CanceledException extends Exception {
        CanceledException() {
            super("Canceled");
        }
    }

    public static class PausedException extends Exception {
        PausedException() {
            super("Paused");
        }
    }

    private static class Segment {
        final String uri;
        final String keyUri;
        final byte[] iv;
        final long sequence;
        final double duration;

        Segment(String uri, String keyUri, byte[] iv, long sequence, double duration) {
            this.uri = uri;
            this.keyUri = keyUri;
            this.iv = iv;
            this.sequence = sequence;
            this.duration = duration;
        }
    }

    public static File download(DownloadItem item, Map<String, String> headers, File targetMp4, ProgressListener listener) throws Exception {
        String m3u8Url = item.getUrl();
        String playlist = fetchText(m3u8Url, headers);
        if (isMaster(playlist)) {
            String variant = chooseVariant(playlist, m3u8Url);
            if (TextUtils.isEmpty(variant)) throw new Exception("no variant in master playlist");
            playlist = fetchText(variant, headers);
            m3u8Url = variant;
        }
        List<Segment> segments = parseSegments(playlist, m3u8Url);
        if (segments.isEmpty()) throw new Exception("no segments in playlist");

        // 本地输出目录：与 targetMp4 同目录、同名 + "_hls"，内部保存各分片与 index.m3u8。
        // 直接基于分片构建本地播放列表，无需整体合并/转封装，天然支持断点续传与本地播放。
        File dir = new File(targetMp4.getParentFile(), targetMp4.getName().replaceAll("\\.mp4$", "") + "_hls");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("create hls dir failed");

        try {
            int total = segments.size();
            // 统计已下载分片（续传），避免重复下载
            int completed = 0;
            long downloadedBytes = 0;
            for (int i = 0; i < total; i++) {
                File sf = new File(dir, "seg_" + i + ".ts");
                if (sf.exists() && sf.length() > 0) {
                    completed++;
                    downloadedBytes += sf.length();
                }
            }
            if (completed < total) {
                downloadSegments(item, headers, segments, dir, completed, downloadedBytes, listener);
            }
            if (item.isCanceled()) throw new CanceledException();
            if (item.isPaused()) throw new PausedException();
            writePlaylist(dir, segments);
            File out = new File(dir, "index.m3u8");
            long size = dirSize(dir);
            if (listener != null) listener.onProgress(100, size, size, 0);
            return out;
        } catch (PausedException e) {
            // 保留目录以便续传
            throw e;
        } catch (CanceledException e) {
            deleteDir(dir);
            throw e;
        } catch (Throwable e) {
            // 保留目录用于续传/重试，仅取消会删除
            throw e;
        }
    }

    private static List<Segment> parseSegments(String playlist, String baseUrl) {
        List<Segment> segments = new ArrayList<>();
        long mediaSequence = 0;
        String keyUri = null;
        byte[] keyIv = null;
        boolean pendingVariant = false;
        double pendingDuration = 0;
        String[] lines = playlist.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                if (line.startsWith("#EXTINF")) {
                    pendingDuration = parseExtinf(line);
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try {
                        mediaSequence = Long.parseLong(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                    } catch (Exception ignored) {
                    }
                } else if (line.startsWith("#EXT-X-KEY")) {
                    String method = attr(line, "METHOD");
                    if ("NONE".equalsIgnoreCase(method)) {
                        keyUri = null;
                        keyIv = null;
                    } else {
                        keyUri = attr(line, "URI");
                        if (!TextUtils.isEmpty(keyUri)) keyUri = stripQuotes(resolve(baseUrl, keyUri));
                        String ivStr = attr(line, "IV");
                        keyIv = TextUtils.isEmpty(ivStr) ? null : hexToBytes(ivStr);
                    }
                } else if (line.startsWith("#EXT-X-STREAM-INF")) {
                    pendingVariant = true;
                }
                continue;
            }
            if (pendingVariant) {
                pendingVariant = false;
            } else {
                long seq = mediaSequence + segments.size();
                byte[] iv = keyIv != null ? keyIv : sequenceToIv(seq);
                segments.add(new Segment(resolve(baseUrl, line), keyUri, iv, seq, pendingDuration));
                pendingDuration = 0;
            }
        }
        return segments;
    }

    private static double parseExtinf(String line) {
        try {
            int colon = line.indexOf(':');
            if (colon < 0) return 0;
            String val = line.substring(colon + 1);
            int comma = val.indexOf(',');
            if (comma >= 0) val = val.substring(0, comma);
            return Double.parseDouble(val.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String chooseVariant(String playlist, String baseUrl) {
        String best = null;
        long bestBandwidth = -1;
        boolean pending = false;
        long pendingBandwidth = 0;
        String[] lines = playlist.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pending = true;
                pendingBandwidth = 0;
                String bw = attr(line, "BANDWIDTH");
                try {
                    if (!TextUtils.isEmpty(bw)) pendingBandwidth = Long.parseLong(bw.trim());
                } catch (Exception ignored) {
                }
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                if (pending) {
                    String uri = resolve(baseUrl, line);
                    if (pendingBandwidth >= bestBandwidth) {
                        bestBandwidth = pendingBandwidth;
                        best = uri;
                    }
                    pending = false;
                }
            }
        }
        return best;
    }

    private static void downloadSegments(DownloadItem item, Map<String, String> headers, List<Segment> segments, File dir, int startCompleted, long startBytes, ProgressListener listener) throws Exception {
        int total = segments.size();
        File[] segFiles = new File[total];
        for (int i = 0; i < total; i++) segFiles[i] = new File(dir, "seg_" + i + ".ts");
        ExecutorService pool = Executors.newFixedThreadPool(SEGMENT_THREADS);
        CountDownLatch latch = new CountDownLatch(total);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicLong downloadedBytes = new AtomicLong(startBytes);
        AtomicInteger completed = new AtomicInteger(startCompleted);
        String tag = item.getId();
        long startTime = System.currentTimeMillis();
        AtomicLong lastNotify = new AtomicLong(startTime);
        AtomicLong lastBytes = new AtomicLong(startBytes);

        for (int i = 0; i < total; i++) {
            final int index = i;
            final Segment seg = segments.get(index);
            pool.execute(() -> {
                try {
                    if (item.isCanceled() || failed.get()) return;
                    if (segFiles[index].exists() && segFiles[index].length() > 0) {
                        // 已下载（续传），直接跳过
                        return;
                    }
                    if (item.isPaused() || item.isCanceled()) return;
                    byte[] data = fetchBytes(seg.uri, headers, tag, item);
                    if (item.isPaused() || item.isCanceled()) return;
                    if (seg.keyUri != null) data = decrypt(data, seg.keyUri, seg.iv, headers, tag, item);
                    if (item.isPaused() || item.isCanceled()) return;
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(segFiles[index]))) {
                        os.write(data);
                    }
                    downloadedBytes.addAndGet(data.length);
                    onSegmentDone(completed, total, downloadedBytes, lastNotify, lastBytes, startTime, listener);
                } catch (PausedException | CanceledException e) {
                    // 暂停/取消：不标记为失败
                } catch (Throwable e) {
                    failed.set(true);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        if (item.isCanceled()) throw new CanceledException();
        if (item.isPaused()) throw new PausedException();
        if (failed.get()) throw new Exception("segment download failed");
    }

    private static void onSegmentDone(AtomicInteger completed, int total, AtomicLong downloadedBytes, AtomicLong lastNotify, AtomicLong lastBytes, long startTime, ProgressListener listener) {
        int done = completed.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastNotify.get() >= PROGRESS_INTERVAL) {
            lastNotify.set(now);
            long b = downloadedBytes.get();
            long delta = Math.max(1, now - startTime);
            long speed = (b - lastBytes.get()) * 1000 / delta;
            if (listener != null) listener.onProgress(done * 100 / total, b, -1, speed);
            lastBytes.set(b);
        }
    }

    private static void writePlaylist(File dir, List<Segment> segments) throws Exception {
        File m3u8 = new File(dir, "index.m3u8");
        double maxDur = 0;
        for (Segment seg : segments) {
            if (seg.duration > maxDur) maxDur = seg.duration;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:").append((long) Math.ceil(maxDur)).append("\n");
        for (int i = 0; i < segments.size(); i++) {
            sb.append("#EXTINF:").append(segments.get(i).duration).append(",\n");
            sb.append("seg_").append(i).append(".ts\n");
        }
        sb.append("#EXT-X-ENDLIST\n");
        try (FileOutputStream os = new FileOutputStream(m3u8)) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static long dirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.isFile()) size += f.length();
        return size;
    }

    private static byte[] decrypt(byte[] data, String keyUri, byte[] iv, Map<String, String> headers, String tag, DownloadItem item) throws Exception {
        byte[] key = fetchBytes(keyUri, headers, tag, item);
        if (key == null || key.length < 16) throw new Exception("invalid aes key");
        SecretKeySpec keySpec = new SecretKeySpec(key, 0, 16, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv != null ? iv : new byte[16]);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        try {
            return cipher.doFinal(data);
        } catch (javax.crypto.BadPaddingException e) {
            // 部分源未使用 PKCS7 填充，回退 NoPadding
            Cipher raw = Cipher.getInstance("AES/CBC/NoPadding");
            raw.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return raw.doFinal(data);
        }
    }

    private static byte[] fetchBytes(String url, Map<String, String> headers, String tag, DownloadItem item) throws Exception {
        int retry = 0;
        while (true) {
            if (item.isPaused() || item.isCanceled()) throw new PausedException();
            try {
                try (Response res = OkHttp.newCall(url, headers, tag).execute()) {
                    if (!res.isSuccessful() || res.body() == null) throw new Exception("HTTP " + res.code());
                    return res.body().bytes();
                }
            } catch (PausedException e) {
                throw e;
            } catch (Throwable e) {
                if (++retry >= SEGMENT_RETRY) throw e;
                try {
                    Thread.sleep(300 * retry);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static String fetchText(String url, Map<String, String> headers) throws Exception {
        String text = OkHttp.string(url, headers);
        if (TextUtils.isEmpty(text)) throw new Exception("empty playlist");
        return text;
    }

    private static boolean isMaster(String playlist) {
        return playlist.contains("#EXT-X-STREAM-INF");
    }

    private static String resolve(String base, String value) {
        try {
            okhttp3.HttpUrl baseUrl = okhttp3.HttpUrl.parse(base);
            okhttp3.HttpUrl resolved = baseUrl == null ? null : baseUrl.resolve(value);
            return resolved == null ? value : resolved.toString();
        } catch (Exception e) {
            return value;
        }
    }

    private static String attr(String line, String name) {
        int idx = line.indexOf(name + "=");
        if (idx < 0) return "";
        int start = idx + name.length() + 1;
        if (start >= line.length()) return "";
        char c = line.charAt(start);
        if (c == '"') {
            int end = line.indexOf('"', start + 1);
            return end < 0 ? "" : line.substring(start + 1, end);
        }
        int end = line.indexOf(',', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private static byte[] hexToBytes(String hex) {
        String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        int len = h.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] sequenceToIv(long seq) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 0 && seq > 0; i--) {
            iv[i] = (byte) (seq & 0xFF);
            seq >>= 8;
        }
        return iv;
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
