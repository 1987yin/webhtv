package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.TmdbEpisode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.databinding.AdapterEpisodeHoriBinding;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.custom.EpisodeTitlePopup;
import com.fongmi.android.tv.ui.holder.EpisodeGridHolder;
import com.fongmi.android.tv.ui.holder.EpisodeHoriHolder;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.List;

public class EpisodeAdapter extends RecyclerView.Adapter<BaseEpisodeHolder> {

    private final OnClickListener listener;
    private final List<Episode> mItems;
    private final int viewType;

    public EpisodeAdapter(OnClickListener listener, int viewType) {
        this(listener, viewType, new ArrayList<>());
    }

    public EpisodeAdapter(OnClickListener listener, int viewType, ArrayList<Episode> items) {
        this.listener = listener;
        this.viewType = viewType;
        this.mItems = items;
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public void addAll(List<Episode> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public int getPosition(Episode item) {
        return mItems.indexOf(item);
    }

    public Episode getActivated() {
        return mItems.get(getPosition());
    }

    public Episode getNext() {
        int current = getPosition();
        int max = getItemCount() - 1;
        current = ++current > max ? max : current;
        return mItems.get(current);
    }

    public Episode getPrev() {
        int current = getPosition();
        current = --current < 0 ? 0 : current;
        return mItems.get(current);
    }

    public List<Episode> getItems() {
        return mItems;
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    /**
     * 绑定标题和长按事件（供 Holder 调用）
     */
    public static String getTitle(Episode item) {
        if (item == null) return "";
        TmdbEpisode tmdbEpisode = item.getTmdbEpisode();
        if (tmdbEpisode != null) return getTmdbTitle(item, tmdbEpisode);
        String title = TextUtils.isEmpty(item.getDisplayName()) ? item.getName() : item.getDisplayName();
        if (TextUtils.isEmpty(item.getDesc()) || title.startsWith(item.getDesc())) return title;
        return item.getDesc().concat(title);
    }

    private static String getTmdbTitle(Episode item, TmdbEpisode tmdbEpisode) {
        if (!TextUtils.isEmpty(tmdbEpisode.getTitle())) {
            return tmdbEpisode.getNumber() > 0 ? tmdbEpisode.getNumber() + ". " + tmdbEpisode.getTitle() : tmdbEpisode.getTitle();
        }
        if (tmdbEpisode.getNumber() > 0) return "第" + tmdbEpisode.getNumber() + "集";
        return TextUtils.isEmpty(item.getName()) ? item.getDisplayName() : item.getName();
    }

    public static void bindTitle(MaterialTextView text, Episode item) {
        String title = getTitle(item);
        text.setText(title);
        applyMarquee(text, item.isSelected(), text.hasFocus());
        text.setOnFocusChangeListener((view, hasFocus) -> applyMarquee(text, item.isSelected(), hasFocus));
        text.setOnLongClickListener(view -> {
            if (item.getTmdbEpisode() != null) {
                return EpisodeTitlePopup.show(view, item.getTmdbEpisode());
            } else {
                return EpisodeTitlePopup.show(view, title);
            }
        });
    }

    public static void dismissTitlePopup() {
        EpisodeTitlePopup.dismiss();
    }

    private static void applyMarquee(MaterialTextView text, boolean selected, boolean focused) {
        text.setSelected(selected || focused);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return viewType;
    }

    @Override
    public void onBindViewHolder(@NonNull BaseEpisodeHolder holder, int position) {
        holder.initView(mItems.get(position));
    }

    @NonNull
    @Override
    public BaseEpisodeHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == ViewType.HORI) {
            return new EpisodeHoriHolder(AdapterEpisodeHoriBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
        } else {
            return new EpisodeGridHolder(AdapterEpisodeGridBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
        }
    }
}
