package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadGroup;
import com.fongmi.android.tv.bean.DownloadItem;
import com.fongmi.android.tv.databinding.ActivityDownloadListBinding;
import com.fongmi.android.tv.databinding.AdapterDownloadGroupBinding;
import com.fongmi.android.tv.databinding.AdapterDownloadItemBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.DownloadManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载任务列表页。从下载通知卡片点击（ContentIntent）或首页入口进入。
 */
public class DownloadListActivity extends BaseActivity implements DownloadManager.Listener {

    private ActivityDownloadListBinding binding;
    private final List<Row> rows = new ArrayList<>();
    private DownloadAdapter adapter;

    public static void start(Activity activity) {
        Intent intent = new Intent(activity, DownloadListActivity.class);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityDownloadListBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DownloadAdapter();
        binding.recycler.setAdapter(adapter);
        binding.toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_pause_all) {
                DownloadManager.get().pauseAll();
                return true;
            } else if (id == R.id.action_clear_finished) {
                clearFinished();
                return true;
            }
            return false;
        });
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadManager.get().addListener(this);
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        DownloadManager.get().removeListener(this);
    }

    private void load() {
        List<DownloadGroup> groups = DownloadManager.get().getGroups();
        rows.clear();
        for (DownloadGroup group : groups) {
            rows.add(new Row(group.getVodName(), null));
            for (DownloadItem item : group.getItems()) rows.add(new Row(null, item));
        }
        adapter.notifyDataSetChanged();
        binding.empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String statusText(DownloadItem item) {
        int res;
        switch (item.getState()) {
            case DownloadItem.STATE_WAITING:
                res = R.string.download_status_waiting;
                break;
            case DownloadItem.STATE_RUNNING:
                res = R.string.download_status_running;
                break;
            case DownloadItem.STATE_PAUSED:
                res = R.string.download_status_paused;
                break;
            case DownloadItem.STATE_DONE:
                res = R.string.download_status_done;
                break;
            default:
                res = R.string.download_status_error;
                break;
        }
        return getString(res) + " " + item.getProgress() + "%";
    }

    private void clearFinished() {
        List<DownloadItem> finished = new ArrayList<>();
        for (DownloadItem item : DownloadManager.get().getItems()) {
            if (item.isDone()) finished.add(item);
        }
        if (!finished.isEmpty()) DownloadManager.get().remove(finished);
    }

    @Override
    public void onDownloadChange() {
        App.post(this::load);
    }

    @Override
    public void onDownloadUpdate(@NonNull DownloadItem item) {
        App.post(this::load);
    }

    private static final class Row {
        final String group;
        final DownloadItem item;

        Row(String group, DownloadItem item) {
            this.group = group;
            this.item = item;
        }

        boolean isGroup() {
            return item == null;
        }
    }

    private class DownloadAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_GROUP = 0;
        private static final int TYPE_ITEM = 1;

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).isGroup() ? TYPE_GROUP : TYPE_ITEM;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_GROUP) {
                return new GroupHolder(AdapterDownloadGroupBinding.inflate(inflater, parent, false));
            }
            return new ItemHolder(AdapterDownloadItemBinding.inflate(inflater, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (holder instanceof GroupHolder) {
                ((GroupHolder) holder).binding.setText(row.group);
            } else {
                ((ItemHolder) holder).bind(row.item);
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private class GroupHolder extends RecyclerView.ViewHolder {
        final AdapterDownloadGroupBinding binding;

        GroupHolder(AdapterDownloadGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private class ItemHolder extends RecyclerView.ViewHolder {
        final AdapterDownloadItemBinding binding;

        ItemHolder(AdapterDownloadItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(DownloadItem item) {
            String name = item.getVodName();
            if (!TextUtils.isEmpty(item.getEpisodeName())) {
                name = name + " " + item.getEpisodeName();
            }
            binding.name.setText(name);
            binding.sub.setText(statusText(item));
            binding.progress.setProgress(item.getProgress());
            binding.action.setImageResource(item.isActive() ? R.drawable.ic_audio_pause : R.drawable.ic_audio_play);
            binding.action.setOnClickListener(v -> DownloadManager.get().toggle(item));
            binding.remove.setOnClickListener(v -> DownloadManager.get().remove(item));
        }
    }
}
