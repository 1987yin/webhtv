package com.fongmi.android.tv.receiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.fongmi.android.tv.utils.DownloadManager;

// 处理下载通知的“取消”动作（取消对应任务）。
// 注意：通知点击“打开下载列表”不再经此广播，而是直接在 DownloadNotify 中构造 Activity PendingIntent，
// 以规避 Android 10+ 后台启动 Activity 的限制。
public class DownloadReceiver extends BroadcastReceiver {

    public static final String ACTION_OPEN = "com.fongmi.android.tv.download.open";
    public static final String ACTION_CANCEL = "com.fongmi.android.tv.download.cancel";
    public static final String EXTRA_ID = "id";

    public static PendingIntent cancelIntent(Context context, String id) {
        Intent intent = new Intent(ACTION_CANCEL).setPackage(context.getPackageName()).putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) return;
        switch (intent.getAction()) {
            case ACTION_CANCEL:
                String id = intent.getStringExtra(EXTRA_ID);
                if (id != null) DownloadManager.get().cancel(id);
                break;
        }
    }
}
