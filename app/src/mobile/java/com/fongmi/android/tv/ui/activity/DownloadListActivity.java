package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadGroup;
import com.fongmi.android.tv.databinding.ActivityDownloadListBinding;
import com.fongmi.android.tv.ui.adapter.DownloadVodAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.DownloadEpisodeListDialog;
import com.fongmi.android.tv.utils.DownloadManager;

public class DownloadListActivity extends BaseActivity implements DownloadVodAdapter.OnClickListener {

    private ActivityDownloadListBinding mBinding;
    private DownloadVodAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DownloadListActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDownloadListBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, 3));
        mBinding.recycler.setAdapter(mAdapter = new DownloadVodAdapter(this));
        refresh();
    }

    @Override
    protected void initEvent() {
        DownloadManager.get().register(mCallback);
    }

    private final DownloadManager.Callback mCallback = this::refresh;

    private void refresh() {
        mAdapter.setItems(DownloadManager.get().getGroups());
        boolean empty = DownloadManager.get().getGroups().isEmpty();
        mBinding.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mBinding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onItemClick(DownloadGroup group) {
        DownloadEpisodeListDialog.show(this, group);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_download_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.clear) {
            DownloadManager.get().clearFinished();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        DownloadManager.get().unregister(mCallback);
        super.onDestroy();
    }
}
