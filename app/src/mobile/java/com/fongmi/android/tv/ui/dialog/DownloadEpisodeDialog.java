package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.DialogDownloadEpisodeBinding;
import com.fongmi.android.tv.ui.activity.DownloadListActivity;
import com.fongmi.android.tv.ui.adapter.DownloadEpisodeAdapter;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.List;

public class DownloadEpisodeDialog extends AppCompatDialogFragment implements DownloadEpisodeAdapter.OnClickListener {

    private DialogDownloadEpisodeBinding binding;
    private DownloadEpisodeAdapter adapter;
    private List<Episode> episodes;
    private OnDownload listener;

    public interface OnDownload {
        void onDownload(Episode episode);
    }

    public static void show(FragmentActivity activity, List<Episode> episodes, OnDownload listener) {
        if (episodes == null || episodes.isEmpty()) return;
        DownloadEpisodeDialog dialog = new DownloadEpisodeDialog();
        dialog.episodes = episodes;
        dialog.listener = listener;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        configureWindow(dialog);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow(getDialog());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogDownloadEpisodeBinding.inflate(inflater, container, false);
        FrameLayout overlay = new FrameLayout(requireContext());
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setOnClickListener(v -> dismiss());
        binding.getRoot().setClickable(true);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, getHeight(), Gravity.BOTTOM);
        overlay.addView(binding.getRoot(), params);
        return overlay;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setRecyclerView();
    }

    private int getHeight() {
        int screen = ResUtil.getScreenHeight(requireContext());
        return Math.max(ResUtil.dp2px(240), Math.round(screen * 0.33f));
    }

    private void configureWindow(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setDimAmount(0f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        Util.hideSystemUI(window);
    }

    private void setRecyclerView() {
        binding.episode.setHasFixedSize(true);
        binding.episode.setItemAnimator(null);
        binding.episode.setAdapter(adapter = new DownloadEpisodeAdapter(this));
        adapter.addAll(episodes);
        binding.downloadList.setOnClickListener(v -> {
            DownloadListActivity.start(requireActivity());
            dismiss();
        });
        binding.selectAll.setOnClickListener(v -> {
            boolean all = !adapter.isAllSelected();
            adapter.selectAll(all);
            updateSelectAll();
        });
        binding.download.setOnClickListener(v -> {
            List<Episode> selected = adapter.getSelected();
            for (Episode episode : selected) listener.onDownload(episode);
            if (!selected.isEmpty()) DownloadListActivity.start(requireActivity());
            dismiss();
        });
        updateSelectAll();
    }

    private void updateSelectAll() {
        binding.selectAll.setText(adapter.isAllSelected() ? R.string.download_select_none : R.string.download_select_all);
    }

    @Override
    public void onItemClick(int position) {
        adapter.toggle(position);
        updateSelectAll();
    }
}
