package com.fongmi.android.tv.ad.audio;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 按上游 m3u8-ad-audio-probe 的规则更新约束抓取社区 rules.json：只允许 HTTPS，
 * 重定向后仍必须是 HTTPS，整次调用 45 秒上限，读取上限 4 MiB，
 * 解析成功后才交给 {@link ProbeRuleStore} 原子替换。规则源不签名，因此只做严格解析、
 * revision 单调递增和大小限制，不做真实性校验。
 */
public final class ProbeRuleDownloader {

    private static final long CONNECT_TIMEOUT_MS = 15_000L;
    private static final long CALL_TIMEOUT_MS = 45_000L;
    private static final int HTTP_NOT_MODIFIED = 304;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static OkHttpClient client;

    private ProbeRuleDownloader() {
    }

    /**
     * 指纹功能开启、配置了 HTTPS 规则源且已过刷新间隔时，在后台拉一次规则。
     * 并发调用会被合并，异常只记录不上抛。
     */
    public static void refreshIfDue() {
        if (!AdAudioSetting.isEnabled()) return;
        String url = AdAudioSetting.getProbeRuleUrl();
        if (!isHttps(url)) return;
        long now = System.currentTimeMillis();
        if (!AdAudioSetting.isProbeRefreshDue(now)) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        Task.execute(() -> {
            try {
                AdAudioSetting.markProbeRefreshed(System.currentTimeMillis());
                refresh(url, ProbeRuleStore.get());
            } catch (IOException | RuntimeException e) {
                // fail-open：拉取或校验失败时已有缓存继续生效，不影响播放。
                SpiderDebug.log("ad-audio-probe-rules", e);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    /**
     * 设置页「立即刷新」：忽略刷新间隔，结果回到主线程。并发点击会被合并，
     * 此时回调不会触发，由调用方自己维持按钮状态。
     */
    public static void refreshNow(Callback callback) {
        String url = AdAudioSetting.getProbeRuleUrl();
        if (!isHttps(url)) {
            callback.onFailure(new IllegalArgumentException("probe rule url must be https"));
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) return;
        Task.execute(() -> {
            try {
                AdAudioSetting.markProbeRefreshed(System.currentTimeMillis());
                AdAudioRuleSnapshot snapshot = refresh(url, ProbeRuleStore.get());
                App.post(() -> callback.onSuccess(snapshot));
            } catch (IOException | RuntimeException e) {
                SpiderDebug.log("ad-audio-probe-rules", e);
                App.post(() -> callback.onFailure(e));
            } finally {
                RUNNING.set(false);
            }
        });
    }

    public interface Callback {

        void onSuccess(AdAudioRuleSnapshot snapshot);

        void onFailure(Throwable error);
    }

    /**
     * 同步抓取并安装。解析失败、版本回滚或同版本内容冲突会抛
     * {@link IllegalArgumentException}，已有缓存保持生效。
     */
    public static AdAudioRuleSnapshot refresh(String url, ProbeRuleStore store) throws IOException {
        if (!isHttps(url)) throw new IllegalArgumentException("probe rule url must be https");
        return refresh(url, store, client());
    }

    static AdAudioRuleSnapshot refresh(String url, ProbeRuleStore store, OkHttpClient client)
            throws IOException {
        if (store == null) throw new IllegalArgumentException("store is required");
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == HTTP_NOT_MODIFIED) return store.load();
            if (!response.isSuccessful()) {
                throw new IOException("probe rule download failed: " + response.code());
            }
            if (isHttps(url) && !isHttps(response.request().url().toString())) {
                throw new IOException("probe rule url was redirected off https");
            }
            return store.install(readBounded(response.body()));
        }
    }

    private static byte[] readBounded(ResponseBody body) throws IOException {
        if (body == null) throw new IOException("probe rule response has no body");
        long declared = body.contentLength();
        if (declared > ProbeRuleStore.MAX_DOWNLOAD_BYTES) {
            throw new IOException("probe rules are too large: " + declared);
        }
        try (InputStream input = body.byteStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(16_384);
            byte[] buffer = new byte[8_192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                total += count;
                if (total > ProbeRuleStore.MAX_DOWNLOAD_BYTES) {
                    throw new IOException("probe rules are too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /**
     * 故意不复用 {@link com.github.catvod.net.OkHttp#client()}：那个客户端为了兼容资源站的破证书
     * 装了 trust-all 的 {@code sslSocketFactory} 和恒真 {@code hostnameVerifier}。规则源没有签名，
     * 传输层是唯一的真实性保障，用信任一切的客户端拉规则等于任何中间人都能塞入任意指纹，
     * 而指纹会直接驱动播放器 seek。这里用平台默认 TLS 校验，并禁止 https 降级到 http。
     */
    static synchronized OkHttpClient client() {
        if (client != null) return client;
        return client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .build();
    }

    private static boolean isHttps(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("https://");
    }
}
