package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbItem;
import com.fongmi.android.tv.utils.ImgUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AiRecommendationInfoDialog {

    public static void show(Activity activity, TmdbItem item) {
        if (activity == null || item == null) return;
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_ai_recommendation_info, null);
        ImageView poster = view.findViewById(R.id.poster);
        TextView title = view.findViewById(R.id.title);
        TextView subtitle = view.findViewById(R.id.subtitle);
        TextView rating = view.findViewById(R.id.rating);
        TextView reasonLabel = view.findViewById(R.id.reasonLabel);
        TextView reason = view.findViewById(R.id.reason);

        title.setText(item.getTitle());
        bindText(subtitle, meta(item));
        bindText(rating, item.getRating() > 0 ? String.format(Locale.US, "★ %.1f", item.getRating()) : "");
        String reasonText = item.getOverview();
        bindText(reason, reasonText);
        reasonLabel.setVisibility(TextUtils.isEmpty(reasonText) ? View.GONE : View.VISIBLE);
        ImgUtil.load(item.getTitle(), image(item), poster);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.ai_recommendation_info_title)
                .setView(view)
                .setPositiveButton(R.string.dialog_positive, null)
                .show();
    }

    private static void bindText(TextView view, String text) {
        view.setText(text);
        view.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private static String image(TmdbItem item) {
        return TextUtils.isEmpty(item.getPosterUrl()) ? item.getBackdropUrl() : item.getPosterUrl();
    }

    private static String meta(TmdbItem item) {
        List<String> values = new ArrayList<>();
        values.add("tv".equalsIgnoreCase(item.getMediaType()) ? "剧集" : "电影");
        for (String raw : item.getSubtitle().split("[·•/、,，]")) {
            String value = raw == null ? "" : raw.trim();
            if (TextUtils.isEmpty(value)) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (value.startsWith("评分") || lower.startsWith("score")) continue;
            if (!values.contains(value)) values.add(value);
            if (values.size() >= 3) break;
        }
        return TextUtils.join(" · ", values);
    }
}
