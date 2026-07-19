package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.fongmi.android.tv.bean.DownloadGroup;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.databinding.ActivityDownloadEpisodesBinding;
import com.fongmi.android.tv.ui.adapter.DownloadEpisodeAdapter;
import com.fongmi.android.tv.utils.DownloadManager;
import com.fongmi.android.tv.utils.FileUtil;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;

public class DownloadEpisodeListDialog extends BottomSheetDialog {

    private final Activity mActivity;
    private final String mKey;
    private ActivityDownloadEpisodesBinding mBinding;
    private DownloadEpisodeAdapter mAdapter;

    public static void show(Activity activity, DownloadGroup group) {
        new DownloadEpisodeListDialog(activity, group.getKey()).show();
    }

    private DownloadEpisodeListDialog(Activity activity, String key) {
        super(activity);
        this.mActivity = activity;
        this.mKey = key;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityDownloadEpisodesBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        mAdapter = new DownloadEpisodeAdapter(this::onAction);
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        mBinding.recycler.setAdapter(mAdapter);
        DownloadManager.get().register(mRefresh);
        refresh();
    }

    private final DownloadManager.Callback mRefresh = this::refresh;

    private void refresh() {
        DownloadGroup group = DownloadManager.get().getGroup(mKey);
        if (group == null) {
            dismiss();
            return;
        }
        mBinding.title.setText(group.getName());
        mAdapter.setItems(group.getItems());
    }

    private void onAction(DownloadItem item, int action) {
        if (action == DownloadEpisodeAdapter.ACTION_PLAY) {
            if (item.getState() == DownloadItem.SUCCESS && !TextUtils.isEmpty(item.getFilePath())) {
                FileUtil.openFile(new File(item.getFilePath()));
            }
        } else if (action == DownloadEpisodeAdapter.ACTION_CANCEL) {
            DownloadManager.get().cancel(item.getId());
        } else if (action == DownloadEpisodeAdapter.ACTION_REMOVE) {
            DownloadManager.get().remove(item.getId());
        }
        refresh();
    }

    @Override
    public void dismiss() {
        DownloadManager.get().unregister(mRefresh);
        super.dismiss();
    }
}
