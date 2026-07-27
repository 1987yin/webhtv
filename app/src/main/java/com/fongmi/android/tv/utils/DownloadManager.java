package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.service.DownloadService;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.DownloadGroup;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.bean.Result;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class DownloadManager {

    // 同时进行的下载任务数上限（含拉取真实地址阶段），默认 3。
    public static final int DEFAULT_MAX_CONCURRENT = 3;

    private static DownloadManager sInstance;

    private final List<DownloadItem> mItems;
    private final List<Callback> mCallbacks;
    private final ExecutorService mExecutor;
    private final Map<String, Download> mDownloads;
    private final Map<String, Future<?>> mFutures;
    private final AtomicBoolean mNotifyPosted = new AtomicBoolean(false);
    private final AtomicBoolean mNotifyPostedNotify = new AtomicBoolean(false);
    private final int mMaxConcurrent;
    private final AtomicInteger mRunning = new AtomicInteger(0);
    // 等待名额的排队任务（item + 真正要执行的任务体）。
    private final Deque<Slot> mPending = new ArrayDeque<>();

    private static final class Slot {
        final DownloadItem item;
        final Consumer<Runnable> task;

        Slot(DownloadItem item, Consumer<Runnable> task) {
            this.item = item;
            this.task = task;
        }
    }

    public static synchronized DownloadManager get() {
        if (sInstance == null) sInstance = new DownloadManager();
        return sInstance;
    }

    private DownloadManager() {
        mItems = new ArrayList<>();
        mCallbacks = new ArrayList<>();
        mDownloads = new ConcurrentHashMap<>();
        mFutures = new ConcurrentHashMap<>();
        mMaxConcurrent = DEFAULT_MAX_CONCURRENT;
        // 线程池至少能容纳上限数量的同时任务（fetch 阶段占用，真正的下载走 Task 线程池）。
        mExecutor = Executors.newFixedThreadPool(Math.max(1, mMaxConcurrent));
        // 清理上次运行遗留的下载通知，避免旧会话的 91% 等卡片残留
        DownloadNotify.clearStale();
    }

    public int getMaxConcurrent() {
        return mMaxConcurrent;
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
        // UI 刷新合并节流，避免主线程被高频进度刷新占满导致界面卡顿/无法点击
        if (mNotifyPosted.compareAndSet(false, true)) {
            App.post(() -> {
                mNotifyPosted.set(false);
                for (Callback callback : new ArrayList<>(mCallbacks)) callback.onChanged();
            });
        }
        // 通知刷新独立合并：状态/进度变化（含终态）一定反映到通知栏，不受 UI 合并窗口影响；
        // 与下方终态分支的 DownloadNotify.update(item) 共同确保完成/失败/取消后通知不残留旧进度。
        if (mNotifyPostedNotify.compareAndSet(false, true)) {
            App.post(() -> {
                mNotifyPostedNotify.set(false);
                for (DownloadItem item : mItems) DownloadNotify.update(item);
            });
        }
        // 有活动下载则启动前台服务保活进程，避免应用挂后台被系统回收导致下载中断；
        // 无活动下载则停止前台服务。sync 内部通过 running 标志幂等，可高频调用。
        DownloadService.sync(App.get(), getActiveCount());
    }

    public void enqueue(DownloadItem item, String siteKey, String flag, String episodeUrl) {
        item.setSiteKey(siteKey);
        item.setFlag(flag);
        item.setEpisodeUrl(episodeUrl);
        mItems.add(0, item);
        notifyChanged();
        // 占一个并发名额后拉取真实地址并开始下载；名额不足则进入排队队列。
        acquire(item, onDone -> runFetch(item, onDone));
    }

    // 占用并发名额执行任务：若名额空闲立即执行，否则将任务放入等待队列并以 QUEUED 状态排队。
    private synchronized void acquire(DownloadItem item, Consumer<Runnable> task) {
            if (item.isCanceled()) {
                item.setState(DownloadItem.CANCELED);
                notifyChanged();
                DownloadNotify.update(item);
                return;
            }
        if (mRunning.get() < mMaxConcurrent) {
            runWithSlot(item, task);
        } else {
            item.setState(DownloadItem.QUEUED);
            notifyChanged();
            mPending.add(new Slot(item, task));
        }
    }

    // 占用一个并发名额并在后台线程执行任务体。名额不在任务体结束时释放，而是交由后台下载真正
    // 完成时通过 onDone 一次性释放，从而精确限制"同时进行的下载"数量（含 m3u8 分片下载）。
    private void runWithSlot(DownloadItem item, Consumer<Runnable> task) {
        mRunning.incrementAndGet();
        item.setState(DownloadItem.WAITING);
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable onDone = () -> {
            if (released.compareAndSet(false, true)) release();
        };
        mExecutor.execute(() -> task.accept(onDone));
    }

    // 释放一个并发名额，并从等待队列中取出下一个未取消的任务执行。
    private synchronized void release() {
        if (mRunning.decrementAndGet() < 0) mRunning.set(0);
        Slot next;
        while ((next = mPending.poll()) != null) {
            if (next.item.isCanceled()) {
                next.item.setState(DownloadItem.CANCELED);
                notifyChanged();
                continue;
            }
            runWithSlot(next.item, next.task);
            return;
        }
    }

    // 拉取真实地址并启动下载；若成功启动后台下载则返回 true（名额由后台完成时释放），
    // 否则返回 false（此处立即释放名额）。已取消的任务直接终止。
    private void runFetch(DownloadItem item, Runnable onDone) {
        boolean started;
        try {
            started = fetchAndStart(item, onDone);
        } catch (Throwable e) {
            if (item.isCanceled()) {
                item.setState(DownloadItem.CANCELED);
            } else {
                item.setState(DownloadItem.ERROR);
                item.setError(e.getMessage());
            }
            notifyChanged();
            started = false;
        }
        if (!started) onDone.run();
    }

    // 向站点接口拉取最新播放地址与请求头（签名可能已过期，需刷新），随后开始下载。
    // 返回是否成功启动了后台下载（true 时名额随下载完成释放；false 时由调用方释放）。
    private boolean fetchAndStart(DownloadItem item, Runnable onDone) throws Exception {
        if (item.isCanceled()) {
            item.setState(DownloadItem.CANCELED);
            notifyChanged();
            return false;
        }
        Result result = SiteApi.playerContent(item.getSiteKey(), item.getFlag(), item.getEpisodeUrl());
        if (item.isCanceled()) {
            item.setState(DownloadItem.CANCELED);
            notifyChanged();
            return false;
        }
        item.setUrl(result.getRealUrl());
        item.setHeaders(result.getHeader());
        return startDownload(item, onDone);
    }

    private boolean startDownload(DownloadItem item, Runnable onDone) {
        if (item.isCanceled()) {
            item.setState(DownloadItem.CANCELED);
            notifyChanged();
            return false;
        }
        if (TextUtils.isEmpty(item.getUrl())) {
            item.setState(DownloadItem.ERROR);
            item.setError("empty url");
            notifyChanged();
            return false;
        }
        item.setState(DownloadItem.DOWNLOADING);
        notifyChanged();
        File file = buildFile(item.getName(), item.getUrl());
        if (file == null) {
            item.setState(DownloadItem.ERROR);
            item.setError("create file failed");
            notifyChanged();
            return false;
        }
        item.setFilePath(file.getAbsolutePath());
        if (isM3u8(item.getUrl())) {
            downloadM3u8(item, file, onDone);
        } else {
            downloadSingle(item, file, onDone);
        }
        return true;
    }

    private void downloadM3u8(DownloadItem item, File file, Runnable onDone) {
        Future<?> future = Task.submit(() -> runM3u8(item, file, onDone));
        mFutures.put(item.getId(), future);
    }

    private void runM3u8(DownloadItem item, File file, Runnable onDone) {
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
                // mp4 现在指向 name_hls/index.m3u8，其父目录才是分片目录；整目录清理，避免残留分片孤儿文件
                File hlsDir = mp4.getParentFile();
                if (hlsDir != null) Path.clear(hlsDir);
                notifyChanged();
                return;
            }
            // 走到这里说明 M3u8Downloader.download 已成功返回（分片全部就绪且播放列表已写出），
            // 即下载"真正完成"。即便期间用户点了暂停，也以完成态为准，避免出现
            // "显示已暂停、但分片其实已下完、点恢复又瞬间完成"的假象。
            item.setFilePath(mp4.getAbsolutePath());
            if (!item.isCanceled()) {
                item.setPaused(false);
                item.setState(DownloadItem.SUCCESS);
                item.setProgress(100);
                item.setSpeed(0);
                notifyChanged();
                DownloadNotify.update(item);
            }
        } catch (M3u8Downloader.PausedException e) {
            if (item.isPaused()) {
                item.setState(DownloadItem.PAUSED);
                item.setSpeed(0);
            }
            notifyChanged();
            DownloadNotify.update(item);
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
            DownloadNotify.update(item);
        } finally {
            mFutures.remove(item.getId());
            // 下载线程退出（成功/失败/暂停/取消任意路径），释放并发名额。
            onDone.run();
        }
    }

    private void downloadSingle(DownloadItem item, File file, Runnable onDone) {
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
                DownloadNotify.update(item);
                onDone.run();
            }

            @Override
            public void success(File f) {
                item.setState(DownloadItem.SUCCESS);
                item.setProgress(100);
                mDownloads.remove(item.getId());
                notifyChanged();
                DownloadNotify.update(item);
                onDone.run();
            }

            @Override
            public void finish() {
                // 单文件下载线程退出的一次性通知，与 success/error 共用同一个 onDone（内部去重）。
                onDone.run();
            }
        });
    }

    public void pause(String id) {
        // 必须先置暂停标记，再取消在途请求：否则取消触发的 IOException 会被 M3u8Downloader 误判为
        // "下载失败"，进而走无效刷新重试，且分片线程无法干净退出（表现为暂停不灵、恢复后秒完成）。
        for (DownloadItem item : mItems) {
            if (item.getId().equals(id)) {
                if (item.getState() == DownloadItem.QUEUED) {
                    // 仍在排队：移出等待队列并标记为已暂停，恢复时再重新调度。
                    synchronized (this) {
                        mPending.removeIf(slot -> slot.item.getId().equals(id));
                    }
                    item.setPaused(true);
                    item.setState(DownloadItem.PAUSED);
                    item.setSpeed(0);
                    break;
                }
                if (item.isActive() && item.getState() != DownloadItem.PAUSED) {
                    item.setPaused(true);
                    item.setState(DownloadItem.PAUSED);
                    item.setSpeed(0);
                }
            }
        }
        Download download = mDownloads.get(id);
        if (download != null) download.pause();
        if (!isM3u8Item(id)) {
            Future<?> future = mFutures.get(id);
            if (future != null) future.cancel(true);
        }
        // m3u8 通过取消 tag 中断在途 HTTP 请求（绝不中断 future，否则 latch.await 抛
        // InterruptedException 被误判为失败），分片线程检测到暂停标记后自然退出并保留断点。
        OkHttp.cancel(id);
        M3u8Downloader.cancelTag(id);
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
        // 恢复同样受并发上限约束：占名额执行，名额不足则进入排队（状态 QUEUED）。
        acquire(finalTarget, onDone -> runResume(finalTarget, state, onDone));
    }

    private void runResume(DownloadItem item, int prevState, Runnable onDone) {
        boolean started;
        try {
            started = doResume(item, prevState, onDone);
        } catch (Throwable e) {
            if (item.isCanceled()) {
                item.setState(DownloadItem.CANCELED);
            } else {
                item.setState(DownloadItem.ERROR);
                item.setError(e.getMessage());
            }
            notifyChanged();
            started = false;
        }
        if (!started) onDone.run();
    }

    private boolean doResume(DownloadItem item, int prevState, Runnable onDone) {
        // 等待旧任务结束，避免与正在退出的下载并发操作同一目录
        Future<?> old = mFutures.remove(item.getId());
        if (old != null) {
            // m3u8 任务不可中断（否则会被误判为失败），仅等待其自然退出；单文件可直接中断
            if (!isM3u8Item(item.getId())) old.cancel(true);
            try {
                old.get();
            } catch (Exception ignored) {
            }
        }
        OkHttp.cancel(item.getId());
        M3u8Downloader.cancelTag(item.getId());
        mDownloads.remove(item.getId());
        // 错误重试：m3u8 预签名地址往往短则数十秒、长则几分钟就过期，
        // 用旧 URL 直接续传会 403（表现为进度 0% 后失败）。这里重新向接口要一次最新签名。
        if (prevState == DownloadItem.ERROR && !TextUtils.isEmpty(item.getSiteKey())) {
            try {
                return fetchAndStart(item, onDone);
            } catch (Throwable e) {
                item.setState(DownloadItem.ERROR);
                item.setError(e.getMessage());
                notifyChanged();
                return false;
            }
        }
        return resumeDownload(item, new File(item.getFilePath()), onDone);
    }

    private boolean resumeDownload(DownloadItem item, File file, Runnable onDone) {
        if (item.isCanceled()) {
            item.setState(DownloadItem.CANCELED);
            notifyChanged();
            return false;
        }
        item.setState(DownloadItem.DOWNLOADING);
        notifyChanged();
        if (isM3u8(item.getUrl())) {
            downloadM3u8(item, file, onDone);
        } else {
            downloadSingle(item, file, onDone);
        }
        return true;
    }

    public void cancel(String id) {
        // 若任务仍在等待队列（QUEUED）中，先从队列移除，避免其被调度后泄漏名额。
        synchronized (this) {
            mPending.removeIf(slot -> slot.item.getId().equals(id));
        }
        Download download = mDownloads.remove(id);
        if (download != null) download.cancel();
        boolean m3u8 = isM3u8Item(id);
        // 先标记状态，再取消任务，避免后台线程读到旧状态而误判
        for (DownloadItem item : mItems) {
            if (item.getId().equals(id)) {
                item.setCanceled(true);
                item.setPaused(false);
                if (item.isActive()) item.setState(DownloadItem.CANCELED);
                DownloadNotify.update(item);
            }
        }
        if (m3u8) {
            // 取消下载客户端上该 tag 的链接（与 OkHttp.cancel 互补，避免分片请求继续占用带宽）
            M3u8Downloader.cancelTag(id);
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
        DownloadItem item = getItem(id);
        cancel(id);
        if (item != null) deleteFiles(item);
        mItems.removeIf(it -> it.getId().equals(id));
        DownloadNotify.cancel(id);
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
        List<DownloadItem> finished = new ArrayList<>();
        for (DownloadItem item : mItems) if (!item.isActive()) finished.add(item);
        mItems.removeIf(item -> !item.isActive());
        for (DownloadItem item : finished) {
            deleteFiles(item);
            DownloadNotify.cancel(item.getId());
        }
        notifyChanged();
    }

    public void cancelAll() {
        List<String> ids = new ArrayList<>();
        for (DownloadItem item : mItems) if (item.isActive()) ids.add(item.getId());
        for (String id : ids) cancel(id);
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

    // 删除某任务对应的本地文件：单文件下载删 mp4；m3u8 下载删整个 _hls 分片目录。
    // 供 remove / removeGroup / clearFinished 调用，确保从列表删除时磁盘文件一并清理。
    private void deleteFiles(DownloadItem item) {
        if (item == null || TextUtils.isEmpty(item.getFilePath())) return;
        File file = new File(item.getFilePath());
        if (isM3u8(item.getUrl())) {
            // 下载完成产物为 name_hls/ 目录（index.m3u8 + seg_i.ts），占位 mp4 已清理
            Path.clear(hlsDirOf(item));
        } else {
            // 单文件下载：直接删除产物文件；若父目录是 _hls（理论上不会）则整体清理
            Path.clear(file);
        }
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
