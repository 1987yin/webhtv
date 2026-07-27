package com.fongmi.android.tv.receiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.fongmi.android.tv.utils.DownloadManager;

// 处理下载通知的点击（打开下载列表）与取消（取消对应任务）动作。
// 通过隐式 action 启动 DownloadListActivity，避免 main 模块硬引用 mobile 模块的类（leanback 变体无该 Activity）。
public class DownloadReceiver extends BroadcastReceiver {

    public static final String ACTION_OPEN = "com.fongmi.android.tv.download.open";
    public static final String ACTION_CANCEL = "com.fongmi.android.tv.download.cancel";
    public static final String EXTRA_ID = "id";

    public static PendingIntent openIntent(Context context) {
        Intent intent = new Intent(ACTION_OPEN).setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static PendingIntent cancelIntent(Context context, String id) {
        Intent intent = new Intent(ACTION_CANCEL).setPackage(context.getPackageName()).putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) return;
        switch (intent.getAction()) {
            case ACTION_OPEN:
                openDownloads(context);
                break;
            case ACTION_CANCEL:
                String id = intent.getStringExtra(EXTRA_ID);
                if (id != null) DownloadManager.get().cancel(id);
                break;
        }
    }

    private void openDownloads(Context context) {
        // 注意：DownloadListActivity 的 intent-filter 含 CATEGORY_DEFAULT。
        // startActivity() 隐式启动时会自动补加该 category，但 resolveActivity() 不会，
        // 因此必须显式 addCategory，否则 resolveActivity 返回 null 而永不启动。
        Intent intent = new Intent(ACTION_OPEN).setPackage(context.getPackageName())
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) != null) context.startActivity(intent);
    }
}
