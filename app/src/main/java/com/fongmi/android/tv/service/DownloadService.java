package com.fongmi.android.tv.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.content.pm.ServiceInfo;
import android.text.format.Formatter;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.receiver.DownloadReceiver;
import com.fongmi.android.tv.utils.DownloadManager;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

// 下载前台服务：在存在活动下载任务时保活进程，避免应用切后台后被系统回收导致下载中断。
// 同时维护“唯一一张”下载通知卡片：统一展示所有任务的进度/速度，并提供打开列表与取消全部操作，
// 不再为每个任务单独建卡片。卡片随前台服务存在，所有任务完成后服务停止、卡片消失。
public class DownloadService extends Service {

    public static final int ID = Notify.ID + 3;
    private static volatile boolean running;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // 根据活动下载数量同步服务状态：有任务则确保前台服务在运行，无任务则停止。
    public static void sync(Context context, int activeCount) {
        if (activeCount > 0) {
            if (!running) ContextCompat.startForegroundService(context, new Intent(context, DownloadService.class));
        } else if (running) {
            context.stopService(new Intent(context, DownloadService.class));
        }
    }

    // 状态/进度变化后刷新唯一卡片；服务未运行时直接返回（无卡片可刷）。
    public static void update() {
        if (!running) return;
        Context context = App.get();
        if (context == null) return;
        NotificationManagerCompat.from(context).notify(ID, buildNotification(context));
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        acquireLocks();
        startForegroundCompat(buildNotification(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        releaseLocks();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID, notification);
        }
    }

    // 根据 DownloadManager 当前状态构建统一卡片内容
    private static Notification buildNotification(Context context) {
        DownloadManager dm = DownloadManager.get();
        int active = dm.getActiveCount();
        java.util.List<DownloadItem> items = dm.getItems();

        DownloadItem current = null;
        for (DownloadItem it : items) {
            if (it.getState() == DownloadItem.DOWNLOADING) {
                current = it;
                break;
            }
        }

        int progress;
        boolean indeterminate;
        String detail;
        if (current != null) {
            progress = current.getProgress();
            indeterminate = false;
            detail = current.getName() + "  ·  " + progress + "%  ·  " + Formatter.formatFileSize(context, current.getSpeed()) + "/s";
        } else if (active > 0) {
            progress = 0;
            indeterminate = true;
            detail = ResUtil.getString(R.string.download_active);
        } else if (!items.isEmpty()) {
            progress = 100;
            indeterminate = false;
            detail = ResUtil.getString(R.string.download_done);
        } else {
            progress = 0;
            indeterminate = true;
            detail = ResUtil.getString(R.string.download_active);
        }

        String title = ResUtil.getString(R.string.download) + (active > 0 ? " (" + active + ")" : "");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, Notify.DEFAULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(openIntent(context))
                .setProgress(100, progress, indeterminate)
                .setOngoing(true)
                .setSilent(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, ResUtil.getString(R.string.download_cancel_all), DownloadReceiver.cancelAllIntent(context));
        return builder.build();
    }

    private static PendingIntent openIntent(Context context) {
        // 通知点击直接启动下载列表（隐式 Intent），系统对通知触发的 Activity 跳转始终放行。
        Intent intent = new Intent(DownloadReceiver.ACTION_OPEN)
                .setPackage(context.getPackageName())
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void acquireLocks() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":download");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {
        }
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, BuildConfig.APPLICATION_ID + ":download");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {
        }
        wakeLock = null;
        wifiLock = null;
    }
}
