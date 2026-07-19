package com.fongmi.android.tv.ui.adapter;

import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.databinding.AdapterDownloadEpisodeListBinding;

import java.util.ArrayList;
import java.util.List;

public class DownloadEpisodeListAdapter extends RecyclerView.Adapter<DownloadEpisodeListAdapter.ViewHolder> {

    public static final int ACTION_PLAY = 0;
    public static final int ACTION_CANCEL = 1;
    public static final int ACTION_DELETE = 2;

    private final List<DownloadItem> mItems;
    private final OnActionListener mListener;

    public DownloadEpisodeListAdapter(OnActionListener listener) {
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
        return new ViewHolder(AdapterDownloadEpisodeListBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadItem item = mItems.get(position);
        holder.item = item;
        holder.binding.name.setText(item.getName());
        int state = item.getState();
        if (state == DownloadItem.SUCCESS) {
            holder.binding.state.setText(R.string.download_done);
            holder.binding.progress.setProgress(100);
            holder.binding.speed.setText("");
        } else if (state == DownloadItem.DOWNLOADING) {
            holder.binding.state.setText(item.getProgress() + "%");
            holder.binding.progress.setProgress(item.getProgress());
            holder.binding.speed.setText(formatSpeed(holder, item.getSpeed()));
        } else if (state == DownloadItem.WAITING) {
            holder.binding.state.setText(R.string.download_waiting);
            holder.binding.progress.setProgress(0);
            holder.binding.speed.setText("");
        } else if (state == DownloadItem.ERROR) {
            holder.binding.state.setText(R.string.download_failed);
            holder.binding.progress.setProgress(item.getProgress());
            holder.binding.speed.setText("");
        } else if (state == DownloadItem.CANCELED) {
            holder.binding.state.setText(R.string.download_canceled);
            holder.binding.progress.setProgress(item.getProgress());
            holder.binding.speed.setText("");
        }
    }

    private String formatSpeed(ViewHolder holder, long speed) {
        if (speed <= 0) return "";
        return Formatter.formatFileSize(holder.binding.getRoot().getContext(), speed) + "/s";
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final AdapterDownloadEpisodeListBinding binding;
        public DownloadItem item;

        public ViewHolder(AdapterDownloadEpisodeListBinding binding, OnActionListener listener) {
            super(binding.getRoot());
            this.binding = binding;
            binding.getRoot().setOnClickListener(v -> {
                if (item == null) return;
                if (item.getState() == DownloadItem.SUCCESS) listener.onAction(item, ACTION_PLAY);
                else if (item.isActive()) listener.onAction(item, ACTION_CANCEL);
            });
            binding.getRoot().setOnLongClickListener(v -> {
                if (item != null) listener.onAction(item, ACTION_DELETE);
                return true;
            });
        }
    }
}
