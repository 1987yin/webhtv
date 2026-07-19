package com.fongmi.android.tv.bean;

import com.fongmi.android.tv.App;

import java.util.Map;
import java.util.UUID;

public class DownloadItem {

    public static final int WAITING = 0;
    public static final int DOWNLOADING = 1;
    public static final int SUCCESS = 2;
    public static final int ERROR = 3;
    public static final int CANCELED = 4;
    public static final int PAUSED = 5;

    private final String id;
    private String name;
    private String url;
    private Map<String, String> headers;
    private String filePath;
    private String cover;
    private String group;
    private int state;
    private int progress;
    private long total;
    private long speed;
    private String error;
    private volatile boolean canceled;
    private volatile boolean paused;

    public DownloadItem() {
        this.id = UUID.randomUUID().toString();
        this.state = WAITING;
    }

    public static DownloadItem create(String name) {
        DownloadItem item = new DownloadItem();
        item.setName(name);
        return item;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getCover() {
        return cover == null ? "" : cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public String getGroup() {
        return group == null ? "" : group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getSpeed() {
        return speed;
    }

    public void setSpeed(long speed) {
        this.speed = speed;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isActive() {
        return state == WAITING || state == DOWNLOADING || state == PAUSED;
    }
}
