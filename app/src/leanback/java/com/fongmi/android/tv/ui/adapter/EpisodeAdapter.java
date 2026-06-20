package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.TmdbEpisode;
import com.fongmi.android.tv.databinding.AdapterEpisodeBinding;
import com.fongmi.android.tv.databinding.AdapterEpisodeCardBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {

    private static final int VIEW_TYPE_TEXT = 0;
    private static final int VIEW_TYPE_CARD = 1;

    private final OnClickListener mListener;
    private final OnLongClickListener mLongClickListener;
    private final List<Episode> mItems;
    private final int maxWidth;
    private final int spacing;
    private int nextFocusDown;
    private int nextFocusUp;
    private int column;
    private boolean useTmdbCard = false;
    private boolean gridMode = false;
    private boolean verticalGridMode = false;

    public EpisodeAdapter(OnClickListener listener) {
        this(listener, null);
    }

    public EpisodeAdapter(OnClickListener listener, OnLongClickListener longClickListener) {
        mListener = listener;
        mLongClickListener = longClickListener;
        mItems = new ArrayList<>();
        maxWidth = ResUtil.getScreenWidth() - ResUtil.dp2px(48);
        spacing = ResUtil.dp2px(8);
        column = 1;
    }

    public void addAll(List<Episode> items) {
        mItems.clear();
        mItems.addAll(items);
        column = useTmdbCard ? 1 : getColumn(items);
        notifyDataSetChanged();
    }

    public void setUseTmdbCard(boolean useTmdbCard) {
        if (this.useTmdbCard == useTmdbCard) return;
        this.useTmdbCard = useTmdbCard;
        column = useTmdbCard ? 1 : getColumn(mItems);
        notifyDataSetChanged();
    }

    public boolean isUsingTmdbCard() {
        return useTmdbCard;
    }

    public void setGridMode(boolean gridMode) {
        if (this.gridMode == gridMode) return;
        this.gridMode = gridMode;
        notifyDataSetChanged();
    }

    public void setVerticalGridMode(boolean verticalGridMode) {
        if (this.verticalGridMode == verticalGridMode) return;
        this.verticalGridMode = verticalGridMode;
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public List<Episode> getItems() {
        return mItems;
    }

    public int getSelectedPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return RecyclerView.NO_POSITION;
    }

    public int indexOf(Episode item) {
        return mItems.indexOf(item);
    }

    public void notifySelectionChanged(int oldPosition, int newPosition) {
        if (oldPosition != RecyclerView.NO_POSITION) notifyItemChanged(oldPosition);
        if (newPosition != RecyclerView.NO_POSITION && newPosition != oldPosition) notifyItemChanged(newPosition);
    }

    public Episode getActivated() {
        return mItems.isEmpty() ? new Episode() : mItems.get(getPosition());
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

    public void setNextFocusDown(int nextFocusDown) {
        if (this.nextFocusDown == nextFocusDown) return;
        this.nextFocusDown = nextFocusDown;
        notifyDataSetChanged();
    }

    public void setNextFocusUp(int nextFocusUp) {
        if (this.nextFocusUp == nextFocusUp) return;
        this.nextFocusUp = nextFocusUp;
        notifyDataSetChanged();
    }

    public void setColumn(int column) {
        column = Math.max(1, column);
        if (this.column == column) return;
        this.column = column;
        notifyDataSetChanged();
    }

    public static int getColumn(List<Episode> items) {
        int max = 1;
        for (Episode item : items) max = Math.max(max, item.getName().length());
        if (max <= 1) return 8;
        if (max <= 3) return 6;
        if (max <= 5) return 5;
        if (max <= 8) return 4;
        if (max <= 14) return 3;
        return 2;
    }

    public static String getTitle(Episode item) {
        return item.getDesc().concat(item.getDisplayName());
    }

    private int getWidth() {
        return Math.min((maxWidth - spacing * (column - 1)) / column, ResUtil.dp2px(120));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        Episode item = mItems.get(position);
        // 如果启用了TMDB卡片模式，且该集数有TMDB数据，则使用卡片布局
        return (useTmdbCard && item.getTmdbEpisode() != null) ? VIEW_TYPE_CARD : VIEW_TYPE_TEXT;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_CARD) {
            // TMDB 卡片模式
            AdapterEpisodeCardBinding binding = AdapterEpisodeCardBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                binding.cardContainer.setDefaultFocusHighlightEnabled(false);
            }
            return new ViewHolder(binding);
        } else {
            // 简单文本模式
            AdapterEpisodeBinding binding = AdapterEpisodeBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new ViewHolder(binding);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Episode item = mItems.get(position);

        if (getItemViewType(position) == VIEW_TYPE_CARD) {
            // TMDB 卡片模式
            bindCardView(holder, item, position);
        } else {
            // 简单文本模式
            bindTextView(holder, item, position);
        }
    }

    private void bindTextView(@NonNull ViewHolder holder, Episode item, int position) {
        TextView textView = holder.textView;
        if (textView == null) return;

        textView.getLayoutParams().width = getWidth();
        textView.setNextFocusUpId(isTopEdge(position) && nextFocusUp != 0 ? nextFocusUp : View.NO_ID);
        textView.setNextFocusDownId(isBottomEdge(position) && nextFocusDown != 0 ? nextFocusDown : View.NO_ID);
        textView.setSelected(item.isSelected());
        textView.setText(getTitle(item));
        textView.setOnClickListener(v -> mListener.onItemClick(item));
        if (mLongClickListener != null) {
            textView.setOnLongClickListener(v -> {
                mLongClickListener.onItemLongClick(item);
                return true;
            });
        }
    }

    private void bindCardView(@NonNull ViewHolder holder, Episode item, int position) {
        AdapterEpisodeCardBinding binding = holder.cardBinding;
        if (binding == null) return;

        TmdbEpisode tmdbEpisode = item.getTmdbEpisode();
        if (tmdbEpisode == null) return;

        applyCardSize(binding);

        // 设置选中状态（用于边框颜色）
        binding.cardContainer.setSelected(item.isSelected());
        binding.cardContainer.setNextFocusUpId(isTopEdge(position) && nextFocusUp != 0 ? nextFocusUp : View.NO_ID);
        binding.cardContainer.setNextFocusDownId(isBottomEdge(position) && nextFocusDown != 0 ? nextFocusDown : View.NO_ID);

        // 设置焦点边框效果
        binding.cardContainer.setForeground(binding.cardContainer.getContext().getDrawable(R.drawable.selector_episode_card));

        // 加载剧照
        if (!tmdbEpisode.getStillUrl().isEmpty()) {
            Glide.with(binding.still.getContext())
                .load(tmdbEpisode.getStillUrl())
                .placeholder(R.color.black)
                .error(R.color.black)
                .into(binding.still);
        } else {
            binding.still.setImageResource(R.color.black);
        }

        // 设置标题
        binding.cardTitle.setText(getCardTitle(tmdbEpisode));

        // 网格模式展示更多元信息，列表模式保持干净的横向剧照条
        if (gridMode && !tmdbEpisode.getDate().isEmpty()) {
            binding.dateBadge.setText(tmdbEpisode.getDate());
            binding.dateBadge.setVisibility(View.VISIBLE);
        } else {
            binding.dateBadge.setVisibility(View.GONE);
        }
        if (gridMode && tmdbEpisode.getRuntime() > 0) {
            binding.runtimeBadge.setText(String.format(Locale.US, "%dm", tmdbEpisode.getRuntime()));
            binding.runtimeBadge.setVisibility(View.VISIBLE);
        } else {
            binding.runtimeBadge.setVisibility(View.GONE);
        }

        // 设置简介
        if (gridMode && !tmdbEpisode.getOverview().isEmpty()) {
            binding.overview.setText(tmdbEpisode.getOverview());
            binding.overview.setVisibility(View.VISIBLE);
        } else {
            binding.overview.setVisibility(View.GONE);
        }

        // 点击和长按事件
        binding.cardContainer.setOnClickListener(v -> mListener.onItemClick(item));
        if (mLongClickListener != null) {
            binding.cardContainer.setOnLongClickListener(v -> {
                mLongClickListener.onItemLongClick(item);
                return true;
            });
        }
    }

    private void applyCardSize(AdapterEpisodeCardBinding binding) {
        ViewGroup.LayoutParams cardParams = binding.cardContainer.getLayoutParams();
        cardParams.width = gridMode ? ViewGroup.LayoutParams.MATCH_PARENT : ResUtil.dp2px(280);
        cardParams.height = ResUtil.dp2px(gridMode ? 248 : 160);
        binding.cardContainer.setLayoutParams(cardParams);
        if (cardParams instanceof ViewGroup.MarginLayoutParams marginParams) {
            marginParams.setMarginEnd(ResUtil.dp2px(12));
            marginParams.bottomMargin = gridMode ? ResUtil.dp2px(16) : 0;
            binding.cardContainer.setLayoutParams(marginParams);
        }

        ViewGroup.LayoutParams scrimParams = binding.scrim.getLayoutParams();
        scrimParams.height = ResUtil.dp2px(gridMode ? 148 : 104);
        binding.scrim.setLayoutParams(scrimParams);
        binding.textPanel.setPadding(
                ResUtil.dp2px(12),
                0,
                ResUtil.dp2px(12),
                ResUtil.dp2px(gridMode ? 14 : 12));
        binding.cardTitle.setTextSize(gridMode ? 18 : 18);
    }

    private String getCardTitle(TmdbEpisode tmdbEpisode) {
        return tmdbEpisode.getTitle().isEmpty() ? tmdbEpisode.getDisplayTitle() : tmdbEpisode.getNumber() + ". " + tmdbEpisode.getTitle();
    }

    private boolean isTopEdge(int position) {
        if (column <= 1) return position == 0;
        if (verticalGridMode) return position < column;
        return position % column == 0;
    }

    private boolean isBottomEdge(int position) {
        if (column <= 1) return position == getItemCount() - 1;
        if (verticalGridMode) {
            int lastRowCount = (getItemCount() - 1) % column + 1;
            return position >= getItemCount() - lastRowCount;
        }
        return position % column == column - 1 || position == getItemCount() - 1;
    }

    public interface OnClickListener {
        void onItemClick(Episode item);
    }

    public interface OnLongClickListener {
        void onItemLongClick(Episode item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private TextView textView;
        private AdapterEpisodeCardBinding cardBinding;

        // 简单文本模式的 ViewHolder
        ViewHolder(@NonNull AdapterEpisodeBinding binding) {
            super(binding.getRoot());
            this.textView = binding.text;
        }

        // TMDB 卡片模式的 ViewHolder
        ViewHolder(@NonNull AdapterEpisodeCardBinding binding) {
            super(binding.getRoot());
            this.cardBinding = binding;
        }
    }
}
