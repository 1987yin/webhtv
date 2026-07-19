package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadGroup;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.databinding.ActivityDownloadEpisodesBinding;
import com.fongmi.android.tv.ui.adapter.DownloadEpisodeGridAdapter;
import com.fongmi.android.tv.utils.DownloadManager;
import com.fongmi.android.tv.utils.FileUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

public class DownloadEpisodeListDialog extends BaseBottomSheetDialog {

    private static final int SPAN_COUNT = 4;

    private ActivityDownloadEpisodesBinding mBinding;
    private DownloadEpisodeGridAdapter mAdapter;
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
        mAdapter = new DownloadEpisodeGridAdapter(this::onAction);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), SPAN_COUNT));
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
        if (action == DownloadEpisodeGridAdapter.ACTION_PLAY) {
            if (item.getState() == DownloadItem.SUCCESS && !TextUtils.isEmpty(item.getFilePath())) {
                FileUtil.openFile(new File(item.getFilePath()));
            }
        } else if (action == DownloadEpisodeGridAdapter.ACTION_CANCEL) {
            DownloadManager.get().cancel(item.getId());
        } else if (action == DownloadEpisodeGridAdapter.ACTION_DELETE) {
            new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle(R.string.download_remove)
                    .setMessage(R.string.download_delete_episode)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.dialog_positive, (d, w) -> DownloadManager.get().remove(item.getId()))
                    .show();
        }
        refresh();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        DownloadManager.get().unregister(mRefresh);
        super.onDismiss(dialog);
    }
}
