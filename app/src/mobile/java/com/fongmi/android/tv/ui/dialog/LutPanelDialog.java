package com.fongmi.android.tv.ui.dialog;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.lut.LutPreset;
import com.fongmi.android.tv.player.lut.LutSetting;
import com.fongmi.android.tv.player.lut.LutStore;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.List;

public class LutPanelDialog extends BaseBottomSheetDialog {

    private MaterialTextView title;
    private MaterialTextView delay;
    private MaterialTextView empty;
    private RecyclerView recycler;
    private PanelAdapter adapter;
    private PlayerManager player;

    public static LutPanelDialog create() {
        return new LutPanelDialog();
    }

    public LutPanelDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof LutPanelDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return new SimpleBinding(createContent());
    }

    @Override
    protected void initView() {
        adapter = new PanelAdapter();
        recycler.setHasFixedSize(true);
        recycler.setItemAnimator(null);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);
        refreshList();
    }

    private View createContent() {
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(0xF6101118);

        LinearLayoutCompat header = new LinearLayoutCompat(requireContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayoutCompat.HORIZONTAL);
        root.addView(header, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title = text(R.string.player_lut, 18, true);
        header.addView(title, new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        MaterialTextView close = chip(R.string.lut_close);
        close.setOnClickListener(view -> dismiss());
        header.addView(close);

        LinearLayoutCompat tools = new LinearLayoutCompat(requireContext());
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setOrientation(LinearLayoutCompat.HORIZONTAL);
        LinearLayoutCompat.LayoutParams toolParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolParams.setMargins(0, dp(12), 0, dp(6));
        root.addView(tools, toolParams);

        delay = chip(0);
        delay.setOnClickListener(view -> cycleDelay());
        tools.addView(delay);

        MaterialTextView importView = chip(R.string.lut_import);
        importView.setOnClickListener(view -> ((ControlDialog.Listener) requireActivity()).onLutImport());
        tools.addView(importView);

        empty = text(R.string.lut_empty_presets, 14, false);
        empty.setGravity(Gravity.CENTER);
        root.addView(empty, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        recycler = new RecyclerView(requireContext());
        recycler.setClipToPadding(false);
        recycler.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(recycler, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    @Override
    protected void setBehavior(BottomSheetDialog dialog) {
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        int height = getPanelHeight();
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        params.height = height;
        sheet.setLayoutParams(params);
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        behavior.setPeekHeight(height);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
    }

    private int getPanelHeight() {
        int screen = ResUtil.getScreenHeight(requireContext());
        return Math.max(dp(320), Math.min(dp(480), Math.round(screen * 0.52f)));
    }

    private void refreshList() {
        List<Entry> items = new ArrayList<>();
        items.add(Entry.original());
        for (LutPreset preset : LutStore.getPresets()) items.add(Entry.preset(preset));
        adapter.setItems(items);
        empty.setVisibility(items.size() <= 1 ? View.VISIBLE : View.GONE);
        title.setText(ResUtil.getString(R.string.lut_title_value, ResUtil.getString(R.string.player_lut), LutSetting.getSummary()));
        delay.setText(ResUtil.getString(R.string.lut_preview_delay_value, LutSetting.getPreviewSeconds()));
        scrollToSelected(items);
    }

    private void scrollToSelected(List<Entry> items) {
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isSelected()) continue;
            recycler.post(() -> recycler.scrollToPosition(Math.max(0, adapter.getSelectedPosition())));
            return;
        }
    }

    private void select(LutPreset preset) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("lut-ui", "panel select preset=%s enabledBefore=%s current=%s", preset == null ? "original" : preset.getId(), LutSetting.isEnabled(), LutSetting.getPresetId());
        ((ControlDialog.Listener) requireActivity()).onLutSelected(preset);
        refreshList();
        recycler.requestFocus();
    }

    private void cycleDelay() {
        int current = LutSetting.getPreviewSeconds();
        int next = current < 2 ? 2 : current < 3 ? 3 : current < 5 ? 5 : current < 8 ? 8 : 1;
        LutSetting.putPreviewSeconds(next);
        delay.setText(ResUtil.getString(R.string.lut_preview_delay_value, next));
        if (LutSetting.isEnabled() && player != null) player.applyLutPreview(false);
    }

    private MaterialTextView chip(int resId) {
        MaterialTextView view = text(resId, 14, false);
        view.setFocusable(true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(8));
        view.setLayoutParams(params);
        setBackground(view, false, false);
        view.setOnFocusChangeListener((v, focused) -> setBackground((MaterialTextView) v, false, focused));
        return view;
    }

    private MaterialTextView text(int resId, int sp, boolean bold) {
        MaterialTextView view = new MaterialTextView(requireContext());
        if (resId != 0) view.setText(resId);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void setBackground(MaterialTextView view, boolean selected, boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? 0xFF2F80ED : focused ? 0x44FFFFFF : 0x22FFFFFF);
        drawable.setStroke(dp(1), selected || focused ? 0xFFFFFFFF : 0x33FFFFFF);
        drawable.setCornerRadius(dp(6));
        view.setBackground(drawable);
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }

    private static class SimpleBinding implements ViewBinding {
        private final View root;

        private SimpleBinding(View root) {
            this.root = root;
        }

        @NonNull
        @Override
        public View getRoot() {
            return root;
        }
    }

    private static class Entry {
        private final LutPreset preset;

        private Entry(LutPreset preset) {
            this.preset = preset;
        }

        static Entry original() {
            return new Entry(null);
        }

        static Entry preset(LutPreset preset) {
            return new Entry(preset);
        }

        String getText() {
            return preset == null ? ResUtil.getString(R.string.lut_original) : preset.getName();
        }

        boolean isSelected() {
            return preset == null ? !LutSetting.isEnabled() : LutSetting.isEnabled() && preset.getId().equals(LutSetting.getPresetId());
        }
    }

    private class PanelAdapter extends RecyclerView.Adapter<PanelAdapter.ViewHolder> {
        private final List<Entry> items = new ArrayList<>();

        void setItems(List<Entry> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        int getSelectedPosition() {
            for (int i = 0; i < items.size(); i++) if (items.get(i).isSelected()) return i;
            return 0;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialTextView view = text(0, 15, false);
            view.setFocusable(true);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setSingleLine(true);
            view.setPadding(dp(12), 0, dp(12), 0);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            params.setMargins(0, dp(8), 0, 0);
            view.setLayoutParams(params);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Entry entry = items.get(position);
            holder.text.setText(entry.getText());
            setBackground(holder.text, entry.isSelected(), holder.text.hasFocus());
            holder.text.setOnFocusChangeListener((view, focused) -> setBackground((MaterialTextView) view, entry.isSelected(), focused));
            holder.text.setOnClickListener(view -> select(entry.preset));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final MaterialTextView text;

            private ViewHolder(@NonNull MaterialTextView itemView) {
                super(itemView);
                text = itemView;
            }
        }
    }
}
