package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.databinding.AdapterDownloadEpisodeBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DownloadEpisodeAdapter extends RecyclerView.Adapter<DownloadEpisodeAdapter.ViewHolder> {

    public static final int ACTION_PLAY = 0;
    public static final int ACTION_CANCEL = 1;
    public static final int ACTION_REMOVE = 2;

    private final List<DownloadItem> mItems;
    private final OnActionListener mListener;

    public DownloadEpisodeAdapter(OnActionListener listener) {
        this.mItems = new ArrayList<>();
        this.mListener = listener;
    }

    public void setItems(List<DownloadItem> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public interface OnActionListener {
        void onAction(DownloadItem item, int action);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDownloadEpisodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadItem item = mItems.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.status.setText(getStatus(item));
        int action;
        String label;
        if (item.getState() == DownloadItem.SUCCESS) {
            action = ACTION_PLAY;
            label = ResUtil.getString(R.string.download_play);
        } else if (item.isActive()) {
            action = ACTION_CANCEL;
            label = ResUtil.getString(R.string.download_cancel);
        } else {
            action = ACTION_REMOVE;
            label = ResUtil.getString(R.string.download_remove);
        }
        holder.binding.action.setText(label);
        holder.binding.action.setOnClickListener(v -> mListener.onAction(item, action));
        holder.binding.getRoot().setOnClickListener(v -> {
            if (item.getState() == DownloadItem.SUCCESS && !TextUtils.isEmpty(item.getFilePath())) {
                mListener.onAction(item, ACTION_PLAY);
            }
        });
    }

    private String getStatus(DownloadItem item) {
        switch (item.getState()) {
            case DownloadItem.WAITING:
                return ResUtil.getString(R.string.download_waiting);
            case DownloadItem.DOWNLOADING:
                String speed = item.getSpeed() > 0 ? formatSpeed(item.getSpeed()) : "";
                String size = item.getTotal() > 0 ? " / " + formatSize(item.getTotal()) : "";
                return item.getProgress() + "%  " + speed + size;
            case DownloadItem.SUCCESS:
                return ResUtil.getString(R.string.download_done);
            case DownloadItem.ERROR:
                return ResUtil.getString(R.string.download_failed) + (item.getError() != null ? "\n" + item.getError() : "");
            case DownloadItem.CANCELED:
                return ResUtil.getString(R.string.download_canceled);
            default:
                return "";
        }
    }

    private String formatSpeed(long speed) {
        if (speed < 1024) return speed + " B/s";
        if (speed < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB/s", speed / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB/s", speed / 1024.0 / 1024.0);
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", size / 1024.0 / 1024.0);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final AdapterDownloadEpisodeBinding binding;

        public ViewHolder(AdapterDownloadEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
