package com.fongmi.android.tv.ui.web;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.ui.novel.NovelReaderHost;
import com.fongmi.android.tv.ui.novel.NovelRouter;
import com.fongmi.android.tv.ui.novel.ReaderHistory;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验室：小说/漫画 WebView 阅读器。
 *
 * 正确姿势（非直接打开第三方网页）：
 * 加载本地 assets/reader.html 阅读器模板（自带小说翻页 / 漫画瀑布流 / 目录 / 主题 / 自动滚动 UI），
 * 把 spider 返回的 novel://{title,content} 或 pics://{图片URL列表} JSON 数据注入到模板渲染，全屏沉浸。
 */
public class WebReaderActivity extends AppCompatActivity {

    private static final String TAG = "TV-reader";

    public static final String EXTRA_KIND = "kind";           // 1=小说 2=漫画
    public static final String EXTRA_PAYLOAD = "payload";     // novel:// 或 pics:// 原始字符串
    public static final String EXTRA_SITE_KEY = "siteKey";
    public static final String EXTRA_FLAG = "flag";
    public static final String EXTRA_VOD_ID = "vodId";
    public static final String EXTRA_VOD_NAME = "vodName";
    public static final String EXTRA_VOD_PIC = "vodPic";
    public static final String EXTRA_CHAPTERS = "chapters";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_LOCAL_PATH = "localPath"; // 本地文件绝对路径（本地阅读模式）
    public static final String EXTRA_CACHE_KEY = "cacheKey";   // 大数据经静态缓存传递，避开 Binder 1MB 限制

    /**
     * 章节列表 / 正文 payload 经进程内静态缓存传递，不走 Intent。
     * 小说整本书可有数千章、单章正文可达数百 KB，直接放进 Intent 会触发
     * TransactionTooLargeException（Binder 事务上限约 1MB）。做法与 AudioActivity 一致。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, ArrayList<Episode>> CHAPTER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> PAYLOAD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 把大数据存入缓存并返回 key，供 Intent 携带。 */
    public static String cacheLargeData(String payload, List<Episode> chapters) {
        String key = "reader_" + System.nanoTime();
        if (payload != null) PAYLOAD_CACHE.put(key, payload);
        if (chapters != null) CHAPTER_CACHE.put(key, new ArrayList<>(chapters));
        return key;
    }

    private static final okhttp3.OkHttpClient IMAGE_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    /** 章节解析线程（单线程串行，避免快速连点目录时并发注入乱序）。 */
    private static final java.util.concurrent.ExecutorService RESOLVE_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();

    private WebView webView;
    private ProgressBar progress;
    private View loading;
    private int kind = 1;
    private String payload = "";
    private String siteKey = "", flag = "", vodId = "", vodName = "", vodPic = "";
    private ArrayList<Episode> chapters = new ArrayList<>();
    private int index = 0;
    private String localPath = "";
    private String cacheKey = "";
    private boolean pageFinished = false;
    private String pendingJson = null;

    /** 阅读进度：JS 滚动时上报到内存，onPause / onDestroy 落库。 */
    private ReaderHistory.Record record;
    private String lastChapterUrl = "";
    private String lastChapterName = "";
    /** 章节内锚点序号（小说=段落，漫画/PDF=页）与锚点总数。 */
    private int lastAnchor = 0;
    private int lastTotal = 0;
    /** 待恢复的章节内锚点与总数（用完置 0）。 */
    private long restoreAnchor = 0;
    private long restoreTotal = 0;
    /** 上次读的章节与传入 payload 不同时，待重新解析的章节 URL。 */
    private String pendingRestoreUrl = null;

    /** 把内存里的最新阅读进度落库。 */
    private void persistProgress() {
        if (record == null || lastChapterUrl.isEmpty()) return;
        ReaderHistory.save(record, lastChapterName, lastChapterUrl, lastAnchor, lastTotal);
        SpiderDebug.log(TAG, "saveProgress index=%d anchor=%d/%d chapter=%s", index, lastAnchor, lastTotal, lastChapterName);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_reader);
        applyImmersive();

        // 兼容 Android 13+ enableOnBackInvokedCallback：无论手势返回还是系统返回键，都直接关闭阅读页
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        Intent it = getIntent();
        kind = it.getIntExtra(EXTRA_KIND, 1);
        cacheKey = it.getStringExtra(EXTRA_CACHE_KEY);
        if (cacheKey == null) cacheKey = "";
        // 优先取静态缓存（大数据不走 Intent，避免 TransactionTooLargeException）
        payload = cacheKey.isEmpty() ? null : PAYLOAD_CACHE.get(cacheKey);
        if (payload == null) payload = it.getStringExtra(EXTRA_PAYLOAD);
        if (payload == null) payload = "";
        siteKey = it.getStringExtra(EXTRA_SITE_KEY);
        if (siteKey == null) siteKey = "";
        flag = it.getStringExtra(EXTRA_FLAG);
        if (flag == null) flag = "";
        vodId = it.getStringExtra(EXTRA_VOD_ID);
        if (vodId == null) vodId = "";
        vodName = it.getStringExtra(EXTRA_VOD_NAME);
        if (vodName == null) vodName = "";
        vodPic = it.getStringExtra(EXTRA_VOD_PIC);
        if (vodPic == null) vodPic = "";
        chapters = cacheKey.isEmpty() ? null : CHAPTER_CACHE.get(cacheKey);
        if (chapters == null) chapters = it.getParcelableArrayListExtra(EXTRA_CHAPTERS);
        if (chapters == null) chapters = new ArrayList<>();
        index = it.getIntExtra(EXTRA_INDEX, 0);
        localPath = it.getStringExtra(EXTRA_LOCAL_PATH);
        if (localPath == null) localPath = "";

        // 注册当前阅读器实例，供播放器解析完成后回传结果
        NovelRouter.currentReader = this;

        // 阅读进度：有 vodId 才能稳定标识一本书；无则只阅读不记录
        record = new ReaderHistory.Record(siteKey, vodId, flag, vodName, vodPic);
        if (record.canUse()) restoreFromHistory();

        webView = findViewById(R.id.web_view);
        progress = findViewById(R.id.progress);
        loading = findViewById(R.id.loading);
        TextView loadingText = findViewById(R.id.loading_text);
        loadingText.setText(vodName.isEmpty()
                ? getString(R.string.reader_opening_default)
                : getString(R.string.reader_opening, vodName));
        // 与 reader.html 默认主题同色，避免 WebView 首帧前出现黑屏跳变
        webView.setBackgroundColor(0xFFE8ECF1);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // 允许 file:// 页面加载同源 assets 资源、pdf.worker.min.js worker 以及 fetch 缓存目录里的 PDF 文件
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(this, "AndroidReader");
        SpiderDebug.log(TAG, "create kind=%d payloadLen=%d chapters=%d index=%d site=%s flag=%s local=%s",
                kind, payload.length(), chapters.size(), index, siteKey, flag, localPath);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                SpiderDebug.log(TAG, "progress=%d", newProgress);
                if (progress != null) {
                    if (newProgress >= 100) progress.setVisibility(View.GONE);
                    else { progress.setVisibility(View.VISIBLE); progress.setProgress(newProgress); }
                }
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                // JS 报错 / console 输出：reader.html 渲染失败时的唯一线索
                SpiderDebug.log(TAG, "console [%s] %s (%s:%d)",
                        msg.messageLevel(), msg.message(), msg.sourceId(), msg.lineNumber());
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                SpiderDebug.log(TAG, "pageStarted url=%s", url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                SpiderDebug.log(TAG, "pageFinished url=%s pending=%b local=%s", url, pendingJson != null, localPath);
                pageFinished = true;
                if (pendingJson != null) {
                    inject(pendingJson);
                    pendingJson = null;
                } else if (!localPath.isEmpty()) {
                    loadLocalFileAsync();
                } else {
                    inject(buildDataJson());
                }
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                SpiderDebug.log(TAG, "resourceError url=%s code=%d desc=%s main=%b",
                        request.getUrl(), error.getErrorCode(), error.getDescription(), request.isForMainFrame());
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse response) {
                SpiderDebug.log(TAG, "httpError url=%s status=%d", request.getUrl(), response.getStatusCode());
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // 渲染进程崩溃（OOM / crash）：不吞掉，否则整个 Activity 会被系统杀掉
                SpiderDebug.log(TAG, "renderProcessGone crashed=%b", detail.didCrash());
                finish();
                return true;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("readerpic://")) return fetchImageWithReferer(u);
                return null;
            }
        });

        webView.loadUrl("file:///android_asset/reader.html");
        // 兜底：WebView 迟迟不回调时也要放开占位层，不能让它永久遮挡页面
        loading.postDelayed(this::hideLoading, 8000);
    }

    private void inject(String json) {
        if (webView == null) return;
        try {
            // 用 typeof 探测模板函数是否就绪，回执写日志：区分「注入没发生」和「注入了但渲染失败」
            String js = "(function(){try{"
                    + "if(typeof window.__injectReader!=='function') return 'no-fn';"
                    + "window.__injectReader(" + json + ");"
                    + "var r=document.getElementById('reader');"
                    + "return 'ok children='+(r?r.children.length:-1)+' theme='+document.body.className;"
                    + "}catch(e){return 'err '+e;}})()";
            SpiderDebug.log(TAG, "inject len=%d head=%s", json.length(), json.substring(0, Math.min(160, json.length())));
            webView.evaluateJavascript(js, value -> {
                SpiderDebug.log(TAG, "injectResult %s", value);
                afterInject();
            });
        } catch (Throwable e) {
            SpiderDebug.log(TAG, e);
            hideLoading();
        }
    }

    /**
     * 首屏内容注入完成后：
     * 1) 上次读的不是本章 → 直接解析目标章（占位层保持显示，避免闪现第一章）
     * 2) 已是目标章 → 恢复章节内滚动位置并放开占位层
     */
    private void afterInject() {
        if (pendingRestoreUrl != null) {
            String url = pendingRestoreUrl;
            pendingRestoreUrl = null;
            resolveChapterSelf(url);
            return;
        }
        restoreScroll();
        hideLoading();
    }

    /** 恢复章节内位置（只在本次进入时生效一次）。 */
    private void restoreScroll() {
        if (restoreAnchor <= 0 || webView == null) return;
        long a = restoreAnchor;
        long t = restoreTotal;
        restoreAnchor = 0;
        restoreTotal = 0;
        // 旧版小说记录（total==SCALE）传 0，让 HTML 走百分比兜底：a/SCALE 即原百分比
        if (t == ReaderHistory.SCALE) {
            webView.evaluateJavascript("window.__restoreScroll && window.__restoreScroll("
                    + ((double) a / ReaderHistory.SCALE) + ", 0);", null);
        } else {
            webView.evaluateJavascript("window.__restoreScroll && window.__restoreScroll(" + a + ", " + t + ");", null);
        }
    }

    /** 内容已渲染 → 淡出原生占位层，露出 WebView。 */
    private void hideLoading() {
        if (loading == null || loading.getVisibility() != View.VISIBLE) return;
        loading.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            loading.setVisibility(View.GONE);
            loading.setAlpha(1f);
        }).start();
    }

    /** 把 novel:// / pics:// payload 解析成阅读数据 JSON，注入模板。 */
    private String buildDataJson() {
        try {
            JSONObject data = new JSONObject();
            data.put("kind", kind);
            data.put("siteKey", siteKey);
            data.put("flag", flag);
            data.put("vodId", vodId);
            data.put("vodName", vodName);
            data.put("vodPic", vodPic);
            data.put("current", index);
            data.put("chapters", buildChaptersJson());

            if (kind == 1) {
                // 小说：novel://{title,content}
                JSONObject n = parseNovel(payload);
                data.put("title", n.optString("title", vodName));
                data.put("content", n.optString("content", ""));
            } else {
                // 漫画：pics://url1&&url2（图片）或 pics://xxx.pdf（PDF 漫画）
                String pdfFile = downloadPdfIfNeeded(payload);
                if (pdfFile != null) {
                    data.put("kind", 3); // PDF 漫画
                    data.put("pdfFile", pdfFile);
                } else {
                    data.put("images", parsePics(payload));
                }
                String t = nvl(currentChapterName(), vodName);
                data.put("title", t);
            }
            return data.toString();
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "buildDataJson failed kind=%d payloadLen=%d", kind, payload.length());
            SpiderDebug.log(TAG, e);
            return "{\"kind\":" + kind + ",\"title\":\"加载失败\",\"content\":\"\",\"images\":[],\"chapters\":[],\"current\":0}";
        }
    }

    private String nvl(String a, String b) {
        return (a == null || a.isEmpty()) ? (b == null ? "" : b) : a;
    }

    private String currentChapterName() {
        if (index >= 0 && index < chapters.size()) return chapters.get(index).getName();
        return "";
    }

    private JSONArray buildChaptersJson() {
        JSONArray arr = new JSONArray();
        for (Episode e : chapters) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", e.getName() == null ? "" : e.getName());
                o.put("url", e.getUrl() == null ? "" : e.getUrl());
            } catch (Throwable ignore) {}
            arr.put(o);
        }
        return arr;
    }

    /** novel://{json} → {title, content}，容错常见字段。 */
    private JSONObject parseNovel(String raw) {
        JSONObject out = new JSONObject();
        try {
            String s = raw.trim();
            if (s.startsWith("novel://")) s = s.substring("novel://".length()).trim();
            JSONObject o = new JSONObject(s);
            String title = o.optString("title", "");
            String content = "";
            for (String k : new String[]{"content", "text", "book", "body", "data", "txt", "chapter", "article"}) {
                Object v = o.opt(k);
                if (v instanceof String && !((String) v).isEmpty()) { content = (String) v; break; }
                else if (v instanceof JSONArray && ((JSONArray) v).length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < ((JSONArray) v).length(); i++) {
                        if (i > 0) sb.append("\n");
                        sb.append(((JSONArray) v).opt(i));
                    }
                    content = sb.toString();
                    break;
                }
            }
            out.put("title", title);
            out.put("content", content);
        } catch (Throwable e) {
            // 非 JSON：把整段当正文
            try { out.put("content", raw); } catch (Throwable ignore) {}
        }
        return out;
    }

    /** pics://url1&&url2... → [url1,url2,...]。
     *  带 @Referer= 防盗链头的图片，转成 readerpic:// 自定义 scheme，由 shouldInterceptRequest 加 Referer 头请求。 */
    private JSONArray parsePics(String raw) {
        JSONArray arr = new JSONArray();
        if (raw == null) return arr;
        String s = raw.trim();
        if (s.startsWith("pics://") || s.startsWith("manga://")) s = s.substring(s.indexOf("://") + 3);
        for (String u : s.split("&&")) {
            if (u == null) continue;
            u = u.trim();
            if (u.isEmpty()) continue;
            String referer = null;
            int ref = u.indexOf("@Referer=");
            if (ref > 0) {
                referer = u.substring(ref + "@Referer=".length()).trim();
                u = u.substring(0, ref);
            }
            int ua = u.indexOf("@User-Agent=");
            if (ua > 0) u = u.substring(0, ua);
            if (referer != null && !referer.isEmpty()) {
                arr.put("readerpic://img?u=" + android.net.Uri.encode(u) + "&r=" + android.net.Uri.encode(referer));
            } else {
                arr.put(u);
            }
        }
        return arr;
    }

    /** 检测 pics:// payload 是否为 PDF 漫画；若是则下载到缓存并返回 file:// URL，否则返回 null。 */
    private String downloadPdfIfNeeded(String raw) {
        try {
            if (raw == null) return null;
            String s = raw.trim();
            if (!s.startsWith("pics://") && !s.startsWith("manga://")) return null;
            s = s.substring(s.indexOf("://") + 3);
            String first = s.split("&&", 2)[0].trim();
            if (first.isEmpty()) return null;
            String referer = null;
            int ref = first.indexOf("@Referer=");
            if (ref > 0) {
                referer = first.substring(ref + "@Referer=".length()).trim();
                first = first.substring(0, ref).trim();
            }
            int ua = first.indexOf("@User-Agent=");
            if (ua > 0) first = first.substring(0, ua).trim();
            String path = first;
            int q = path.indexOf('?');
            if (q > 0) path = path.substring(0, q);
            if (!path.toLowerCase().endsWith(".pdf")) return null;
            return downloadPdfToCache(first, referer);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 用 OkHttp 加 Referer/UA 头下载 PDF 到缓存目录，返回 file:// 绝对路径（复用缓存）。 */
    private String downloadPdfToCache(String url, String referer) {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "readerpdf");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, md5(url + "|" + (referer == null ? "" : referer)) + ".pdf");
            if (f.exists() && f.length() > 0) return "file://" + f.getAbsolutePath();
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
            if (referer != null && !referer.isEmpty()) rb.header("Referer", referer);
            okhttp3.Response resp = IMAGE_CLIENT.newCall(rb.build()).execute();
            if (!resp.isSuccessful() || resp.body() == null) { resp.close(); return null; }
            byte[] body = resp.body().bytes();
            resp.close();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(body);
            fos.close();
            return "file://" + f.getAbsolutePath();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /* ---------------- 本地文件阅读 ---------------- */

    private void loadLocalFileAsync() {
        new Thread(() -> {
            String json = buildLocalDataJson(localPath);
            runOnUiThread(() -> {
                if (pageFinished) inject(json);
                else pendingJson = json;
            });
        }).start();
    }

    /** 按扩展名把本地文件分流成阅读数据 JSON。 */
    private String buildLocalDataJson(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return errorJson("文件不存在");
            String name = f.getName();
            String lower = name.toLowerCase(java.util.Locale.ROOT);

            if (lower.endsWith(".epub")) return buildEpubJson(f);
            if (lower.endsWith(".zip")) return buildZipJson(f);
            if (lower.endsWith(".pdf")) return buildLocalComicJson(f, true);
            if (isImage(lower)) return buildLocalComicJson(f, false);

            JSONObject data = new JSONObject();
            data.put("siteKey", "");
            data.put("flag", "");
            data.put("vodId", "");
            data.put("vodName", name);
            data.put("vodPic", "");
            data.put("current", 0);
            data.put("chapters", new JSONArray());

            if (lower.endsWith(".txt") || lower.endsWith(".html") || lower.endsWith(".htm")) {
                data.put("kind", 1);
                data.put("title", name);
                data.put("content", readText(f));
            } else {
                return errorJson("不支持的格式：" + name);
            }
            return data.toString();
        } catch (Throwable e) {
            return errorJson("读取失败：" + e.getMessage());
        }
    }

    /** 本地漫画（图片/PDF）：识别章节子目录，当前章独立渲染，chapters 含目录路径供本地切章。 */
    private String buildLocalComicJson(java.io.File f, boolean isPdf) {
        try {
            java.io.File dir = f.getParentFile();
            java.util.List<java.io.File> chapterDirs = detectChapterDirs(dir);

            JSONObject data = new JSONObject();
            data.put("vodName", f.getName());
            data.put("chapters", buildChaptersJson(chapterDirs));
            int cur = chapterDirs.size() > 1 ? indexOf(chapterDirs, dir) : 0;
            data.put("current", cur);
            data.put("title", chapterDirs.size() > 1 ? dir.getName() : f.getName());

            if (isPdf) {
                data.put("kind", 3);
                data.put("pdfFile", "file://" + f.getAbsolutePath());
            } else {
                data.put("kind", 2);
                data.put("images", collectImagesFromDir(dir));
            }
            return data.toString();
        } catch (Throwable e) {
            return errorJson("读取失败：" + e.getMessage());
        }
    }

    /** 检测章节结构：dir 的父目录下若有多个子目录，则这些子目录视为章节。 */
    private java.util.List<java.io.File> detectChapterDirs(java.io.File dir) {
        java.util.List<java.io.File> out = new java.util.ArrayList<>();
        if (dir == null) return out;
        java.io.File grand = dir.getParentFile();
        if (grand == null) return out;
        java.io.File[] subs = grand.listFiles(java.io.File::isDirectory);
        if (subs == null || subs.length <= 1) return out;
        for (java.io.File s : subs) out.add(s);
        java.util.Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private int indexOf(java.util.List<java.io.File> list, java.io.File dir) {
        if (dir == null) return 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).equals(dir)) return i;
        return 0;
    }

    private JSONArray buildChaptersJson(java.util.List<java.io.File> chapterDirs) {
        JSONArray arr = new JSONArray();
        for (java.io.File cd : chapterDirs) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", cd.getName());
                o.put("url", cd.getAbsolutePath());
            } catch (Throwable ignore) {}
            arr.put(o);
        }
        return arr;
    }

    private JSONArray collectImagesFromDir(java.io.File dir) {
        JSONArray arr = new JSONArray();
        if (dir == null) return arr;
        java.io.File[] files = dir.listFiles();
        if (files == null) return arr;
        java.util.List<java.io.File> imgs = new java.util.ArrayList<>();
        for (java.io.File x : files) {
            if (x.isFile() && isImage(x.getName().toLowerCase(java.util.Locale.ROOT))) imgs.add(x);
        }
        java.util.Collections.sort(imgs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (java.io.File img : imgs) arr.put("file://" + img.getAbsolutePath());
        return arr;
    }

    private String errorJson(String msg) {
        return "{\"kind\":1,\"title\":\"读取失败\",\"content\":\"" + escape(msg) + "\",\"images\":[],\"chapters\":[],\"current\":0}";
    }

    private String escape(String s) {
        return (s == null ? "" : s).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private boolean isImage(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private String readText(java.io.File f) {
        try {
            byte[] bytes = readAllBytes(f);
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                return new String(bytes, 3, bytes.length - 3, "UTF-8");
            }
            try {
                java.nio.charset.CharsetDecoder dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                return dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (Throwable e) {
                return new String(bytes, "GBK");
            }
        } catch (Throwable e) {
            return "";
        }
    }

    private byte[] readAllBytes(java.io.File f) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    /** ZIP 漫画：解压所有图片，按文件名排序，写缓存目录返回 file:// URL 列表。 */
    private String buildZipJson(java.io.File f) {
        try {
            java.util.List<String> imgs = new java.util.ArrayList<>();
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(f);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
            java.io.File dir = new java.io.File(getCacheDir(), "readerzip/" + md5(f.getAbsolutePath()));
            if (!dir.exists()) dir.mkdirs();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName().toLowerCase(java.util.Locale.ROOT);
                if (!isImage(n)) continue;
                java.io.File out = new java.io.File(dir, Integer.toHexString(e.getName().hashCode()) + extOf(n));
                if (!out.exists() || out.length() == 0) {
                    java.io.InputStream in = zip.getInputStream(e);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int c;
                    while ((c = in.read(buf)) > 0) fos.write(buf, 0, c);
                    fos.close();
                    in.close();
                }
                imgs.add(e.getName());
            }
            zip.close();
            java.util.Collections.sort(imgs, String::compareToIgnoreCase);
            JSONObject data = new JSONObject();
            data.put("kind", 2);
            data.put("title", f.getName());
            data.put("vodName", f.getName());
            data.put("current", 0);
            data.put("chapters", new JSONArray());
            JSONArray arr = new JSONArray();
            for (String n : imgs) {
                arr.put("file://" + new java.io.File(dir, Integer.toHexString(n.hashCode()) + extOf(n)).getAbsolutePath());
            }
            data.put("images", arr);
            return data.toString();
        } catch (Throwable e) {
            return errorJson("ZIP 解压失败：" + e.getMessage());
        }
    }

    private String extOf(String lower) {
        int i = lower.lastIndexOf('.');
        return i >= 0 ? lower.substring(i) : ".jpg";
    }

    /** EPUB：解压 → 解析 container.xml/opf/spine → 拼接图文 HTML（图片提取到缓存转 file:// URL）。 */
    private String buildEpubJson(java.io.File f) {
        try {
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(f);
            String opfPath = findOpfPath(zip);
            if (opfPath == null) { zip.close(); return errorJson("无效 EPUB：找不到 OPF"); }
            String opfDir = "";
            int slash = opfPath.lastIndexOf('/');
            if (slash >= 0) opfDir = opfPath.substring(0, slash + 1);

            String opf = readEntry(zip, opfPath);
            if (opf == null) { zip.close(); return errorJson("无法读取 OPF"); }

            java.util.Map<String, String> manifest = new java.util.LinkedHashMap<>();
            java.util.regex.Matcher mi = java.util.regex.Pattern.compile("<item[^>]*id=\"([^\"]*)\"[^>]*href=\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(opf);
            while (mi.find()) manifest.put(mi.group(1), mi.group(2));

            java.util.List<String> spine = new java.util.ArrayList<>();
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("<itemref[^>]*idref=\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(opf);
            while (sm.find()) {
                String href = manifest.get(sm.group(1));
                if (href != null) spine.add(opfDir + href);
            }
            if (spine.isEmpty()) {
                for (String href : manifest.values()) if (href.toLowerCase().endsWith(".xhtml") || href.toLowerCase().endsWith(".html")) spine.add(opfDir + href);
            }

            java.io.File imgDir = new java.io.File(getCacheDir(), "readerepub/" + md5(f.getAbsolutePath()));
            if (!imgDir.exists()) imgDir.mkdirs();

            java.util.List<String> collectedImages = new java.util.ArrayList<>();
            StringBuilder html = new StringBuilder();
            int totalTextLen = 0;
            for (String xhtmlPath : spine) {
                String xhtml = readEntry(zip, xhtmlPath);
                if (xhtml == null) continue;
                String body = extractBody(xhtml);
                if (body == null || body.trim().isEmpty()) body = xhtml;
                totalTextLen += stripTagsLength(body);
                body = replaceImages(zip, body, xhtmlPath, imgDir, collectedImages);
                if (!body.trim().isEmpty()) html.append("<section class=\"epub-chapter\">").append(body).append("</section>\n");
            }
            zip.close();

            JSONObject data = new JSONObject();
            data.put("title", f.getName());
            data.put("vodName", f.getName());
            data.put("current", 0);
            data.put("chapters", new JSONArray());

            // 漫画判定：有图片且文字极少（平均每张图 < 30 字符，纯图 EPUB 漫画）
            boolean isComic = !collectedImages.isEmpty() && totalTextLen < collectedImages.size() * 30;
            if (isComic) {
                data.put("kind", 2);
                JSONArray arr = new JSONArray();
                for (String u : collectedImages) arr.put(u);
                data.put("images", arr);
            } else {
                data.put("kind", 1);
                data.put("content", html.toString());
            }
            return data.toString();
        } catch (Throwable e) {
            return errorJson("EPUB 解析失败：" + e.getMessage());
        }
    }

    /** 统计去掉 HTML 标签后的纯文本长度（用于判断 EPUB 是漫画还是小说）。 */
    private int stripTagsLength(String html) {
        try {
            return html.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", "").length();
        } catch (Throwable e) {
            return 0;
        }
    }

    private String findOpfPath(java.util.zip.ZipFile zip) {
        try {
            String container = readEntry(zip, "META-INF/container.xml");
            if (container == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("full-path=\"([^\"]+)\"").matcher(container);
            return m.find() ? m.group(1) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private String readEntry(java.util.zip.ZipFile zip, String path) {
        try {
            java.util.zip.ZipEntry e = zip.getEntry(path);
            if (e == null) return null;
            java.io.InputStream in = zip.getInputStream(e);
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }

    private String extractBody(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<body[^>]*>(.*?)</body>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** 把 body 里 <img src> / <image xlink:href> 的图片提取到缓存并替换为 file:// URL，同时收集到 collected。 */
    private String replaceImages(java.util.zip.ZipFile zip, String body, String xhtmlPath, java.io.File imgDir, java.util.List<String> collected) {
        String xhtmlDir = "";
        int s = xhtmlPath.lastIndexOf('/');
        if (s >= 0) xhtmlDir = xhtmlPath.substring(0, s + 1);
        body = replaceImagesByPattern(zip, body, xhtmlDir, imgDir, "<img[^>]*src=[\"']([^\"']+)[\"'][^>]*>", "<img src=\"%s\">", collected);
        body = replaceImagesByPattern(zip, body, xhtmlDir, imgDir, "<image[^>]*xlink:href=[\"']([^\"']+)[\"'][^>]*>", "<img src=\"%s\">", collected);
        return body;
    }

    private String replaceImagesByPattern(java.util.zip.ZipFile zip, String body, String xhtmlDir, java.io.File imgDir, String regex, String replacement, java.util.List<String> collected) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(body);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String url = extractImage(zip, m.group(1), xhtmlDir, imgDir);
            if (collected != null) collected.add(url);
            sb.append(body, last, m.start());
            sb.append(replacement.replace("%s", url));
            last = m.end();
        }
        sb.append(body, last, body.length());
        return sb.toString();
    }

    private String extractImage(java.util.zip.ZipFile zip, String src, String xhtmlDir, java.io.File imgDir) {
        try {
            String entryPath = normalize(xhtmlDir + src);
            java.util.zip.ZipEntry e = zip.getEntry(entryPath);
            if (e == null) e = findEntryByBasename(zip, src);
            if (e == null) return src;
            java.io.File out = new java.io.File(imgDir, Integer.toHexString(e.getName().hashCode()) + extOf(e.getName().toLowerCase()));
            if (!out.exists() || out.length() == 0) {
                java.io.InputStream in = zip.getInputStream(e);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                in.close();
            }
            return "file://" + out.getAbsolutePath();
        } catch (Throwable ex) {
            return src;
        }
    }

    private java.util.zip.ZipEntry findEntryByBasename(java.util.zip.ZipFile zip, String src) {
        String base = src;
        int q = base.indexOf('?'); if (q > 0) base = base.substring(0, q);
        int sl = base.lastIndexOf('/'); if (sl >= 0) base = base.substring(sl + 1);
        try { base = java.net.URLDecoder.decode(base, "UTF-8"); } catch (Throwable e) {}
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            java.util.zip.ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName();
            int nsl = n.lastIndexOf('/');
            if (nsl >= 0) n = n.substring(nsl + 1);
            if (n.equals(base)) return e;
        }
        return null;
    }

    private String normalize(String p) {
        String[] parts = p.split("/");
        java.util.List<String> stack = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!stack.isEmpty()) stack.remove(stack.size() - 1); }
            else stack.add(part);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) { if (i > 0) sb.append("/"); sb.append(stack.get(i)); }
        return sb.toString();
    }

    /** 拦截 readerpic:// 自定义 scheme，用 OkHttp 加 Referer/UA 头请求图片（解决防盗链图片加载失败）。 */
    private android.webkit.WebResourceResponse fetchImageWithReferer(String proxyUrl) {
        try {
            android.net.Uri u = android.net.Uri.parse(proxyUrl);
            String realUrl = u.getQueryParameter("u");
            String referer = u.getQueryParameter("r");
            if (realUrl == null || realUrl.isEmpty()) return null;
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(realUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
            if (referer != null && !referer.isEmpty()) rb.header("Referer", referer);
            okhttp3.Response resp = IMAGE_CLIENT.newCall(rb.build()).execute();
            if (!resp.isSuccessful()) { resp.close(); return null; }
            byte[] body = resp.body() != null ? resp.body().bytes() : new byte[0];
            String mime = resp.header("Content-Type", "image/jpeg");
            String encoding = resp.header("Content-Encoding");
            resp.close();
            return new android.webkit.WebResourceResponse(mime, encoding, new java.io.ByteArrayInputStream(body));
        } catch (Throwable e) {
            return null;
        }
    }

    /* ---------------- JS bridge（HTML 里可调 AndroidReader.xxx） ---------------- */

    @JavascriptInterface
    public void back() {
        runOnUiThread(this::finish);
    }

    @JavascriptInterface
    public void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    /**
     * HTML 滚动时上报阅读进度（已在 JS 线程，直接记内存；落库由 onPause / onDestroy 触发，
     * 避免滚动过程中频繁写数据库）。
     *
     * @param anchor 章节内锚点序号（0 基）：小说=段落，漫画/PDF=页
     * @param total  锚点总数
     */
    @JavascriptInterface
    public void saveProgress(int chapterIndex, String chapterUrl, String chapterName, int anchor, int total) {
        index = chapterIndex;
        lastChapterUrl = chapterUrl == null ? "" : chapterUrl;
        lastChapterName = chapterName == null ? "" : chapterName;
        lastAnchor = anchor;
        lastTotal = total;
    }

    /**
     * 读取上次阅读记录：定位章节下标与章节内锚点。
     *
     * 若上次读的不是本次传入的章节（站点分流总是解析第一章），
     * 记下待恢复的章节 URL，等页面就绪后直接解析该章，不先渲染第一章再跳。
     */
    private void restoreFromHistory() {
        History h = ReaderHistory.find(siteKey, vodId);
        if (h == null) return;
        String url = h.getEpisodeUrl();
        if (TextUtils.isEmpty(url)) return;
        int at = indexOfChapter(url);
        if (at < 0) return;
        index = at;
        // position/duration 即锚点序号/总数；旧版小说记录存的是百分比×SCALE，
        // HTML 侧按 total 是否等于 SCALE 兜底处理。
        restoreAnchor = h.getPosition();
        restoreTotal = h.getDuration();
        lastChapterUrl = url;
        lastChapterName = h.getVodRemarks() == null ? "" : h.getVodRemarks();
        lastAnchor = (int) restoreAnchor;
        lastTotal = (int) restoreTotal;
        // 传入 payload 已是该章内容时无需重新解析
        if (!extractedTitleMatches(lastChapterName)) pendingRestoreUrl = url;
        SpiderDebug.log(TAG, "restore index=%d anchor=%d/%d kind=%d chapter=%s reresolve=%b",
                index, restoreAnchor, restoreTotal, kind, lastChapterName, pendingRestoreUrl != null);
    }

    /** 传入的 payload 是否正是待恢复的那一章（按标题比对）。 */
    private boolean extractedTitleMatches(String chapterName) {
        if (TextUtils.isEmpty(chapterName)) return false;
        try {
            JSONObject n = parseNovel(payload);
            return chapterName.equals(n.optString("title", ""));
        } catch (Throwable e) {
            return false;
        }
    }

    /** 换章：HTML 点目录时回调。本地模式读目录章节，在线模式自行解析（无播放器时也能切章）。 */
    @JavascriptInterface
    public void loadChapter(String chapterUrl) {
        if (TextUtils.isEmpty(chapterUrl)) return;
        runOnUiThread(() -> {
            if (!localPath.isEmpty() && isLocalDir(chapterUrl)) {
                loadLocalChapter(chapterUrl);
                return;
            }
            // 站点级分流（ReaderContentHandler → NovelRouter.openSite）不经过播放器，host 为 null，
            // 此时阅读器用自己持有的 siteKey/flag 直接解析章节。
            if (!siteKey.isEmpty()) {
                resolveChapterSelf(chapterUrl);
                return;
            }
            NovelReaderHost h = NovelRouter.host;
            if (h != null) h.labPlayEpisode(chapterUrl);
            else Toast.makeText(this, R.string.reader_chapter_failed, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 自行解析章节内容：复用 SiteApi.playerContent，拿到 novel:// / pics:// 后注入。
     * 返回 parse=1（要求二次解析）时交给播放器宿主处理；无宿主则提示失败。
     */
    private void resolveChapterSelf(String chapterUrl) {
        int at = indexOfChapter(chapterUrl);
        RESOLVE_EXECUTOR.execute(() -> {
            String payloadOut = null;
            int kindOut = 0;
            boolean needHost = false;
            try {
                com.fongmi.android.tv.bean.Result r = com.fongmi.android.tv.api.SiteApi.playerContent(siteKey, flag, chapterUrl);
                String u = r.getRealUrl();
                String t = u == null ? "" : u.trim();
                if (t.startsWith("novel://")) kindOut = 1;
                else if (t.startsWith("pics://") || t.startsWith("manga://")) kindOut = 2;
                else if (r.needParse()) needHost = true;
                else kindOut = kind; // 站点已判定为阅读源，内容按当前类型处理
                payloadOut = u;
                SpiderDebug.log(TAG, "resolveChapter kind=%d needHost=%b len=%d url=%s",
                        kindOut, needHost, u == null ? 0 : u.length(), chapterUrl);
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "resolveChapter failed url=%s", chapterUrl);
                SpiderDebug.log(TAG, e);
            }
            final String fp = payloadOut;
            final int fk = kindOut;
            final boolean fh = needHost;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fk != 0 && fp != null && !fp.isEmpty()) {
                    if (at >= 0) index = at;
                    onEpisodeResolved(fk, fp, at >= 0 ? chapters.get(at).getName() : "");
                    return;
                }
                NovelReaderHost h = NovelRouter.host;
                if (fh && h != null) h.labPlayEpisode(chapterUrl);
                else Toast.makeText(this, R.string.reader_chapter_failed, Toast.LENGTH_SHORT).show();
                // 解析失败也要放开占位层，否则恢复历史章节失败时会一直挡着
                hideLoading();
            });
        });
    }

    private int indexOfChapter(String chapterUrl) {
        for (int i = 0; i < chapters.size(); i++) {
            if (chapterUrl.equals(chapters.get(i).getUrl())) return i;
        }
        return -1;
    }

    private boolean isLocalDir(String s) {
        return s.startsWith("/storage") || s.startsWith("/sdcard") || s.startsWith("file://");
    }

    /** 本地漫画切章：读章节目录，PDF 优先否则图片，注入新章数据。 */
    private void loadLocalChapter(String dirPath) {
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(dirPath.startsWith("file://") ? dirPath.substring("file://".length()) : dirPath);
                JSONObject d = new JSONObject();
                java.io.File pdf = findFirstPdf(dir);
                if (pdf != null) {
                    d.put("kind", 3);
                    d.put("pdfFile", "file://" + pdf.getAbsolutePath());
                } else {
                    d.put("kind", 2);
                    d.put("images", collectImagesFromDir(dir));
                }
                d.put("title", dir.getName());
                String json = d.toString();
                runOnUiThread(() -> injectChapter(json));
            } catch (Throwable ignore) {}
        }).start();
    }

    private java.io.File findFirstPdf(java.io.File dir) {
        java.io.File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return null;
        java.util.List<java.io.File> pdfs = new java.util.ArrayList<>();
        for (java.io.File x : files) {
            if (x.isFile() && x.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) pdfs.add(x);
        }
        if (pdfs.isEmpty()) return null;
        java.util.Collections.sort(pdfs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return pdfs.get(0);
    }

    /**
     * 播放器解析完成后回传结果（由 NovelRouter.routeReaderEngine 调用，已在主线程）。
     * 把 novel:// / pics:// 解析成阅读数据注入 HTML。
     */
    public void onEpisodeResolved(int kind, String payload, String title) {
        if (webView == null) return;
        try {
            JSONObject d = new JSONObject();
            if (kind == 1) {
                JSONObject n = parseNovel(payload);
                d.put("title", n.optString("title", title == null ? "" : title));
                d.put("content", n.optString("content", ""));
            } else {
                String pdfFile = downloadPdfIfNeeded(payload);
                if (pdfFile != null) {
                    d.put("kind", 3);
                    d.put("pdfFile", pdfFile);
                } else {
                    d.put("images", parsePics(payload));
                }
                d.put("title", title == null ? "" : title);
            }
            injectChapter(d.toString());
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "onEpisodeResolved failed kind=%d", kind);
            SpiderDebug.log(TAG, e);
        }
    }

    private void injectChapter(String json) {
        if (webView == null) return;
        try {
            SpiderDebug.log(TAG, "injectChapter len=%d head=%s", json.length(), json.substring(0, Math.min(160, json.length())));
            webView.evaluateJavascript("window.__updateChapter && window.__updateChapter(" + json + ");",
                    value -> {
                        SpiderDebug.log(TAG, "injectChapterResult %s", value);
                        restoreScroll();
                        hideLoading();
                    });
        } catch (Throwable e) {
            SpiderDebug.log(TAG, e);
        }
    }

    @Override
    public void onBackPressed() {
        // 直接关闭阅读页返回播放器（兼容旧 API；新 API 走 OnBackPressedDispatcher）
        finish();
    }

    @Override
    protected void onPause() {
        // 切后台 / 返回都先落库，避免进程被回收后丢进度
        persistProgress();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        persistProgress();
        // 清理阅读器静态引用 + 标记关闭时间，避免残留的 playerContent 回调在返回后重新拉起阅读器
        NovelRouter.currentReader = null;
        NovelRouter.readerClosedAt = System.currentTimeMillis();
        if (!cacheKey.isEmpty()) {
            CHAPTER_CACHE.remove(cacheKey);
            PAYLOAD_CACHE.remove(cacheKey);
        }
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("AndroidReader");
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.destroy();
            } catch (Throwable ignore) {}
        }
        super.onDestroy();
    }

    private void applyImmersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                getWindow().setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } catch (Throwable ignore) {}
        } else {
            try {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                getWindow().getDecorView().setSystemUiVisibility(flags);
            } catch (Throwable ignore) {}
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }
}
