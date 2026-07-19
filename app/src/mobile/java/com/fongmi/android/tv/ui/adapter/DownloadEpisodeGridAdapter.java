package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.databinding.AdapterDownloadEpisodeGridBinding;

import java.util.ArrayList;
import java.util.List;

public class DownloadEpisodeGridAdapter extends RecyclerView.Adapter<DownloadEpisodeGridAdapter.ViewHolder> {

    public static final int ACTION_PLAY = 0;
    public static final int ACTION_CANCEL = 1;
    public static final int ACTION_DELETE = 2;

    private final List<DownloadItem> mItems;
    private final OnActionListener mListener;

    public DownloadEpisodeGridAdapter(OnActionListener listener) {
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
        return new ViewHolder(AdapterDownloadEpisodeGridBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadItem item = mItems.get(position);
        boolean downloaded = item.getState() == DownloadItem.SUCCESS;
        holder.binding.text.setText(item.getName());
        holder.binding.check.setVisibility(downloaded ? View.VISIBLE : View.GONE);
        holder.binding.text.setActivated(downloaded);
        holder.item = item;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final AdapterDownloadEpisodeGridBinding binding;
        public DownloadItem item;

        public ViewHolder(AdapterDownloadEpisodeGridBinding binding, OnActionListener listener) {
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
