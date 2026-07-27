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
import android.os.ServiceInfo;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.receiver.DownloadReceiver;
import com.fongmi.android.tv.utils.Notify;

// 下载前台服务：在存在活动下载任务时保活进程，避免应用切后台后被系统回收导致下载中断。
// 前台服务自带常驻通知（与 DownloadNotify 的逐任务进度通知相互独立），并持有
// 部分唤醒锁与 Wifi 锁，确保后台下载时 CPU 与网络不被 Doze/省电策略限流。
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

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        acquireLocks();
        startForegroundCompat(notification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 若服务被系统回收，重启后由 DownloadManager 再次 sync 决定是否继续；
        // 此处直接返回 STICKY 以保持前台保活语义。
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

    private Notification notification() {
        return new NotificationCompat.Builder(this, Notify.DEFAULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.download))
                .setContentText(getString(R.string.download_active))
                .setContentIntent(openIntent())
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private PendingIntent openIntent() {
        // 通知点击直接启动下载列表（隐式 Intent），系统对通知触发的 Activity 跳转始终放行。
        Intent intent = new Intent(DownloadReceiver.ACTION_OPEN)
                .setPackage(getPackageName())
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
