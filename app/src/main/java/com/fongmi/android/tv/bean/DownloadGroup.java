package com.fongmi.android.tv.bean;

import java.util.List;

public class DownloadGroup {

    private final String key;
    private final String name;
    private final String cover;
    private final List<DownloadItem> items;

    public DownloadGroup(String key, String name, String cover, List<DownloadItem> items) {
        this.key = key;
        this.name = name;
        this.cover = cover;
        this.items = items;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public String getCover() {
        return cover == null ? "" : cover;
    }

    public List<DownloadItem> getItems() {
        return items;
    }

    public int getTotal() {
        return items.size();
    }

    public int getDone() {
        int count = 0;
        for (DownloadItem item : items) if (item.getState() == DownloadItem.SUCCESS) count++;
        return count;
    }

    public boolean isActive() {
        for (DownloadItem item : items) if (item.isActive()) return true;
        return false;
    }
}
