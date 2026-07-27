package com.fongmi.android.tv.utils;

import android.Manifest;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.text.format.Formatter;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.receiver.DownloadReceiver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// 下载进度通知：每个活动/终态任务一张独立卡片，复用 Notify 的 DEFAULT 通道（IMPORTANCE_LOW，不打扰）。
public class DownloadNotify {

    // 与 Notify.ID(9527) 区分的独立通知 id 区间
    private static final int BASE_ID = 6000;
    private static final int MAX_STALE = 512;
    private static final Map<String, Integer> IDS = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger(BASE_ID);

    private static int idOf(String key) {
        Integer id = IDS.get(key);
        if (id == null) {
            id = SEQ.incrementAndGet();
            IDS.put(key, id);
        }
        return id;
    }

    private static boolean granted() {
        return ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static NotificationManagerCompat manager() {
        return NotificationManagerCompat.from(App.get());
    }

    // 线程安全地刷新某个任务的通知（主线程构造并显示/移除）。
    public static void update(DownloadItem item) {
        App.post(() -> show(item));
    }

    public static void cancel(String id) {
        App.post(() -> doCancel(id));
    }

    public static void cancelAll() {
        App.post(() -> {
            for (String id : IDS.keySet().toArray(new String[0])) doCancel(id);
        });
    }

    // 清理上次运行遗留的下载通知：进程重启后 IDS 映射已重置，但系统通知栏可能仍残留旧卡片。
    // 启动时按 id 区间统一 cancel，并清空映射，避免旧 91% 等通知长期滞留。
    public static void clearStale() {
        NotificationManagerCompat nm = manager();
        for (int id = BASE_ID + 1; id <= BASE_ID + MAX_STALE; id++) nm.cancel(id);
        IDS.clear();
    }

    private static void doCancel(String id) {
        Integer nid = IDS.remove(id);
        if (nid != null) manager().cancel(nid);
    }

    private static void show(DownloadItem item) {
        if (!granted()) return;
        if (item.isCanceled() || item.getState() == DownloadItem.CANCELED) {
            doCancel(item.getId());
            return;
        }
        int nid = idOf(item.getId());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(App.get(), Notify.DEFAULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(item.getName())
                .setContentIntent(DownloadReceiver.openIntent(App.get()))
                .setOnlyAlertOnce(true);
        switch (item.getState()) {
            case DownloadItem.DOWNLOADING:
                builder.setContentText(ResUtil.getString(R.string.download_active) + " " + item.getProgress() + "%  ·  " + Formatter.formatFileSize(App.get(), item.getSpeed()) + "/s");
                builder.setProgress(100, item.getProgress(), false);
                builder.setOngoing(true);
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, ResUtil.getString(R.string.download_cancel), DownloadReceiver.cancelIntent(App.get(), item.getId()));
                break;
            case DownloadItem.QUEUED:
                builder.setContentText(ResUtil.getString(R.string.download_queued));
                builder.setProgress(0, 0, true);
                builder.setOngoing(true);
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, ResUtil.getString(R.string.download_cancel), DownloadReceiver.cancelIntent(App.get(), item.getId()));
                break;
            case DownloadItem.WAITING:
                builder.setContentText(ResUtil.getString(R.string.download_waiting));
                builder.setProgress(0, 0, true);
                builder.setOngoing(true);
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, ResUtil.getString(R.string.download_cancel), DownloadReceiver.cancelIntent(App.get(), item.getId()));
                break;
            case DownloadItem.PAUSED:
                builder.setContentText(ResUtil.getString(R.string.download_paused));
                builder.setProgress(0, 0, false);
                builder.setOngoing(true);
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, ResUtil.getString(R.string.download_cancel), DownloadReceiver.cancelIntent(App.get(), item.getId()));
                break;
            case DownloadItem.SUCCESS:
                builder.setContentText(ResUtil.getString(R.string.download_done));
                builder.setProgress(0, 0, false);
                builder.setAutoCancel(true);
                break;
            case DownloadItem.ERROR:
                String error = item.getError();
                builder.setContentText(ResUtil.getString(R.string.download_failed) + (TextUtils.isEmpty(error) ? "" : "：" + error));
                builder.setProgress(0, 0, false);
                builder.setAutoCancel(true);
                break;
        }
        manager().notify(nid, builder.build());
    }
}
