package com.fongmi.android.tv.ui.holder;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.TmdbEpisode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EpisodeGridHolder extends BaseEpisodeHolder {

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeGridBinding binding;

    public EpisodeGridHolder(@NonNull AdapterEpisodeGridBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
    }

    @Override
    public void initView(Episode item) {
        bindCard(item, item.getTmdbEpisode());
    }

    private void bindCard(Episode item, TmdbEpisode episode) {
        binding.text.setVisibility(View.GONE);
        binding.card.setVisibility(View.VISIBLE);
        binding.card.setSelected(item.isSelected());
        binding.card.setOnClickListener(v -> listener.onItemClick(item));
        binding.card.setOnLongClickListener(view -> {
            if (item.getTmdbEpisode() != null) return com.fongmi.android.tv.ui.custom.EpisodeTitlePopup.show(view, item.getTmdbEpisode());
            return com.fongmi.android.tv.ui.custom.EpisodeTitlePopup.show(view, EpisodeAdapter.getTitle(item));
        });

        binding.cardTitle.setText(EpisodeAdapter.getTitle(item));
        binding.cardTitle.setSelected(item.isSelected());

        boolean hasStill = episode != null && !TextUtils.isEmpty(episode.getStillUrl());
        binding.imageFrame.setVisibility(hasStill ? View.VISIBLE : View.GONE);
        binding.textPanel.setGravity(hasStill ? Gravity.NO_GRAVITY : Gravity.CENTER_VERTICAL);
        if (!hasStill) {
            Glide.with(binding.still.getContext()).clear(binding.still);
            binding.still.setImageDrawable(null);
        } else {
            Glide.with(binding.still.getContext()).load(episode.getStillUrl()).into(binding.still);
        }

        if (episode == null || TextUtils.isEmpty(episode.getOverview())) {
            binding.overview.setVisibility(View.GONE);
        } else {
            binding.overview.setVisibility(View.VISIBLE);
            binding.overview.setText(episode.getOverview());
        }

        if (episode != null && episode.getVoteAverage() > 0) {
            binding.rating.setVisibility(View.VISIBLE);
            binding.rating.setText(String.format(Locale.US, "%.1f", episode.getVoteAverage()));
        } else {
            binding.rating.setVisibility(View.GONE);
        }

        String meta = episode == null || !hasStill ? "" : getMeta(episode);
        binding.meta.setVisibility(TextUtils.isEmpty(meta) ? View.GONE : View.VISIBLE);
        binding.meta.setText(meta);
    }

    private String getMeta(TmdbEpisode episode) {
        List<String> values = new ArrayList<>();
        if (!TextUtils.isEmpty(episode.getDate())) values.add(episode.getDate());
        if (episode.getRuntime() > 0) values.add(episode.getRuntime() + "m");
        return TextUtils.join(" / ", values);
    }
}
