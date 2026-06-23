package com.fongmi.android.tv.ui.dialog;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.media3.ui.danmaku.DanmakuConfig;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogDanmakuSettingBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.LongConsumer;

final class DanmakuSettingPanel {

    private final DialogDanmakuSettingBinding binding;
    private final PlayerManager player;
    private int currentTab;

    DanmakuSettingPanel(DialogDanmakuSettingBinding binding, PlayerManager player) {
        this.binding = binding;
        this.player = player;
    }

    void bind() {
        bindAppearance();
        bindTiming();
        bindDensity();
        bindDisplay();
        bindTabs();
        applySheetColors();
        showTab(0);
        binding.tabAppearance.requestFocus();
        binding.reset.setOnClickListener(this::onReset);
    }

    private void bindAppearance() {
        var appearance = binding.appearance;
        setupSwitch(appearance.textBoldSwitch, DanmakuSetting.isTextBold(), DanmakuSetting::putTextBold);
        setupFloat(appearance.textSizeSlider, appearance.textSizeValue, DanmakuSetting.getTextScale(), "%.1f", DanmakuSetting::putTextScale);
        setupFloat(appearance.alphaSlider, appearance.alphaValue, DanmakuSetting.getTransparency(), "%.2f", DanmakuSetting::putTransparency);
        setupFloat(appearance.shadowAlphaSlider, appearance.shadowAlphaValue, DanmakuSetting.getShadowTransparency(), "%.2f", DanmakuSetting::putShadowTransparency);
        setupFloat(appearance.strokeWidthSlider, appearance.strokeWidthValue, DanmakuSetting.getStrokeWidthMultiplier(), "%.2f", DanmakuSetting::putStrokeWidthMultiplier);
        setupFloat(appearance.projectionOffsetXSlider, appearance.projectionOffsetXValue, DanmakuSetting.getProjectionOffsetX(), "%.2f", DanmakuSetting::putProjectionOffsetX);
        setupFloat(appearance.projectionOffsetYSlider, appearance.projectionOffsetYValue, DanmakuSetting.getProjectionOffsetY(), "%.2f", DanmakuSetting::putProjectionOffsetY);
        setupFloat(appearance.projectionAlphaSlider, appearance.projectionAlphaValue, DanmakuSetting.getProjectionTransparency(), "%.2f", DanmakuSetting::putProjectionTransparency);
        setupChoice(DanmakuSetting.getStyleMode(), this::styleChipForMode, this::styleModeForChip, this::onStyleModeChanged, appearance.styleNone, appearance.styleShadow, appearance.styleStroke, appearance.styleProjection);
        setupChoice(DanmakuSetting.getColorMode(), this::colorChipForMode, this::colorModeForChip, this::onColorModeChanged, appearance.colorDefault, appearance.colorColorful, appearance.colorGradient);
        updateStyleSubSettings(DanmakuSetting.getStyleMode());
        updateColorOverrideHint(DanmakuSetting.getColorMode());
    }

    private void bindTiming() {
        var timing = binding.timing;
        setupMs(timing.timeOffsetSlider, timing.timeOffsetValue, DanmakuSetting.getTimeOffsetMs(), DanmakuSetting::putTimeOffsetMs);
        setupMs(timing.durationSlider, timing.durationValue, DanmakuSetting.getDurationMs(), DanmakuSetting::putDurationMs);
        setupMs(timing.fixedDurationSlider, timing.fixedDurationValue, DanmakuSetting.getFixedDurationMs(), DanmakuSetting::putFixedDurationMs);
    }

    private void bindDensity() {
        var density = binding.density;
        setupInt(density.maxOnScreenSlider, density.maxOnScreenValue, DanmakuSetting.getMaxOnScreen(), String::valueOf, DanmakuSetting::putMaxOnScreen);
        setupFloat(density.scrollAreaSlider, density.scrollAreaValue, DanmakuSetting.getScrollAreaRatio(), "%.2f", DanmakuSetting::putScrollAreaRatio);
        setupFloat(density.scrollGapSlider, density.scrollGapValue, DanmakuSetting.getScrollGapRatio(), "%.1f", DanmakuSetting::putScrollGapRatio);
        setupFloat(density.lineSpacingSlider, density.lineSpacingValue, DanmakuSetting.getLineSpacing(), "%.1f", DanmakuSetting::putLineSpacing);
        setupInt(density.maxScrollLinesSlider, density.maxScrollLinesValue, DanmakuSetting.getMaxScrollLines(), this::linesText, DanmakuSetting::putMaxScrollLines);
        setupInt(density.maxTopLinesSlider, density.maxTopLinesValue, DanmakuSetting.getMaxTopLines(), this::linesText, DanmakuSetting::putMaxTopLines);
        setupInt(density.maxBottomLinesSlider, density.maxBottomLinesValue, DanmakuSetting.getMaxBottomLines(), this::linesText, DanmakuSetting::putMaxBottomLines);
        updateDependentControls();
    }

    private void bindDisplay() {
        var display = binding.display;
        setupSwitch(display.showScrollSwitch, DanmakuSetting.isShowScroll(), DanmakuSetting::putShowScroll, this::updateDependentControls);
        setupSwitch(display.showTopSwitch, DanmakuSetting.isShowTop(), DanmakuSetting::putShowTop, this::updateDependentControls);
        setupSwitch(display.showBottomSwitch, DanmakuSetting.isShowBottom(), DanmakuSetting::putShowBottom, this::updateDependentControls);
        setupSwitch(display.showReverseSwitch, DanmakuSetting.isShowReverse(), DanmakuSetting::putShowReverse, this::updateDependentControls);
        setupSwitch(display.showPositionedSwitch, DanmakuSetting.isShowPositioned(), DanmakuSetting::putShowPositioned, null);
        setupSwitch(display.showSubtitleSwitch, DanmakuSetting.isShowSubtitle(), DanmakuSetting::putShowSubtitle, null);
        setupSwitch(display.showSpecialSwitch, DanmakuSetting.isShowSpecial(), DanmakuSetting::putShowSpecial, null);
    }

    private void bindTabs() {
        TextView[] tabs = {binding.tabAppearance, binding.tabTiming, binding.tabDensity, binding.tabDisplay};
        for (TextView tab : tabs) {
            checkOnFocus(tab);
        }
        for (int i = 0; i < tabs.length; i++) {
            int index = i;
            tabs[i].setOnClickListener(view -> showTab(index));
        }
    }

    private void applySheetColors() {
        tintText(binding.getRoot());
        tintSlider(binding.appearance.textSizeSlider);
        tintSlider(binding.appearance.alphaSlider);
        tintSlider(binding.appearance.shadowAlphaSlider);
        tintSlider(binding.appearance.strokeWidthSlider);
        tintSlider(binding.appearance.projectionOffsetXSlider);
        tintSlider(binding.appearance.projectionOffsetYSlider);
        tintSlider(binding.appearance.projectionAlphaSlider);
        tintSlider(binding.timing.timeOffsetSlider);
        tintSlider(binding.timing.durationSlider);
        tintSlider(binding.timing.fixedDurationSlider);
        tintSlider(binding.density.maxOnScreenSlider);
        tintSlider(binding.density.scrollAreaSlider);
        tintSlider(binding.density.scrollGapSlider);
        tintSlider(binding.density.lineSpacingSlider);
        tintSlider(binding.density.maxScrollLinesSlider);
        tintSlider(binding.density.maxTopLinesSlider);
        tintSlider(binding.density.maxBottomLinesSlider);
    }

    private void tintText(View view) {
        if (view == binding.reset || view == binding.tabAppearance || view == binding.tabTiming || view == binding.tabDensity || view == binding.tabDisplay) return;
        if (view instanceof MaterialButton) return;
        if (view instanceof TextView) ((TextView) view).setTextColor(ResUtil.getColor(R.color.white_90));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintText(group.getChildAt(i));
        }
    }

    private void tintSlider(Slider slider) {
        ColorStateList active = ColorStateList.valueOf(0xCC6F86E8);
        slider.setTrackActiveTintList(active);
        slider.setTrackInactiveTintList(ColorStateList.valueOf(0x24FFFFFF));
        slider.setThumbTintList(active);
        slider.setHaloTintList(ColorStateList.valueOf(0x226F86E8));
    }

    private void checkOnFocus(TextView button) {
        button.setOnFocusChangeListener((v, focused) -> {
            if (!focused) return;
            if (button == binding.tabAppearance) showTab(0);
            else if (button == binding.tabTiming) showTab(1);
            else if (button == binding.tabDensity) showTab(2);
            else if (button == binding.tabDisplay) showTab(3);
        });
    }

    private void onReset(View view) {
        switch (currentTab) {
            case 0:
                DanmakuSetting.resetAppearance();
                bindAppearance();
                applyConfig();
                break;
            case 1:
                DanmakuSetting.resetTiming();
                bindTiming();
                applyConfig();
                break;
            case 2:
                DanmakuSetting.resetDensity();
                bindDensity();
                applyConfig();
                break;
            case 3:
                DanmakuSetting.resetDisplay();
                bindDisplay();
                updateDependentControls();
                applyConfig();
                break;
        }
    }

    private void showTab(int index) {
        View[] roots = {binding.appearance.getRoot(), binding.timing.getRoot(), binding.density.getRoot(), binding.display.getRoot()};
        TextView[] tabs = {binding.tabAppearance, binding.tabTiming, binding.tabDensity, binding.tabDisplay};
        for (int i = 0; i < roots.length; i++) roots[i].setVisibility(visibleIf(index == i));
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(index == i);
        binding.reset.setNextFocusDownId(tabs[currentTab = index].getId());
    }

    private void updateStyleSubSettings(int mode) {
        var appearance = binding.appearance;
        applyVisible(mode == DanmakuConfig.STYLE_SHADOW, appearance.shadowAlphaRow, appearance.shadowAlphaSlider);
        applyVisible(mode == DanmakuConfig.STYLE_STROKE, appearance.strokeWidthRow, appearance.strokeWidthSlider);
        applyVisible(mode == DanmakuConfig.STYLE_PROJECTION, appearance.projectionOffsetXRow, appearance.projectionOffsetXSlider, appearance.projectionOffsetYRow, appearance.projectionOffsetYSlider, appearance.projectionAlphaRow, appearance.projectionAlphaSlider);
    }

    private void applyVisible(boolean visible, View... views) {
        int visibility = visibleIf(visible);
        for (View view : views) view.setVisibility(visibility);
    }

    private void updateColorOverrideHint(int mode) {
        binding.appearance.colorOverrideHint.setVisibility(visibleIf(mode != DanmakuConfig.COLOR_MODE_DEFAULT));
    }

    private void onStyleModeChanged(int mode) {
        DanmakuSetting.putStyleMode(mode);
        updateStyleSubSettings(mode);
    }

    private void onColorModeChanged(int mode) {
        DanmakuSetting.putColorMode(mode);
        updateColorOverrideHint(mode);
    }

    private void updateDependentControls() {
        var density = binding.density;
        applyEnabled(density.maxScrollLinesRow, density.maxScrollLinesSlider, DanmakuSetting.isShowScroll());
        applyEnabled(density.maxTopLinesRow, density.maxTopLinesSlider, DanmakuSetting.isShowTop());
        applyEnabled(density.maxBottomLinesRow, density.maxBottomLinesSlider, DanmakuSetting.isShowBottom());
    }

    private void applyEnabled(View row, Slider slider, boolean enabled) {
        row.setAlpha(enabled ? 1f : 0.38f);
        slider.setEnabled(enabled);
    }

    private void applyConfig() {
        if (player != null) player.setDanmakuConfig(DanmakuSetting.getConfig());
    }

    private int styleChipForMode(int mode) {
        var appearance = binding.appearance;
        if (mode == DanmakuConfig.STYLE_NONE) return appearance.styleNone.getId();
        if (mode == DanmakuConfig.STYLE_SHADOW) return appearance.styleShadow.getId();
        if (mode == DanmakuConfig.STYLE_PROJECTION) return appearance.styleProjection.getId();
        return appearance.styleStroke.getId();
    }

    private int styleModeForChip(int chipId) {
        var appearance = binding.appearance;
        if (chipId == appearance.styleNone.getId()) return DanmakuConfig.STYLE_NONE;
        if (chipId == appearance.styleShadow.getId()) return DanmakuConfig.STYLE_SHADOW;
        if (chipId == appearance.styleProjection.getId()) return DanmakuConfig.STYLE_PROJECTION;
        return DanmakuConfig.STYLE_STROKE;
    }

    private int colorChipForMode(int mode) {
        var appearance = binding.appearance;
        if (mode == DanmakuConfig.COLOR_MODE_COLORFUL) return appearance.colorColorful.getId();
        if (mode == DanmakuConfig.COLOR_MODE_GRADIENT) return appearance.colorGradient.getId();
        return appearance.colorDefault.getId();
    }

    private int colorModeForChip(int chipId) {
        var appearance = binding.appearance;
        if (chipId == appearance.colorColorful.getId()) return DanmakuConfig.COLOR_MODE_COLORFUL;
        if (chipId == appearance.colorGradient.getId()) return DanmakuConfig.COLOR_MODE_GRADIENT;
        return DanmakuConfig.COLOR_MODE_DEFAULT;
    }

    private void setupFloat(Slider slider, TextView label, float value, String format, FloatSetter setter) {
        setupSlider(slider, label, value, sliderValue -> String.format(Locale.getDefault(), format, sliderValue), setter::set);
    }

    private void setupInt(Slider slider, TextView label, int value, IntFunction<String> formatter, IntConsumer setter) {
        setupSlider(slider, label, value, sliderValue -> formatter.apply(sliderValue.intValue()), sliderValue -> setter.accept(sliderValue.intValue()));
    }

    private void setupMs(Slider slider, TextView label, long valueMs, LongConsumer setter) {
        setupSlider(slider, label, valueMs, sliderValue -> String.format(Locale.getDefault(), "%.1fs", sliderValue / 1000f), sliderValue -> setter.accept(sliderValue.longValue()));
    }

    private void setupSlider(Slider slider, TextView label, float initial, Function<Float, String> formatter, Consumer<Float> setter) {
        float clamped = Math.max(slider.getValueFrom(), Math.min(slider.getValueTo(), initial));
        slider.clearOnChangeListeners();
        slider.setLabelFormatter(formatter::apply);
        slider.setValue(clamped);
        label.setText(formatter.apply(clamped));
        slider.addOnChangeListener((source, value, fromUser) -> {
            if (!fromUser) return;
            setter.accept(value);
            label.setText(formatter.apply(value));
            applyConfig();
        });
    }

    private void setupSwitch(CompoundButton button, boolean value, Consumer<Boolean> setter, Runnable after) {
        button.setOnCheckedChangeListener(null);
        button.setChecked(value);
        button.setOnCheckedChangeListener((source, checked) -> {
            setter.accept(checked);
            if (after != null) after.run();
            applyConfig();
        });
    }

    private void setupSwitch(CompoundButton button, boolean value, Consumer<Boolean> setter) {
        setupSwitch(button, value, setter, null);
    }

    private void setupChoice(int initialMode, IntUnaryOperator viewForMode, IntUnaryOperator modeForView, IntConsumer onChange, TextView... choices) {
        int selectedId = viewForMode.applyAsInt(initialMode);
        for (TextView choice : choices) {
            choice.setSelected(choice.getId() == selectedId);
            choice.setOnClickListener(view -> {
                for (TextView item : choices) item.setSelected(item == view);
                onChange.accept(modeForView.applyAsInt(view.getId()));
                applyConfig();
            });
        }
    }

    private String linesText(int value) {
        return value == 0 ? binding.getRoot().getContext().getString(R.string.danmaku_auto) : String.valueOf(value);
    }

    private int visibleIf(boolean condition) {
        return condition ? View.VISIBLE : View.GONE;
    }

    @FunctionalInterface
    private interface FloatSetter {
        void set(float value);
    }
}
