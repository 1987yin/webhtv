package com.fongmi.android.tv.ui.holder;

import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.TmdbEpisode;
import com.fongmi.android.tv.databinding.AdapterEpisodeHoriBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.utils.ResUtil;

public class EpisodeHoriHolder extends BaseEpisodeHolder {

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeHoriBinding binding;
    private final int maxWidth;
    private boolean useTmdbCard;

    public EpisodeHoriHolder(@NonNull AdapterEpisodeHoriBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
        this.maxWidth = ResUtil.getScreenWidth() - ResUtil.dp2px(32);
    }

    @Override
    public void setUseTmdbCard(boolean useTmdbCard) {
        this.useTmdbCard = useTmdbCard;
    }

    @Override
    public void initView(Episode item) {
        TmdbEpisode tmdbEpisode = item.getTmdbEpisode();
        EpisodeAdapter.bindTitlePopup(binding.getRoot(), item);

        if (useTmdbCard && tmdbEpisode != null) {
            // TMDB 模式：显示卡片，隐藏文本
            binding.text.setVisibility(View.GONE);
            binding.card.setVisibility(View.VISIBLE);

            binding.card.setSelected(item.isSelected());
            binding.card.setOnClickListener(v -> listener.onItemClick(item));

            EpisodeAdapter.bindTitlePopup(binding.card, item);
            EpisodeAdapter.bindTitlePopup(binding.still, item);
            EpisodeAdapter.bindTitlePopup(binding.cardTitle, item);
            EpisodeAdapter.bindTitlePopup(binding.overview, item);

            // 标题
            binding.cardTitle.setText(EpisodeAdapter.getTitle(item));
            binding.cardTitle.setSelected(item.isSelected());

            // 剧照
            if (!TextUtils.isEmpty(tmdbEpisode.getStillUrl())) {
                binding.still.setVisibility(View.VISIBLE);
                Glide.with(binding.still.getContext())
                        .load(tmdbEpisode.getStillUrl())
                        .into(binding.still);
            } else {
                binding.still.setVisibility(View.GONE);
            }

            // 简介
            if (!TextUtils.isEmpty(tmdbEpisode.getOverview())) {
                binding.overview.setVisibility(View.VISIBLE);
                binding.overview.setText(tmdbEpisode.getOverview());
            } else {
                binding.overview.setVisibility(View.GONE);
            }

            // 评分
            if (tmdbEpisode.getVoteAverage() > 0) {
                binding.rating.setVisibility(View.VISIBLE);
                binding.rating.setText(String.format(java.util.Locale.US, "★%.1f", tmdbEpisode.getVoteAverage()));
            } else {
                binding.rating.setVisibility(View.GONE);
            }
        } else {
            // 非 TMDB 模式：显示文本，隐藏卡片
            binding.text.setVisibility(View.VISIBLE);
            binding.card.setVisibility(View.GONE);

            binding.text.setMaxWidth(maxWidth);
            binding.text.setSelected(item.isSelected());
            binding.text.setText(EpisodeAdapter.getNativeTitle(item));
            binding.text.setOnClickListener(v -> listener.onItemClick(item));
            EpisodeAdapter.bindNativeTitlePopup(binding.text, item);
        }
    }

    private android.app.Activity getActivity(View view) {
        android.content.Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof android.app.Activity) {
                return (android.app.Activity) context;
            }
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /* TV版才有EpisodeDetailDialog,手机版暂不支持此功能
    private void fetchEpisodePhotosAndShowDialog(android.app.Activity activity, com.fongmi.android.tv.bean.TmdbEpisode episode) {
        // 先显示对话框（空剧照列表）
        java.util.List<String> photos = new java.util.ArrayList<>();
        com.fongmi.android.tv.ui.dialog.EpisodeDetailDialog.show(activity, episode, photos);

        // 后台异步获取剧照
        com.google.common.util.concurrent.ListenableFuture<java.util.List<String>> future =
            com.fongmi.android.tv.utils.Task.executor().submit(() -> {
                try {
                    com.fongmi.android.tv.bean.TmdbConfig config = com.fongmi.android.tv.bean.TmdbConfig.objectFrom(com.fongmi.android.tv.setting.Setting.getTmdbConfig());
                    if (config == null || !config.isReady()) return null;

                    com.fongmi.android.tv.service.TmdbService service = new com.fongmi.android.tv.service.TmdbService();
                    com.google.gson.JsonObject episodeDetail = service.episode(episode.getTmdbId(), episode.getSeasonNumber(), episode.getNumber(), config);
                    if (episodeDetail == null) return null;

                    return service.episodePhotos(episodeDetail, config);
                } catch (Exception e) {
                    return null;
                }
            });

        com.google.common.util.concurrent.Futures.addCallback(future,
            com.fongmi.android.tv.utils.Task.callback(result -> {
                if (result != null && !result.isEmpty()) {
                    activity.runOnUiThread(() -> {
                        com.fongmi.android.tv.ui.dialog.EpisodeDetailDialog.updatePhotos(result);
                    });
                }
            }),
            com.fongmi.android.tv.utils.Task.executor());
    }
    */
}

