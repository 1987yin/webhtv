package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingPersonalBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.GroupRuleConfig;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.GroupRuleDialog;

public class SettingPersonalActivity extends BaseActivity {

    private ActivitySettingPersonalBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingPersonalActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_enable : R.string.setting_disable);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingPersonalBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.homeHistory.requestFocus();
        setText();
    }

    @Override
    protected void initEvent() {
        mBinding.homeHistory.setOnClickListener(this::setHomeHistory);
        mBinding.groupRule.setOnClickListener(this::setGroupRule);
    }

    private void setText() {
        mBinding.homeHistoryText.setText(getSwitch(Setting.isHomeHistory()));
        mBinding.groupRuleText.setText(getString(R.string.setting_group_rule_summary, GroupRuleConfig.enabledCount(), GroupRuleConfig.totalCount()));
    }

    private void setHomeHistory(View view) {
        Setting.putHomeHistory(!Setting.isHomeHistory());
        RefreshEvent.history();
        setText();
    }

    private void setGroupRule(View view) {
        GroupRuleDialog.create(this).onChanged(this::setText).show();
    }
}
