package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.databinding.AdapterDownloadBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.ViewHolder> {

    private final List<DownloadItem> mItems;
    private final OnClickListener mListener;

    public DownloadAdapter(OnClickListener listener) {
        this.mItems = new ArrayList<>();
        this.mListener = listener;
    }

    public void setItems(List<DownloadItem> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public interface OnClickListener {
        void onCancel(DownloadItem item);

        void onRemove(DownloadItem item);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDownloadBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadItem item = mItems.get(position);
        holder.binding.name.setText(item.getName());
        boolean active = item.isActive();
        holder.binding.progress.setIndeterminate(!active && item.getState() == DownloadItem.WAITING);
        holder.binding.progress.setProgress(item.getProgress());
        holder.binding.status.setText(getStatus(item));
        holder.binding.cancel.setVisibility(active ? android.view.View.VISIBLE : android.view.View.GONE);
        holder.binding.cancel.setOnClickListener(v -> mListener.onCancel(item));
        holder.binding.remove.setOnClickListener(v -> mListener.onRemove(item));
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
        public final AdapterDownloadBinding binding;

        public ViewHolder(AdapterDownloadBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
