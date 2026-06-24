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
    private boolean useTmdbCard;

    public EpisodeGridHolder(@NonNull AdapterEpisodeGridBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
    }

    @Override
    public void setUseTmdbCard(boolean useTmdbCard) {
        this.useTmdbCard = useTmdbCard;
    }

    @Override
    public void initView(Episode item) {
        TmdbEpisode episode = item.getTmdbEpisode();
        if (useTmdbCard && episode != null) bindCard(item, episode);
        else bindText(item);
    }

    private void bindText(Episode item) {
        binding.card.setVisibility(View.GONE);
        binding.text.setVisibility(View.VISIBLE);
        binding.text.setActivated(item.isSelected());
        binding.text.setHorizontallyScrolling(true);
        binding.text.setText(EpisodeAdapter.getNativeTitle(item));
        setMarquee(binding.text.hasFocus() || item.isSelected());
        binding.text.setOnFocusChangeListener((view, hasFocus) -> setMarquee(hasFocus || binding.text.isActivated()));
        binding.text.setOnClickListener(v -> listener.onItemClick(item));
        binding.text.post(() -> setMarquee(binding.text.hasFocus() || binding.text.isActivated()));
        EpisodeAdapter.bindNativeTitlePopup(binding.getRoot(), item);
        EpisodeAdapter.bindNativeTitlePopup(binding.text, item);
    }

    private void bindCard(Episode item, TmdbEpisode episode) {
        binding.text.setVisibility(View.GONE);
        binding.card.setVisibility(View.VISIBLE);
        binding.card.setSelected(item.isSelected());
        binding.card.setOnClickListener(v -> listener.onItemClick(item));
        EpisodeAdapter.bindTitlePopup(binding.getRoot(), item);
        EpisodeAdapter.bindTitlePopup(binding.card, item);
        EpisodeAdapter.bindTitlePopup(binding.imageFrame, item);
        EpisodeAdapter.bindTitlePopup(binding.still, item);
        EpisodeAdapter.bindTitlePopup(binding.textPanel, item);
        EpisodeAdapter.bindTitlePopup(binding.cardTitle, item);
        EpisodeAdapter.bindTitlePopup(binding.overview, item);

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

    private void setMarquee(boolean focused) {
        binding.text.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.START);
        binding.text.setSelected(focused);
    }
}
