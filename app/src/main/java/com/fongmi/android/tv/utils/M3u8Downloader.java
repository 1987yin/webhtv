package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.DownloadItem;
import com.github.catvod.net.OkHttp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
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

import okhttp3.Request;
import okhttp3.Response;

public class M3u8Downloader {

    private static final int SEGMENT_THREADS = 4;
    private static final int SEGMENT_RETRY = 3;
    private static final long PROGRESS_INTERVAL = 400;
    // 默认 UA：部分对象存储（OBS / AWS S3 预签名）会按 UA 放行或拦截，
    // 不带的默认 okhttp UA 容易被 403。播放器/本地代理也是用浏览器类 UA 拉通的。
    private static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 11; WebHTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

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

    // 下载入口。targetMp4 为占位文件（name.mp4），实际产物为同目录的 name.ts（所有分片合并后的单文件）。
    // 关键点：第一次解析 playlist 后，把“已解析的分片播放列表”与 baseUrl 持久化到 _hls 目录，
    // 续传/重试时复用同一份列表，避免滑动窗口、签名 URL、按次变化的源导致分片列表不一致、
    // 按索引重算“已完成”数量错乱而跳过下载。
    public static File download(DownloadItem item, Map<String, String> headers, File targetMp4, ProgressListener listener) throws Exception {
        File dir = new File(targetMp4.getParentFile(), baseName(targetMp4) + "_hls");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("create hls dir failed");
        File playlistFile = new File(dir, "playlist.m3u8");
        File baseUrlFile = new File(dir, "baseurl.txt");

        String playlist;
        String baseUrl;
        if (playlistFile.exists() && baseUrlFile.exists()) {
            // 续传：复用首次解析并保存的分片列表，保证索引与已下载的 seg_i.ts 一一对应
            playlist = readFile(playlistFile);
            baseUrl = readFile(baseUrlFile);
        } else {
            String m3u8Url = item.getUrl();
            playlist = fetchText(m3u8Url, headers, item);
            if (isMaster(playlist)) {
                String variant = chooseVariant(playlist, m3u8Url);
                if (TextUtils.isEmpty(variant)) throw new Exception("no variant in master playlist");
                playlist = fetchText(variant, headers, item);
                m3u8Url = variant;
            }
            baseUrl = m3u8Url;
            writeFile(playlistFile, playlist);
            writeFile(baseUrlFile, baseUrl);
        }

        List<Segment> segments = parseSegments(playlist, baseUrl);
        if (segments.isEmpty()) throw new Exception("no segments in playlist");

        int total = segments.size();
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

        // 完成前必须校验所有分片确实就绪，杜绝“部分缺失却标记成功”
        for (int i = 0; i < total; i++) {
            File sf = new File(dir, "seg_" + i + ".ts");
            if (!sf.exists() || sf.length() == 0) throw new Exception("segment missing: " + i);
        }

        // 合并为单个 .ts：MPEG-TS 可直接拼接，单文件播放最稳、时长准确、无缺失分片问题
        File merged = new File(targetMp4.getParentFile(), baseName(targetMp4) + ".ts");
        mergeTs(dir, total, merged);
        // 清理分片与临时文件（占位 mp4 亦无用）
        deleteDir(dir);
        File placeholder = targetMp4;
        if (placeholder.exists()) placeholder.delete();

        if (listener != null) listener.onProgress(100, merged.length(), merged.length(), 0);
        return merged;
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
            val = val.trim();
            if (val.isEmpty()) return 0;
            return Double.parseDouble(val);
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
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (item.isCanceled()) throw new CanceledException();
            if (item.isPaused()) throw new PausedException();
            throw new Exception("interrupted");
        } finally {
            pool.shutdown();
        }
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

    private static void mergeTs(File dir, int total, File out) throws Exception {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[64 * 1024];
            for (int i = 0; i < total; i++) {
                File sf = new File(dir, "seg_" + i + ".ts");
                try (InputStream is = new FileInputStream(sf)) {
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
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
        boolean clean = false;
        while (true) {
            if (item.isPaused() || item.isCanceled()) throw new PausedException();
            Map<String, String> useHeaders = clean ? null : headers;
            try (Response res = call(url, useHeaders, item)) {
                if (!res.isSuccessful() || res.body() == null) throw new Exception("HTTP " + res.code());
                return res.body().bytes();
            } catch (PausedException e) {
                throw e;
            } catch (Throwable e) {
                // 某些源（带签名/鉴权的对象存储、需特定 UA 的 CDN）对“源自定义头 + 默认 okhttp UA”
                // 的直连会 403，而播放器/本地代理是用默认 UA 且基本不带源头拉通的。
                // 首次失败后改用“干净请求”（仅默认 UA、去掉源自定义头）重试，与播放路径一致。
                if (++retry > SEGMENT_RETRY) throw e;
                clean = true;
                try {
                    Thread.sleep(300 * retry);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static String fetchText(String url, Map<String, String> headers, DownloadItem item) throws Exception {
        try (Response res = call(url, headers, item)) {
            String text = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) throw new Exception("playlist HTTP " + res.code() + " " + snippet(text));
            if (TextUtils.isEmpty(text)) throw new Exception("playlist HTTP " + res.code() + " empty");
            return text;
        }
    }

    // 构造请求：始终带默认浏览器 UA（与播放器/本地代理一致），再叠加源自定义头。
    // 复用 OkHttp.player() 客户端，与播放路径共享同一套拦截器/配置，最大化行为一致性。
    private static Response call(String url, Map<String, String> headers, DownloadItem item) throws Exception {
        Request.Builder builder = new Request.Builder().url(url).tag(item.getId());
        boolean hasUa = false;
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getValue() == null) continue;
                if ("User-Agent".equalsIgnoreCase(entry.getKey())) hasUa = true;
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        if (!hasUa) builder.header("User-Agent", DEFAULT_UA);
        return OkHttp.player().newCall(builder.build()).execute();
    }

    private static String snippet(String text) {
        if (TextUtils.isEmpty(text)) return "";
        int end = Math.min(text.length(), 200);
        return text.substring(0, end).replace("\n", " ").replace("\r", " ");
    }

    private static boolean isMaster(String playlist) {
        return playlist.contains("#EXT-X-STREAM-INF");
    }

    private static String resolve(String base, String value) {
        try {
            okhttp3.HttpUrl baseUrl = okhttp3.HttpUrl.parse(base);
            if (baseUrl == null) return value;
            okhttp3.HttpUrl resolved = baseUrl.resolve(value);
            if (resolved == null) return value;
            // 对象存储预签名（OBS / AWS S3）把签名放在 query 上。RFC3986 下相对分片解析会丢弃
            // base 的 query，导致分片 / EXT-X-KEY 请求缺失签名而 403。播放器（UriUtil.resolve）
            // 会保留 base query，所以能播；这里在分片为“相对文件名”（非根相对、非绝对）时同样补回
            // base 的 query，保持与播放路径一致。
            if (TextUtils.isEmpty(resolved.query())
                    && !TextUtils.isEmpty(baseUrl.query())
                    && !value.contains("://")
                    && !value.startsWith("/")) {
                resolved = resolved.newBuilder().query(baseUrl.query()).build();
            }
            return resolved.toString();
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

    private static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String readFile(File f) throws Exception {
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = is.read(buf);
            return new String(buf, 0, n < 0 ? 0 : n, StandardCharsets.UTF_8);
        }
    }

    private static void writeFile(File f, String s) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) deleteDir(f);
        dir.delete();
    }
}
