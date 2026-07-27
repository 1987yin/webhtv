package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.bean.TmdbItem;
import com.fongmi.android.tv.databinding.AdapterTmdbRecommendationBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.Locale;

public class TmdbRecommendationPresenter extends Presenter {

    private final OnClickListener mListener;
    private final OnLongClickListener mLongClickListener;
    private final OnFocusListener mFocusListener;

    public TmdbRecommendationPresenter(OnClickListener listener) {
        this(listener, null, null);
    }

    public TmdbRecommendationPresenter(OnClickListener listener, OnLongClickListener longClickListener, OnFocusListener focusListener) {
        this.mListener = listener;
        this.mLongClickListener = longClickListener;
        this.mFocusListener = focusListener;
    }

    public interface OnClickListener {
        void onItemClick(TmdbItem item);
    }

    public interface OnLongClickListener {
        boolean onItemLongClick(TmdbItem item);
    }

    public interface OnFocusListener {
        void onItemFocus(TmdbItem item, boolean focused);
    }

    @Override
    public Presenter.ViewHolder onCreateViewHolder(ViewGroup parent) {
        return new ViewHolder(AdapterTmdbRecommendationBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        TmdbItem tmdbItem = (TmdbItem) item;
        ViewHolder holder = (ViewHolder) viewHolder;
        holder.item = tmdbItem;
        holder.binding.title.setText(tmdbItem.getTitle());
        double rating = tmdbItem.getRating();
        if (rating > 0) {
            holder.binding.rating.setText(String.format(Locale.US, "★ %.1f", rating));
            holder.binding.rating.setVisibility(View.VISIBLE);
        } else {
            holder.binding.rating.setVisibility(View.GONE);
        }
        ImgUtil.load(tmdbItem.getTitle(), tmdbItem.getPosterUrl(), holder.binding.poster);
        setOnClickListener(holder, view -> {
            if (mListener != null) mListener.onItemClick(tmdbItem);
        });
        holder.view.setOnLongClickListener(view -> mLongClickListener != null && mLongClickListener.onItemLongClick(tmdbItem));
        holder.view.setOnFocusChangeListener((view, focused) -> {
            if (mFocusListener != null) mFocusListener.onItemFocus(tmdbItem, focused);
        });
        if (holder.view.hasFocus() && mFocusListener != null) mFocusListener.onItemFocus(tmdbItem, true);
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        if (viewHolder.view.hasFocus() && holder.item != null && mFocusListener != null) {
            mFocusListener.onItemFocus(holder.item, false);
        }
        holder.item = null;
        viewHolder.view.setOnLongClickListener(null);
        viewHolder.view.setOnFocusChangeListener(null);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterTmdbRecommendationBinding binding;
        private TmdbItem item;

        public ViewHolder(@NonNull AdapterTmdbRecommendationBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
