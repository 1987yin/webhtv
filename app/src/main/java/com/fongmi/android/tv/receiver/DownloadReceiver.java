// 处理下载通知的动作：点击“取消全部”取消所有活动下载任务。
// 注意：通知点击“打开下载列表”由 DownloadService 直接构造 Activity PendingIntent 完成，
// 不再经此广播（规避 Android 10+ 后台启动 Activity 限制）。
package com.fongmi.android.tv.receiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.fongmi.android.tv.utils.DownloadManager;

public class DownloadReceiver extends BroadcastReceiver {

    public static final String ACTION_OPEN = "com.fongmi.android.tv.download.open";
    public static final String ACTION_CANCEL_ALL = "com.fongmi.android.tv.download.cancelAll";

    public static PendingIntent cancelAllIntent(Context context) {
        Intent intent = new Intent(ACTION_CANCEL_ALL).setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) return;
        if (ACTION_CANCEL_ALL.equals(intent.getAction())) {
            DownloadManager.get().cancelAll();
        }
    }
}
