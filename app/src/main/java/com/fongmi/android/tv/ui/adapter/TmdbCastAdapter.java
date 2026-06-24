package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbPerson;
import com.fongmi.android.tv.ui.helper.TmdbCinemaTheme;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

/**
 * TMDB 演员横向滚动适配器。
 */
public class TmdbCastAdapter extends RecyclerView.Adapter<TmdbCastAdapter.ViewHolder> {

    private final List<TmdbPerson> items = new ArrayList<>();
    private OnItemClickListener listener;
    private boolean cinema;
    private boolean light;

    public interface OnItemClickListener {
        void onItemClick(TmdbPerson person);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<TmdbPerson> cast) {
        items.clear();
        if (cast != null) items.addAll(cast);
        notifyDataSetChanged();
    }

    public void setCinema(boolean cinema) {
        if (this.cinema == cinema) return;
        this.cinema = cinema;
        notifyDataSetChanged();
    }

    public void setLight(boolean light) {
        if (this.light == light) return;
        this.light = light;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_tmdb_cast, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position), listener, cinema, light);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private static final int FOCUS_STROKE = 0xFFFFD166;
        private static final int FOCUS_ELEVATION_DP = 8;
        private final MaterialCardView card;
        private final LinearLayout content;
        private final LinearLayout textBlock;
        private final ImageView profile;
        private final TextView name;
        private final TextView role;

        public ViewHolder(@NonNull android.view.View itemView) {
            super(itemView);
            if (!Util.isLeanback()) {
                itemView.setFocusable(false);
                itemView.setFocusableInTouchMode(false);
            }
            card = (MaterialCardView) itemView;
            content = itemView.findViewById(R.id.content);
            textBlock = itemView.findViewById(R.id.text);
            profile = itemView.findViewById(R.id.profile);
            name = itemView.findViewById(R.id.name);
            role = itemView.findViewById(R.id.role);
        }

        void bind(TmdbPerson person, OnItemClickListener listener, boolean cinema, boolean light) {
            applyStyle(cinema, light);
            name.setText(person.getName());
            role.setText(person.getSubtitle());
            if (person.getProfileUrl() != null && !person.getProfileUrl().isEmpty()) {
                Glide.with(profile.getContext()).load(person.getProfileUrl()).into(profile);
            } else {
                profile.setImageResource(R.color.black);
            }
            bindFocusChrome(cinema, light);

            if (listener != null) {
                itemView.setOnClickListener(v -> listener.onItemClick(person));
            }
        }

        private void applyStyle(boolean cinema, boolean light) {
            TmdbCinemaTheme.Palette palette = TmdbCinemaTheme.palette(light);
            ViewGroup.LayoutParams itemParams = itemView.getLayoutParams();
            itemParams.width = dp(cinema ? 250 : 90);
            itemView.setLayoutParams(itemParams);
            if (itemParams instanceof ViewGroup.MarginLayoutParams marginParams) {
                marginParams.setMarginEnd(dp(cinema ? 18 : 12));
                itemView.setLayoutParams(marginParams);
            }

            content.setOrientation(cinema ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            content.setGravity(cinema ? Gravity.CENTER_VERTICAL : Gravity.NO_GRAVITY);
            content.setPadding(0, 0, 0, 0);

            ViewGroup.LayoutParams imageParams = profile.getLayoutParams();
            imageParams.width = dp(cinema ? 86 : 90);
            imageParams.height = dp(cinema ? 86 : 118);
            profile.setLayoutParams(imageParams);

            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    cinema ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    cinema ? 1f : 0f);
            textParams.setMarginStart(dp(cinema ? 14 : 0));
            textBlock.setLayoutParams(textParams);
            textBlock.setPadding(dp(cinema ? 0 : 8), dp(cinema ? 0 : 8), dp(cinema ? 0 : 8), dp(cinema ? 0 : 8));

            name.setTextColor(cinema ? palette.primary() : 0xFFFFFFFF);
            role.setTextColor(cinema ? palette.secondary() : 0x99FFFFFF);
            name.setTextSize(cinema ? 17f : 12f);
            role.setTextSize(cinema ? 13f : 10f);
            role.setMaxLines(cinema ? 1 : 2);
            profile.setBackgroundColor(cinema ? palette.imagePlaceholder() : 0xFF1C2530);
        }

        private void bindFocusChrome(boolean cinema, boolean light) {
            itemView.setOnFocusChangeListener(null);
            applyCardChrome(cinema, light, itemView.hasFocus());
            itemView.setOnFocusChangeListener((view, focused) -> applyCardChrome(cinema, light, focused));
        }

        private void applyCardChrome(boolean cinema, boolean light, boolean focused) {
            TmdbCinemaTheme.Palette palette = TmdbCinemaTheme.palette(light);
            card.setCardBackgroundColor(cinema ? palette.card() : 0xFF16202A);
            card.setStrokeColor(focused ? FOCUS_STROKE : (cinema ? palette.cardStroke() : 0x33FFFFFF));
            card.setStrokeWidth(dp(focused ? 3 : 1));
            card.setCardElevation(dp(focused ? FOCUS_ELEVATION_DP : 0));
            card.setTranslationZ(dp(focused ? FOCUS_ELEVATION_DP : 0));
        }

        private int dp(int value) {
            return Math.round(value * itemView.getResources().getDisplayMetrics().density);
        }
    }
}
