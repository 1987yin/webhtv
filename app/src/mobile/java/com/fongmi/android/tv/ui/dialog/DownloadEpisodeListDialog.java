package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.DownloadGroup;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.databinding.ActivityDownloadEpisodesBinding;
import com.fongmi.android.tv.ui.adapter.DownloadEpisodeAdapter;
import com.fongmi.android.tv.utils.DownloadManager;
import com.fongmi.android.tv.utils.FileUtil;

import java.io.File;

public class DownloadEpisodeListDialog extends BaseBottomSheetDialog {

    private ActivityDownloadEpisodesBinding mBinding;
    private DownloadEpisodeAdapter mAdapter;
    private String mKey;

    public static void show(FragmentActivity activity, DownloadGroup group) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getSupportFragmentManager().isStateSaved()) return;
        DownloadEpisodeListDialog dialog = new DownloadEpisodeListDialog();
        dialog.mKey = group.getKey();
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = ActivityDownloadEpisodesBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mAdapter = new DownloadEpisodeAdapter(this::onAction);
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mBinding.recycler.setAdapter(mAdapter);
        refresh();
    }

    @Override
    protected void initEvent() {
        DownloadManager.get().register(mRefresh);
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
    public void onDismiss(@NonNull DialogInterface dialog) {
        DownloadManager.get().unregister(mRefresh);
        super.onDismiss(dialog);
    }
}
