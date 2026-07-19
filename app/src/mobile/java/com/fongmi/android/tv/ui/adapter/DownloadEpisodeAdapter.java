package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterDownloadEpisodeBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DownloadEpisodeAdapter extends RecyclerView.Adapter<DownloadEpisodeAdapter.ViewHolder> {

    private final List<Episode> mItems;
    private final Set<Integer> mSelected;
    private final OnClickListener mListener;

    public interface OnClickListener {
        void onItemClick(int position);
    }

    public DownloadEpisodeAdapter(OnClickListener listener) {
        this.mItems = new ArrayList<>();
        this.mSelected = new HashSet<>();
        this.mListener = listener;
    }

    public void addAll(List<Episode> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void toggle(int position) {
        if (mSelected.contains(position)) mSelected.remove(position);
        else mSelected.add(position);
        notifyItemChanged(position);
    }

    public void selectAll(boolean all) {
        mSelected.clear();
        if (all) for (int i = 0; i < mItems.size(); i++) mSelected.add(i);
        notifyDataSetChanged();
    }

    public boolean isAllSelected() {
        return !mItems.isEmpty() && mSelected.size() == mItems.size();
    }

    public List<Episode> getSelected() {
        List<Episode> result = new ArrayList<>();
        for (int i : mSelected) result.add(mItems.get(i));
        return result;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDownloadEpisodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.text.setText(mItems.get(position).getName());
        holder.binding.text.setSelected(mSelected.contains(position));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final AdapterDownloadEpisodeBinding binding;

        public ViewHolder(AdapterDownloadEpisodeBinding binding, OnClickListener listener) {
            super(binding.getRoot());
            this.binding = binding;
            binding.getRoot().setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) listener.onItemClick(position);
            });
        }
    }
}
