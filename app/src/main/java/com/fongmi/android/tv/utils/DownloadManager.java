package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
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

public class DownloadManager {

    private static DownloadManager sInstance;

    private final List<DownloadItem> mItems;
    private final List<Callback> mCallbacks;
    private final ExecutorService mExecutor;
    private final Map<String, Download> mDownloads;

    public static synchronized DownloadManager get() {
        if (sInstance == null) sInstance = new DownloadManager();
        return sInstance;
    }

    private DownloadManager() {
        mItems = new ArrayList<>();
        mCallbacks = new ArrayList<>();
        mDownloads = new ConcurrentHashMap<>();
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
        App.post(() -> {
            for (Callback callback : new ArrayList<>(mCallbacks)) callback.onChanged();
        });
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
        try {
            File mp4 = M3u8Downloader.download(item, item.getHeaders(), file, (percent, bytes, total, speed) -> {
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
            item.setState(DownloadItem.SUCCESS);
            item.setProgress(100);
            item.setSpeed(0);
            notifyChanged();
        } catch (Throwable e) {
            if (item.isCanceled()) {
                item.setState(DownloadItem.CANCELED);
            } else {
                item.setState(DownloadItem.ERROR);
                item.setError(e.getMessage());
            }
            notifyChanged();
        }
    }

    private void downloadSingle(DownloadItem item, File file) {
        Download download = Download.create(item.getUrl(), file).headers(item.getHeaders()).tag(item.getId());
        mDownloads.put(item.getId(), download);
        download.start(new Download.Callback() {
            @Override
            public void progress(int progress) {
                item.setProgress(progress);
                notifyChanged();
            }

            @Override
            public void progress(int progress, long bytes, long total, long speed, long elapsed) {
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

    public void cancel(String id) {
        Download download = mDownloads.remove(id);
        if (download != null) download.cancel();
        OkHttp.cancel(id);
        for (DownloadItem item : mItems) {
            if (item.getId().equals(id)) {
                item.setCanceled(true);
                if (item.isActive()) item.setState(DownloadItem.CANCELED);
            }
        }
        notifyChanged();
    }

    public void remove(String id) {
        cancel(id);
        mItems.removeIf(item -> item.getId().equals(id));
        notifyChanged();
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
