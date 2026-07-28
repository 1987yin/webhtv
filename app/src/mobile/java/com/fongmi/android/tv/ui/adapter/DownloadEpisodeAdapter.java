package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
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
        Episode item = mItems.get(position);
        boolean selected = mSelected.contains(position);
        // 下载选集始终展示完整名称（忽略“短显”压缩的 displayName，直接拼接 desc+name）。
        String title = TextUtils.isEmpty(item.getDesc()) || item.getName().startsWith(item.getDesc())
                ? item.getName()
                : item.getDesc().concat(item.getName());
        holder.binding.text.setText(title);
        // 名称较长时所有项开启跑马灯，方便查看完整名称。
        holder.binding.text.setHorizontallyScrolling(true);
        holder.binding.text.setMarqueeRepeatLimit(-1);
        holder.binding.text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        // 下载选中态用 state_activated 驱动背景与文字高亮；state_selected 仅用于触发跑马灯。
        holder.binding.text.setActivated(selected);
        // 先置 false 再于布局完成后置 true，确保 startMarquee 在视图完成测量后可靠触发
        // （setSelected(true) 在已选中态为 no-op，无法重启跑马灯动画）。
        holder.binding.text.setSelected(false);
        holder.binding.text.post(() -> holder.binding.text.setSelected(true));
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
