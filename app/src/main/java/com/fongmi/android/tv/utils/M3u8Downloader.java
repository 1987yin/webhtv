package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.bean.Result;
import com.github.catvod.net.OkHttp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class M3u8Downloader {

    private static final String TAG = "M3u8Downloader";

    private static final int SEGMENT_THREADS = 4;
    private static final int SEGMENT_RETRY = 3;
    // 每个分片签名窗口约 300s，长视频（尤其限速源 x-obs-traffic-limit）需要多次刷新才能下完，
    // 故上限放宽到很大；同时用“刷新后是否有进展 / 是否拿到新地址”做死循环保护。
    private static final int MAX_REFRESH = 200;
    private static final long PROGRESS_INTERVAL = 400;
    // 续传/重试时，若连续多次刷新后仍未有新分片完成才判定源不可达并停止，
    // 给限速/冷却类源留出“签名窗口重置”的恢复机会（避免 99% 直接失败）。
    private static final int STALL_LIMIT = 6;
    // 每次刷新失败后的退避等待（毫秒），让限速冷却 / 签名窗口有机会恢复。
    private static final long REFRESH_BACKOFF = 1500;
    // 极少数分片（如源内嵌的图片/封面轨道、或热链保护的个别分片）始终拉取失败时，
    // 在充分重试后直接将其从本地播放列表剔除（选项 A：不写进 index.m3u8，播放器自然跳过这几秒），
    // 避免整片在 90%+ 因个别分片失败而作废。
    private static final int BEST_EFFORT_MISSING = 8;
    private static final int MIN_BEST_EFFORT_REFRESH = 2;
    // 默认 UA：部分对象存储（OBS / AWS S3 预签名）会按 UA 放行或拦截，
    // 不带的默认 okhttp UA 容易被 403。播放器/本地代理也是用浏览器类 UA 拉通的。
    private static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 11; WebHTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    // 下载专用客户端：复用播放器的拦截器配置，但把读超时从全局 30s 放宽到 180s，
    // 避免大分片 / 限速（如 x-obs-traffic-limit）场景下的读超时失败。
    private static OkHttpClient sDownloadClient;

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

    // 分片签名过期（HTTP 401/403）：触发外层重新拉取播放列表刷新签名后继续，而非直接判失败。
    private static class PlaylistExpiredException extends Exception {
        PlaylistExpiredException(String m) {
            super(m);
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

    // 下载入口。targetMp4 为占位文件（name.mp4）；实际产物为同目录的 name_hls/index.m3u8（本地播放列表）。
    // 关键点：每次进入都重新拉取播放列表，以拿到“当下有效”的分片签名。
    // OBS / AWS 预签名分片往往独立过期（约数十秒），复用首次解析出的旧分片 URL 会全部 403，
    // 表现为“能播不能下 / 重试进度 0% 失败”。已下载的 seg_i.ts 按索引复用，不重复下载；
    // 若分片签名中途过期（403），外层会重新拉列表刷新签名后继续。
    public static File download(DownloadItem item, Map<String, String> headers, File targetMp4, ProgressListener listener) throws Exception {
        SpiderDebug.log(TAG, "download start id=%s name=%s url=%s", item.getId(), targetMp4.getName(), logUrl(item.getUrl()));
        File dir = new File(targetMp4.getParentFile(), baseName(targetMp4) + "_hls");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("create hls dir failed");

        String m3u8Url = item.getUrl();
        String lastUrl = m3u8Url;
        int refresh = 0;
        int lastCompleted = -1;
        int noProgress = 0;
        try {
        while (true) {
            // 刷新退避：给限速冷却 / 签名窗口恢复留出时间，避免对失效源空转；
            // 同时尊重暂停/取消，及时退出。
            if (refresh > 0) {
                try {
                    Thread.sleep(REFRESH_BACKOFF);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (item.isCanceled()) throw new CanceledException();
                    if (item.isPaused()) throw new PausedException();
                    throw new Exception("interrupted during refresh backoff");
                }
            }
            // 始终重新拉播放列表，刷新分片签名（解决过期导致的 403 / 0% 失败）。
            // 若 m3u8 自身的预签名地址也已过期，则向站点接口重新要一份最新地址（含最新签名与请求头），
            // 否则重拉播放列表会直接 403。
            String playlist;
            try {
                playlist = fetchText(m3u8Url, headers, item);
                if (isMaster(playlist)) {
                    String variant = chooseVariant(playlist, m3u8Url);
                    if (TextUtils.isEmpty(variant)) throw new Exception("no variant in master playlist");
                    playlist = fetchText(variant, headers, item);
                    m3u8Url = variant;
                }
            } catch (PlaylistExpiredException e) {
                // 播放列表/变体地址签名过期：重新向站点要最新地址与请求头后继续（已下分片按索引复用）
                if (shouldStop(refresh, m3u8Url, lastUrl)) {
                    SpiderDebug.log(TAG, "playlist expired stop id=%s refresh=%d url=%s", item.getId(), refresh, logUrl(m3u8Url));
                    throw new Exception("playlist signature expired, retry limit reached");
                }
                SpiderDebug.log(TAG, "playlist expired refresh id=%s refresh=%d -> refetch", item.getId(), refresh);
                refresh++;
                lastUrl = m3u8Url;
                refreshFromSite(item);
                m3u8Url = item.getUrl();
                headers = item.getHeaders();
                continue;
            }
            String baseUrl = m3u8Url;
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
            // 续传/重试时先把已落盘分片折算成进度上报，避免“进度条停在 0% 看似卡死”
            // （实际已在 99% 等最后几个分片，只是没有新分片完成时不会触发 onSegmentDone）。
            if (listener != null && total > 0) listener.onProgress(completed * 100 / total, downloadedBytes, -1, 0);
            SpiderDebug.log(TAG, "scan refresh=%d total=%d completed=%d progress=%d%% url=%s", refresh, total, completed, completed * 100 / total, logUrl(m3u8Url));
            // 连续多次刷新后仍无新分片完成，才判定“源不可达/签名无法刷新”而停止，
            // 给限速冷却类源留出恢复机会，避免仅一次刷新未即时生效就放弃 99% 的下载。
            if (refresh > 0 && completed <= lastCompleted) {
                noProgress++;
            } else {
                noProgress = 0;
            }
            lastCompleted = completed;
            if (noProgress >= STALL_LIMIT) {
                SpiderDebug.log(TAG, "stall stop id=%s completed=%d/%d noProgress=%d/%d", item.getId(), completed, total, noProgress, STALL_LIMIT);
                throw new Exception("download stalled: no progress after " + noProgress + " refreshes");
            }
            if (completed < total) {
                AtomicReference<List<Segment>> segRef = new AtomicReference<>(segments);
                try {
                    int failedCount = downloadSegments(item, headers, segRef, dir, completed, downloadedBytes, listener);
                    if (failedCount > 0) {
                        // 重新统计已落盘分片，得到真实缺失数（限速/个别死链分片都会落在这里）
                        int newCompleted = 0;
                        for (int i = 0; i < total; i++) {
                            File sf = new File(dir, "seg_" + i + ".ts");
                            if (sf.exists() && sf.length() > 0) newCompleted++;
                        }
                        int missing = total - newCompleted;
                        if (missing <= BEST_EFFORT_MISSING && refresh >= MIN_BEST_EFFORT_REFRESH) {
                            // 极少数分片（如源内嵌的图片/封面轨道、热链保护的个别分片）始终拉不下来，
                            // 充分重试后仍缺失则直接从本地播放列表剔除（选项 A），避免整片在 90%+ 因个别分片失败而作废。
                            SpiderDebug.log(TAG, "local m3u8 skip-missing id=%s missing=%d/%d after %d refreshes", item.getId(), missing, total, refresh);
                        } else if (shouldStop(refresh, m3u8Url, lastUrl)) {
                            throw new Exception("segment download failed: " + missing + " segments missing");
                        } else {
                            SpiderDebug.log(TAG, "segment incomplete id=%s refresh=%d missing=%d retry", item.getId(), refresh, missing);
                            refresh++;
                            lastUrl = m3u8Url;
                            refreshFromSite(item);
                            m3u8Url = item.getUrl();
                            headers = item.getHeaders();
                            continue;
                        }
                    }
                } catch (PausedException | CanceledException e) {
                    if (e instanceof CanceledException) throw e;
                    // 暂停时若分片已全部就绪，则视为“真正完成”（下方合并出文件），
                    // 避免“显示已暂停、点恢复却瞬间完成”的假象；仅当确有分片缺失才保留暂停态。
                    if (!allSegmentsReady(dir, total)) throw e;
                } catch (PlaylistExpiredException e) {
                    // 分片签名过期：向站点接口重新拉取最新播放列表地址与签名后继续（已下分片按索引复用）
                    if (shouldStop(refresh, m3u8Url, lastUrl)) {
                        SpiderDebug.log(TAG, "segment expired stop id=%s refresh=%d url=%s", item.getId(), refresh, logUrl(m3u8Url));
                        throw new Exception("segment signature expired, retry limit reached");
                    }
                    SpiderDebug.log(TAG, "segment expired refresh id=%s refresh=%d -> refetch", item.getId(), refresh);
                    refresh++;
                    lastUrl = m3u8Url;
                    refreshFromSite(item);
                    m3u8Url = item.getUrl();
                    headers = item.getHeaders();
                    continue;
                }
            }
            if (item.isCanceled()) throw new CanceledException();
            // 暂停但分片已全部就绪 -> 视为完成；仍有缺失才保留暂停态
            if (item.isPaused() && !allSegmentsReady(dir, total)) throw new PausedException();

            // 产出本地 m3u8（而非合并为单文件）：直接引用本地已解密的 seg_i.ts，
            // 缺失/死链分片（如源内嵌的封面 .jpg）按选项 A 直接从播放列表剔除，播放器自然跳过这几秒。
            File localM3u8 = writeLocalPlaylist(dir, segments, item.getId());
            // 占位 mp4 从未实际落盘，存在则顺手清理
            if (targetMp4.exists()) targetMp4.delete();

            long size = dirSize(dir);
            if (listener != null) listener.onProgress(100, size, size, 0);
            SpiderDebug.log(TAG, "download complete id=%s m3u8=%s size=%d", item.getId(), localM3u8.getAbsolutePath(), size);
            return localM3u8;
        }
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "download failed id=%s reason=%s", item.getId(), e.getMessage());
            throw e;
        }
    }

    // 是否应当停止刷新：仅以刷新上限为界。原先的“刷新后未拿到新地址即停止”会误杀“地址固定但仍在限速
    // 续传/仅个别分片死链”的源（如本例 m3u8 地址不变、每次刷新还能多下 ~80 片），故移除该短链路。
    // 真正的死源由 download() 内的连续无进展(noProgress)判定处理。
    private static boolean shouldStop(int refresh, String url, String lastUrl) {
        return refresh >= MAX_REFRESH;
    }

    // 校验所有分片确实已落盘（存在且非空）
    private static boolean allSegmentsReady(File dir, int total) {
        for (int i = 0; i < total; i++) {
            File sf = new File(dir, "seg_" + i + ".ts");
            if (!sf.exists() || sf.length() == 0) return false;
        }
        return true;
    }

    // 向站点接口重新拉取最新播放地址与请求头（预签名往往会过期），用于分片/播放列表签名过期时自愈，
    // 避免“下到 ~98% 才 403 失败、只能手动点重试”的问题。无站点信息（直链下载）时静默跳过。
    private static void refreshFromSite(DownloadItem item) {
        try {
            if (TextUtils.isEmpty(item.getSiteKey())) return;
            Result result = SiteApi.playerContent(item.getSiteKey(), item.getFlag(), item.getEpisodeUrl());
            if (result == null || TextUtils.isEmpty(result.getRealUrl())) return;
            item.setUrl(result.getRealUrl());
            if (result.getHeader() != null) item.setHeaders(result.getHeader());
        } catch (Throwable ignored) {
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

    // 返回本批次失败（无法落盘）的分片数，由外层决定是否重试或做“尽力合并”，而非一失败就抛。
    private static int downloadSegments(DownloadItem item, Map<String, String> headers, AtomicReference<List<Segment>> segmentsRef, File dir, int startCompleted, long startBytes, ProgressListener listener) throws Exception {
        List<Segment> segments = segmentsRef.get();
        int total = segments.size();
        SpiderDebug.log(TAG, "segments start id=%s total=%d completed=%d", item.getId(), total, startCompleted);
        File[] segFiles = new File[total];
        for (int i = 0; i < total; i++) segFiles[i] = new File(dir, "seg_" + i + ".ts");
        ExecutorService pool = Executors.newFixedThreadPool(SEGMENT_THREADS);
        CountDownLatch latch = new CountDownLatch(total);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicBoolean expired = new AtomicBoolean(false);
        AtomicLong downloadedBytes = new AtomicLong(startBytes);
        AtomicInteger completed = new AtomicInteger(startCompleted);
        String tag = item.getId();
        long startTime = System.currentTimeMillis();
        AtomicLong lastNotify = new AtomicLong(startTime);
        AtomicLong lastBytes = new AtomicLong(startBytes);

        for (int i = 0; i < total; i++) {
            final int index = i;
            pool.execute(() -> {
                try {
                    if (item.isCanceled() || failedCount.get() > 0 || expired.get()) return;
                    if (segFiles[index].exists() && segFiles[index].length() > 0) {
                        // 已下载（续传），直接跳过
                        return;
                    }
                    if (item.isPaused() || item.isCanceled()) return;
                    // 每次都从最新引用取分片地址：外层刷新签名后会替换该引用
                    Segment seg = segmentsRef.get().get(index);
                    byte[] data = fetchBytes(seg.uri, headers, tag, item);
                    if (item.isPaused() || item.isCanceled()) return;
                    if (seg.keyUri != null) data = decrypt(data, seg.keyUri, seg.iv, headers, tag, item);
                    if (item.isPaused() || item.isCanceled()) return;
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(segFiles[index]))) {
                        os.write(data);
                    }
                    downloadedBytes.addAndGet(data.length);
                    onSegmentDone(completed, total, downloadedBytes, lastNotify, lastBytes, startTime, listener);
                } catch (PlaylistExpiredException e) {
                    // 分片签名过期：标记后由外层重新拉列表刷新，不在本批次内重试
                    expired.set(true);
                    SpiderDebug.log(TAG, "seg expired id=%s index=%d", item.getId(), index);
                } catch (PausedException | CanceledException e) {
                    // 暂停/取消：不标记为失败
                } catch (Throwable e) {
                    failedCount.incrementAndGet();
                    SpiderDebug.log(TAG, "seg failed id=%s index=%d msg=%s", item.getId(), index, e.getMessage());
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
        if (expired.get()) throw new PlaylistExpiredException("segment signature expired");
        return failedCount.get();
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

    // 生成本地播放列表（VOD 媒体播放列表），直接引用本地已解密的 seg_i.ts（相对路径）。
    // 缺失/死链分片（如源内嵌的封面 .jpg）按选项 A 直接剔除，不写入列表；播放器遇到缺失条目
    // 会自然跳过这几秒，而非整片卡死。因分片已本地解密，本地 m3u8 无需 EXT-X-KEY。
    private static File writeLocalPlaylist(File dir, List<Segment> segments, String id) throws Exception {
        File out = new File(dir, "index.m3u8");
        int written = 0;
        int skipped = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        for (int i = 0; i < segments.size(); i++) {
            File sf = new File(dir, "seg_" + i + ".ts");
            if (!sf.exists() || sf.length() == 0) {
                // 选项 A：死链/缺失分片直接从本地播放列表剔除，播放器自然跳过这几秒
                SpiderDebug.log(TAG, "local m3u8 skip missing index=%d id=%s", i, id);
                skipped++;
                continue;
            }
            Segment s = segments.get(i);
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.duration)).append(",\n");
            sb.append("seg_").append(i).append(".ts\n");
            written++;
        }
        sb.append("#EXT-X-ENDLIST\n");
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        SpiderDebug.log(TAG, "local m3u8 written id=%s entries=%d skipped=%d file=%s", id, written, skipped, out.getAbsolutePath());
        return out;
    }

    private static long dirSize(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.isFile()) total += f.length();
        return total;
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
                if (!res.isSuccessful() || res.body() == null) {
                    int code = res.code();
                    // 401/403 视为签名过期：抛出专用异常，由外层重新拉列表刷新签名后继续。
                    if (code == 401 || code == 403) throw new PlaylistExpiredException("HTTP " + code);
                    throw new Exception("HTTP " + code);
                }
                return res.body().bytes();
            } catch (PausedException | PlaylistExpiredException e) {
                throw e;
            } catch (Throwable e) {
                // 请求被取消（暂停/取消时 cancelTag 触发 OkHttp 抛 IOException）：立即按当前标记重新
                // 判定为“取消/暂停”，避免被误判为“下载失败”而走无效重试，也保证分片线程干净退出
                // （暂停即时生效，恢复后不会“秒完成”假象）。
                if (isCancelException(e)) {
                    SpiderDebug.log(TAG, "fetch canceled id=%s url=%s msg=%s", item.getId(), logUrl(url), e.getMessage());
                    if (item.isCanceled()) throw new CanceledException();
                    if (item.isPaused()) throw new PausedException();
                }
                // 某些源（带签名/鉴权的对象存储、需特定 UA 的 CDN）对“源自定义头 + 默认 okhttp UA”
                // 的直连会 403，而播放器/本地代理是用默认 UA 且基本不带源头拉通的。
                // 首次失败后改用“干净请求”（仅默认 UA、去掉源自定义头）重试，与播放路径一致。
                if (++retry > SEGMENT_RETRY) throw e;
                clean = true;
                SpiderDebug.log(TAG, "fetch fallback clean id=%s url=%s retry=%d msg=%s", item.getId(), logUrl(url), retry, e.getMessage());
                try {
                    Thread.sleep(300 * retry);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    // 判断异常是否由“请求被取消”引起（暂停/取消时 cancelTag 触发）。OkHttp 取消会抛
    // IOException("Canceled") 或 InterruptedIOException，流重置为 StreamResetException("...CANCEL") 等，
    // 这里按类型 + 关键字识别，以便即时把在途请求重新判定为暂停/取消而非失败。
    private static boolean isCancelException(Throwable e) {
        if (e instanceof java.io.InterruptedIOException) return true;
        String msg = e.getMessage();
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("cancel") || m.contains("socket closed")
                || m.contains("stream closed") || m.contains("connection closed")
                || m.contains("broken pipe") || m.contains("request aborted");
    }

    private static String fetchText(String url, Map<String, String> headers, DownloadItem item) throws Exception {
        try (Response res = call(url, headers, item)) {
            String text = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) {
                // 401/403 视为签名过期：抛出专用异常，由外层重新拉列表 / 重新向站点要地址刷新签名后继续。
                if (res.code() == 401 || res.code() == 403) throw new PlaylistExpiredException("playlist HTTP " + res.code());
                throw new Exception("playlist HTTP " + res.code() + " " + snippet(text));
            }
            if (TextUtils.isEmpty(text)) throw new Exception("playlist HTTP " + res.code() + " empty");
            return text;
        }
    }

    // 构造请求：始终带默认浏览器 UA（与播放器/本地代理一致），再叠加源自定义头。
    // 使用下载专用客户端（读超时放宽），与播放路径共享同一套拦截器/配置，最大化行为一致性。
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
        return downloadClient().newCall(builder.build()).execute();
    }

    // 下载专用客户端：在播放器客户端基础上放宽读超时，避免大分片/限速场景下的 30s 读超时失败。
    private static synchronized OkHttpClient downloadClient() {
        if (sDownloadClient == null) {
            sDownloadClient = OkHttp.player().newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(180, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
        return sDownloadClient;
    }

    // 暂停/取消时取消下载客户端上对应 tag 的链接（与 OkHttp.cancel 互补，避免分片请求继续占用带宽）。
    public static void cancelTag(String tag) {
        if (sDownloadClient != null) OkHttp.cancel(sDownloadClient, tag);
    }

    private static String snippet(String text) {
        if (TextUtils.isEmpty(text)) return "";
        int end = Math.min(text.length(), 200);
        return text.substring(0, end).replace("\n", " ").replace("\r", " ");
    }

    // 日志脱敏：保留 host + path + 各 query 参数名，但把参数值清空，避免把预签名令牌/密钥写进日志。
    private static String logUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        int q = url.indexOf('?');
        if (q < 0) return url;
        String base = url.substring(0, q);
        String query = url.substring(q + 1);
        String[] pairs = query.split("&");
        StringBuilder sb = new StringBuilder(base).append('?');
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) sb.append('&');
            String p = pairs[i];
            int eq = p.indexOf('=');
            sb.append(eq >= 0 ? p.substring(0, eq) : p).append('=');
        }
        return sb.toString();
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

}
