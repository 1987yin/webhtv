package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.ui.activity.DownloadListActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class DownloadEpisodeDialog {

    public interface OnDownload {
        void onDownload(Episode episode);
    }

    public static void show(Activity activity, List<Episode> episodes, OnDownload listener) {
        if (episodes == null || episodes.isEmpty()) return;
        int size = episodes.size();
        String[] names = new String[size];
        boolean[] checked = new boolean[size];
        for (int i = 0; i < size; i++) names[i] = episodes.get(i).getName();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setTitle(R.string.download_select_episode);
        builder.setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked);
        builder.setPositiveButton(R.string.download, (dialog, which) -> {
            List<Episode> selected = new ArrayList<>();
            for (int i = 0; i < size; i++) if (checked[i]) selected.add(episodes.get(i));
            for (Episode episode : selected) listener.onDownload(episode);
            if (!selected.isEmpty()) DownloadListActivity.start(activity);
        });
        builder.setNeutralButton(R.string.download_list, (dialog, which) -> DownloadListActivity.start(activity));
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }
}
