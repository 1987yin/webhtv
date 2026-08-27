package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityAppBrandingBinding;
import com.fongmi.android.tv.setting.AppBranding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;

public class AppBrandingActivity extends BaseActivity {

    private ActivityAppBrandingBinding mBinding;
    private int selectedIconMode;
    private final ActivityResultLauncher<String> imagePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImagePicked);

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, AppBrandingActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityAppBrandingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.name.setText(AppBranding.getCustomName());
        selectedIconMode = AppBranding.getIconMode(this);
        updateSelection();
        updateCustomPreview();
        mBinding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    protected void initEvent() {
        mBinding.iconCurrent.setOnClickListener(v -> selectIcon(AppBranding.ICON_CURRENT));
        mBinding.iconHistory.setOnClickListener(v -> selectIcon(AppBranding.ICON_HISTORY));
        mBinding.iconCustom.setOnClickListener(v -> selectIcon(AppBranding.ICON_CUSTOM));
        mBinding.selectImage.setOnClickListener(v -> imagePicker.launch("image/*"));
        mBinding.cancel.setOnClickListener(v -> finish());
        mBinding.save.setOnClickListener(v -> save());
    }

    private void selectIcon(int mode) {
        selectedIconMode = mode;
        updateSelection();
        if (mode == AppBranding.ICON_CUSTOM && !AppBranding.hasCustomIcon(this)) {
            imagePicker.launch("image/*");
        }
        updateCustomPreview();
    }

    private void updateSelection() {
        setSelected(mBinding.iconCurrent, mBinding.iconCurrentText, selectedIconMode == AppBranding.ICON_CURRENT);
        setSelected(mBinding.iconHistory, mBinding.iconHistoryText, selectedIconMode == AppBranding.ICON_HISTORY);
        setSelected(mBinding.iconCustom, mBinding.iconCustomText, selectedIconMode == AppBranding.ICON_CUSTOM);
    }

    private void setSelected(LinearLayoutCompat container, TextView label, boolean selected) {
        container.setSelected(selected);
        label.setTextColor(selected ? getColor(R.color.display_option_bg_selected) : getColor(R.color.white));
    }

    private void updateCustomPreview() {
        if (selectedIconMode == AppBranding.ICON_CUSTOM && AppBranding.hasCustomIcon(this)) {
            mBinding.customPreview.setVisibility(View.VISIBLE);
            mBinding.customPreview.setImageBitmap(AppBranding.loadCustomIcon(this));
            mBinding.iconCustomPreview.setImageBitmap(AppBranding.loadCustomIcon(this));
        } else if (selectedIconMode == AppBranding.ICON_CUSTOM) {
            mBinding.customPreview.setVisibility(View.GONE);
            mBinding.iconCustomPreview.setImageResource(R.drawable.ic_action_choose);
        } else {
            mBinding.customPreview.setVisibility(View.GONE);
        }
    }

    private void onImagePicked(Uri uri) {
        if (uri == null) return;
        boolean ok = AppBranding.copyCustomIcon(this, uri);
        if (ok) {
            selectedIconMode = AppBranding.ICON_CUSTOM;
            updateSelection();
            updateCustomPreview();
        } else {
            Notify.show(R.string.app_branding_image_error);
        }
    }

    private void save() {
        String name = mBinding.name.getText().toString().trim();
        AppBranding.putName(name);
        if (selectedIconMode == AppBranding.ICON_CUSTOM && !AppBranding.hasCustomIcon(this)) {
            Notify.show(R.string.app_branding_image_required);
            return;
        }
        AppBranding.putIconMode(selectedIconMode);
        // 切换桌面图标 alias
        AppBranding.applyLauncherIcon(this);
        // 自定义图标额外请求添加桌面快捷方式
        if (selectedIconMode == AppBranding.ICON_CUSTOM) {
            boolean added = AppBranding.requestCustomShortcut(this);
            if (!added) Notify.show(R.string.app_branding_shortcut_unsupported);
        }
       Notify.show(AppBranding.getSummary(this));
       setResult(RESULT_OK);
        // 重启 App 让名称和图标生效
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        finish();
    }
}
