package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.DownloadGroup;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.bean.Result;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadManager {

    private static DownloadManager sInstance;

    private final List<DownloadItem> mItems;
    private final List<Callback> mCallbacks;
    private final ExecutorService mExecutor;
    private final Map<String, Download> mDownloads;
    private final Map<String, Future<?>> mFutures;
    private final AtomicBoolean mNotifyPosted = new AtomicBoolean(false);

    public static synchronized DownloadManager get() {
        if (sInstance == null) sInstance = new DownloadManager();
        return sInstance;
    }

    private DownloadManager() {
        mItems = new ArrayList<>();
        mCallbacks = new ArrayList<>();
        mDownloads = new ConcurrentHashMap<>();
        mFutures = new ConcurrentHashMap<>();
        mExecutor = Executors.newFixedThreadPool(2);
    }

    public List<DownloadItem> getItems() {
        return new ArrayList<>(mItems);
    }

    public List<DownloadGroup> getGroups() {
        java.util.LinkedHashMap<String, List<DownloadItem>> map = new java.util.LinkedHashMap<>();
        for (DownloadItem item : mItems) {
            String key = TextUtils.isEmpty(item.getGroup()) ? item.getName() : item.getGroup();
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        List<DownloadGroup> groups = new ArrayList<>();
        for (java.util.Map.Entry<String, List<DownloadItem>> entry : map.entrySet()) {
            List<DownloadItem> items = entry.getValue();
            String cover = "";
            for (DownloadItem it : items) {
                if (!TextUtils.isEmpty(it.getCover())) {
                    cover = it.getCover();
                    break;
                }
            }
            groups.add(new DownloadGroup(entry.getKey(), groupName(entry.getKey()), cover, items));
        }
        return groups;
    }

    public DownloadGroup getGroup(String key) {
        for (DownloadGroup group : getGroups()) {
            if (group.getKey().equals(key)) return group;
        }
        return null;
    }

    private static String groupName(String key) {
        int idx = key.indexOf("$$");
        return idx >= 0 ? key.substring(idx + 2) : key;
    }

    public void register(Callback callback) {
        if (!mCallbacks.contains(callback)) mCallbacks.add(callback);
    }

    public void unregister(Callback callback) {
        mCallbacks.remove(callback);
    }

    private void notifyChanged() {
        // 合并高频进度刷新：若已有刷新在排队，则丢弃本次，避免主线程被刷新洪流占满导致界面卡顿/无法点击
        if (mNotifyPosted.compareAndSet(false, true)) {
            App.post(() -> {
                mNotifyPosted.set(false);
                for (Callback callback : new ArrayList<>(mCallbacks)) callback.onChanged();
            });
        }
    }

    public void enqueue(DownloadItem item, String siteKey, String flag, String episodeUrl) {
        mItems.add(0, item);
        notifyChanged();
        mExecutor.execute(() -> {
            try {
                Result result = SiteApi.playerContent(siteKey, flag, episodeUrl);
                if (item.isCanceled()) {
                    item.setState(DownloadItem.CANCELED);
                    notifyChanged();
                    return;
                }
                item.setUrl(result.getRealUrl());
                item.setHeaders(result.getHeader());
                startDownload(item);
            } catch (Throwable e) {
                item.setState(DownloadItem.ERROR);
                item.setError(e.getMessage());
                notifyChanged();
            }
        });
    }

    private void startDownload(DownloadItem item) {
        if (item.isCanceled()) {
            item.setState(DownloadItem.CANCELED);
            notifyChanged();
            return;
        }
        if (TextUtils.isEmpty(item.getUrl())) {
            item.setState(DownloadItem.ERROR);
            item.setError("empty url");
            notifyChanged();
            return;
        }
        item.setState(DownloadItem.DOWNLOADING);
        notifyChanged();
        File file = buildFile(item.getName(), item.getUrl());
        if (file == null) {
            item.setState(DownloadItem.ERROR);
            item.setError("create file failed");
            notifyChanged();
            return;
        }
        item.setFilePath(file.getAbsolutePath());
        if (isM3u8(item.getUrl())) {
            downloadM3u8(item, file);
        } else {
            downloadSingle(item, file);
        }
    }

    private void downloadM3u8(DownloadItem item, File file) {
        Future<?> future = Task.submit(() -> runM3u8(item, file));
        mFutures.put(item.getId(), future);
    }

    private void runM3u8(DownloadItem item, File file) {
        try {
            File mp4 = M3u8Downloader.download(item, item.getHeaders(), file, (percent, bytes, total, speed) -> {
                if (item.getState() != DownloadItem.DOWNLOADING) return;
                item.setProgress(percent);
                item.setTotal(bytes);
                item.setSpeed(speed);
                notifyChanged();
            });
            if (item.isCanceled()) {
                item.setState(DownloadItem.CANCELED);
                Path.clear(mp4);
                notifyChanged();
                return;
            }
            item.setFilePath(mp4.getAbsolutePath());
            // 若用户在此期间暂停/取消，保留其状态，避免后台完成覆盖 PAUSED
            if (!item.isPaused() && !item.isCanceled()) {
                item.setState(DownloadItem.SUCCESS);
                item.setProgress(100);
                item.setSpeed(0);
                notifyChanged();
            }
        } catch (M3u8Downloader.PausedException e) {
            if (item.isPaused()) {
                item.setState(DownloadItem.PAUSED);
                item.setSpeed(0);
            }
            notifyChanged();
        } catch (Throwable e) {
            if (item.isCanceled()) {
                item.setState(DownloadItem.CANCELED);
            } else if (item.isPaused()) {
                item.setState(DownloadItem.PAUSED);
                item.setSpeed(0);
            } else {
                item.setState(DownloadItem.ERROR);
                item.setError(e.getMessage());
            }
            notifyChanged();
        } finally {
            mFutures.remove(item.getId());
        }
    }

    private void downloadSingle(DownloadItem item, File file) {
        Download download = Download.create(item.getUrl(), file).headers(item.getHeaders()).tag(item.getId());
        mDownloads.put(item.getId(), download);
        download.start(new Download.Callback() {
            @Override
            public void progress(int progress) {
                if (item.getState() != DownloadItem.DOWNLOADING) return;
                item.setProgress(progress);
                notifyChanged();
            }

            @Override
            public void progress(int progress, long bytes, long total, long speed, long elapsed) {
                if (item.getState() != DownloadItem.DOWNLOADING) return;
                item.setProgress(progress);
                item.setTotal(total);
                item.setSpeed(speed);
                notifyChanged();
            }

            @Override
            public void error(String msg) {
                item.setState(DownloadItem.ERROR);
                item.setError(msg);
                mDownloads.remove(item.getId());
                notifyChanged();
            }

            @Override
            public void success(File f) {
                item.setState(DownloadItem.SUCCESS);
                item.setProgress(100);
                mDownloads.remove(item.getId());
                notifyChanged();
            }
        });
    }

    public void pause(String id) {
        Download download = mDownloads.get(id);
        if (download != null) download.pause();
        // m3u8 走 M3u8Downloader 的分片线程池，绝不能通过中断 future 来暂停：
        // 中断会让 download() 的 latch.await() 抛出 InterruptedException，被误判为“下载失败”。
        // 正确做法是置 paused 标记并取消 HTTP 请求，分片线程检测到后会自然退出并保留断点。
        if (!isM3u8Item(id)) {
            Future<?> future = mFutures.get(id);
            if (future != null) future.cancel(true);
        }
        OkHttp.cancel(id);
        for (DownloadItem item : mItems) {
            if (item.getId().equals(id)) {
                if (item.isActive() && item.getState() != DownloadItem.PAUSED) {
                    item.setPaused(true);
                    item.setState(DownloadItem.PAUSED);
                    item.setSpeed(0);
                }
            }
        }
        notifyChanged();
    }

    public void resume(String id) {
        DownloadItem target = null;
        for (DownloadItem item : mItems) {
            if (item.getId().equals(id)) {
                target = item;
                break;
            }
        }
        if (target == null) return;
        int state = target.getState();
        if (state != DownloadItem.PAUSED && state != DownloadItem.ERROR) return;
        final DownloadItem finalTarget = target;
        finalTarget.setPaused(false);
        if (state == DownloadItem.ERROR) {
            finalTarget.setError(null);
            finalTarget.setProgress(0);
        }
        mExecutor.execute(() -> {
            // 等待旧任务结束，避免与正在退出的下载并发操作同一目录
            Future<?> old = mFutures.remove(id);
            if (old != null) {
                // m3u8 任务不可中断（否则会被误判为失败），仅等待其自然退出；单文件可直接中断
                if (!isM3u8Item(id)) old.cancel(true);
                try {
                    old.get();
                } catch (Exception ignored) {
                }
            }
            OkHttp.cancel(id);
            mDownloads.remove(id);
            resumeDownload(finalTarget, new File(finalTarget.getFilePath()));
        });
    }

    private void resumeDownload(DownloadItem item, File file) {
        if (item.isCanceled()) {
            item.setState(DownloadItem.CANCELED);
            notifyChanged();
            return;
        }
        item.setState(DownloadItem.DOWNLOADING);
        notifyChanged();
        if (isM3u8(item.getUrl())) {
            downloadM3u8(item, file);
        } else {
            downloadSingle(item, file);
        }
    }

    public void cancel(String id) {
        Download download = mDownloads.remove(id);
        if (download != null) download.cancel();
        boolean m3u8 = isM3u8Item(id);
        // 先标记状态，再取消任务，避免后台线程读到旧状态而误判
        for (DownloadItem item : mItems) {
            if (item.getId().equals(id)) {
                item.setCanceled(true);
                item.setPaused(false);
                if (item.isActive()) item.setState(DownloadItem.CANCELED);
            }
        }
        if (m3u8) {
            // m3u8：同样不要中断 future，让后台任务在 CanceledException 时自行 deleteDir 清理分片目录；
            // 若任务已结束（暂停/已完成等，future 已不存在），此处直接清理本地分片目录，
            // 注意只清理该任务的 _hls 目录，不能误删整个下载根目录。
            if (!mFutures.containsKey(id)) {
                DownloadItem item = getItem(id);
                if (item != null && !TextUtils.isEmpty(item.getFilePath())) {
                    Path.clear(hlsDirOf(item));
                }
            }
        } else {
            Future<?> future = mFutures.remove(id);
            if (future != null) future.cancel(true);
        }
        OkHttp.cancel(id);
        notifyChanged();
    }

    public void remove(String id) {
        cancel(id);
        mItems.removeIf(item -> item.getId().equals(id));
        notifyChanged();
    }

    public void removeGroup(String key) {
        List<DownloadItem> toRemove = new ArrayList<>();
        for (DownloadItem item : mItems) {
            String k = TextUtils.isEmpty(item.getGroup()) ? item.getName() : item.getGroup();
            if (k.equals(key)) toRemove.add(item);
        }
        for (DownloadItem item : toRemove) remove(item.getId());
    }

    public void clearFinished() {
        mItems.removeIf(item -> !item.isActive());
        notifyChanged();
    }

    public int getActiveCount() {
        int count = 0;
        for (DownloadItem item : mItems) if (item.isActive()) count++;
        return count;
    }

    private File buildFile(String name, String url) {
        File dir = new File(App.get().getExternalFilesDir(Environment.DIRECTORY_MOVIES), "WebHTV");
        if (!dir.exists() && !dir.mkdirs()) return null;
        if (!dir.isDirectory()) return null;
        String ext = isM3u8(url) ? ".mp4" : getExtension(url);
        String base = sanitize(name);
        File file = new File(dir, base + ext);
        int i = 1;
        while (file.exists()) file = new File(dir, base + "_" + (i++) + ext);
        return file;
    }

    private boolean isM3u8Item(String id) {
        DownloadItem item = getItem(id);
        return item != null && isM3u8(item.getUrl());
    }

    private DownloadItem getItem(String id) {
        for (DownloadItem item : mItems) {
            if (item.getId().equals(id)) return item;
        }
        return null;
    }

    // 还原 m3u8 的本地分片目录：与 buildFile 生成的占位文件（name.mp4）同目录、同名 + "_hls"。
    // 若 filePath 已是 index.m3u8（下载完成后），其父目录即为 _hls 目录。
    private File hlsDirOf(DownloadItem item) {
        File f = new File(item.getFilePath());
        File parent = f.getParentFile();
        if (parent != null && parent.getName().endsWith("_hls")) return parent;
        String base = f.getName().replaceAll("\\.(mp4|m3u8|m3u|ts)$", "");
        return new File(parent, base + "_hls");
    }

    private boolean isM3u8(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.US);
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u") || lower.contains("m3u8");
    }

    private String getExtension(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (!TextUtils.isEmpty(path)) {
                int dot = path.lastIndexOf('.');
                if (dot > 0 && dot < path.length() - 1) {
                    String ext = path.substring(dot);
                    if (ext.length() <= 5 && ext.matches(".[a-zA-Z0-9]+")) return ext;
                }
            }
        } catch (Exception ignored) {
        }
        return ".mp4";
    }

    private String sanitize(String name) {
        if (TextUtils.isEmpty(name)) name = "video";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    public interface Callback {
        void onChanged();
    }
}
