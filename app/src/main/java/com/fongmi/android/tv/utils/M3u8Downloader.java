package com.fongmi.android.tv.utils;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.text.TextUtils;

import com.fongmi.android.tv.bean.DownloadItem;
import com.github.catvod.net.OkHttp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Response;

public class M3u8Downloader {

    private static final int SEGMENT_THREADS = 4;
    private static final long PROGRESS_INTERVAL = 400;

    public interface ProgressListener {
        void onProgress(int percent, long bytes, long total, long speed);
    }

    private static class CanceledException extends Exception {
        CanceledException() {
            super("Canceled");
        }
    }

    private static class Segment {
        final String uri;
        final String keyUri;
        final byte[] iv;
        final long sequence;

        Segment(String uri, String keyUri, byte[] iv, long sequence) {
            this.uri = uri;
            this.keyUri = keyUri;
            this.iv = iv;
            this.sequence = sequence;
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

        File dir = new File(targetMp4.getParentFile(), "tmp_" + item.getId());
        if (dir.exists()) deleteDir(dir);
        if (!dir.mkdirs()) throw new Exception("create temp dir failed");

        try {
            File tsFile = downloadSegments(item, headers, segments, dir, listener);
            if (item.isCanceled()) throw new CanceledException();
            remuxToMp4(tsFile, targetMp4);
            return targetMp4;
        } finally {
            deleteDir(dir);
        }
    }

    private static List<Segment> parseSegments(String playlist, String baseUrl) {
        List<Segment> segments = new ArrayList<>();
        List<String> variants = new ArrayList<>();
        long mediaSequence = 0;
        String keyUri = null;
        byte[] keyIv = null;
        boolean pendingVariant = false;
        String[] lines = playlist.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
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
                variants.add(resolve(baseUrl, line));
                pendingVariant = false;
            } else {
                long seq = mediaSequence + segments.size();
                byte[] iv = keyIv != null ? keyIv : sequenceToIv(seq);
                segments.add(new Segment(resolve(baseUrl, line), keyUri, iv, seq));
            }
        }
        return segments;
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

    private static File downloadSegments(DownloadItem item, Map<String, String> headers, List<Segment> segments, File dir, ProgressListener listener) throws Exception {
        int total = segments.size();
        File tsFile = new File(dir, "merged.ts");
        ExecutorService pool = Executors.newFixedThreadPool(SEGMENT_THREADS);
        CountDownLatch latch = new CountDownLatch(total);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicLong downloadedBytes = new AtomicLong(0);
        byte[][] buffers = new byte[total][];
        String tag = item.getId();
        long startTime = System.currentTimeMillis();
        AtomicLong lastNotify = new AtomicLong(startTime);
        AtomicLong lastBytes = new AtomicLong(0);

        for (int i = 0; i < total; i++) {
            final int index = i;
            final Segment seg = segments.get(i);
            pool.execute(() -> {
                try {
                    if (item.isCanceled() || failed.get()) return;
                    byte[] data = fetchBytes(seg.uri, headers, tag);
                    if (seg.keyUri != null) data = decrypt(data, seg.keyUri, seg.iv, headers, tag);
                    buffers[index] = data;
                    long b = downloadedBytes.addAndGet(data.length);
                    long now = System.currentTimeMillis();
                    if (now - lastNotify.get() >= PROGRESS_INTERVAL) {
                        lastNotify.set(now);
                        long delta = Math.max(1, now - startTime);
                        long speed = (b - lastBytes.get()) * 1000 / Math.max(1, now - lastNotify.get() + PROGRESS_INTERVAL);
                        if (listener != null) listener.onProgress((index + 1) * 100 / total, b, -1, speed);
                        lastBytes.set(b);
                    }
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
        if (failed.get()) throw new Exception("segment download failed");

        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tsFile))) {
            for (int i = 0; i < total; i++) {
                if (buffers[i] == null) throw new Exception("missing segment " + i);
                os.write(buffers[i]);
            }
        }
        if (listener != null) listener.onProgress(100, downloadedBytes.get(), downloadedBytes.get(), 0);
        return tsFile;
    }

    private static void remuxToMp4(File tsFile, File mp4File) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(tsFile.getAbsolutePath());
        int trackCount = extractor.getTrackCount();
        if (trackCount == 0) throw new Exception("no track in merged ts");
        MediaMuxer muxer = new MediaMuxer(mp4File.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int[] muxTracks = new int[trackCount];
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            muxTracks[i] = muxer.addTrack(format);
        }
        muxer.start();
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        for (int i = 0; i < trackCount; i++) extractor.selectTrack(i);
        boolean eos = false;
        while (!eos) {
            int trackIndex = extractor.getSampleTrackIndex();
            if (trackIndex < 0) break;
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                eos = true;
                break;
            }
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(muxTracks[trackIndex], buffer, info);
            extractor.advance();
        }
        muxer.stop();
        muxer.release();
        extractor.release();
    }

    private static byte[] decrypt(byte[] data, String keyUri, byte[] iv, Map<String, String> headers, String tag) throws Exception {
        byte[] key = fetchBytes(keyUri, headers, tag);
        if (key == null || key.length < 16) throw new Exception("invalid aes key");
        SecretKeySpec keySpec = new SecretKeySpec(key, 0, 16, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv != null ? iv : new byte[16]);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(data);
    }

    private static byte[] fetchBytes(String url, Map<String, String> headers, String tag) throws Exception {
        try (Response res = OkHttp.newCall(url, headers, tag).execute()) {
            if (!res.isSuccessful() || res.body() == null) throw new Exception("HTTP " + res.code());
            return res.body().bytes();
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
