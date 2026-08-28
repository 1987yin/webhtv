package com.fongmi.android.tv.ui.activity;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.lab.LabActivity;
import com.fongmi.android.tv.lab.LabConfig;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.receiver.ShortcutReceiver;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.AutoBackupPolicy;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.FragmentStateManager;
import com.fongmi.android.tv.ui.fragment.SettingEnhanceFragment;
import com.fongmi.android.tv.ui.fragment.SettingAiFragment;
import com.fongmi.android.tv.ui.fragment.SettingTmdbFragment;
import com.fongmi.android.tv.ui.fragment.SettingDanmakuFragment;
import com.fongmi.android.tv.ui.fragment.SettingFragment;
import com.fongmi.android.tv.ui.fragment.SettingPersonalFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;
import com.fongmi.android.tv.ui.fragment.SettingSubtitleFragment;
import com.fongmi.android.tv.ui.fragment.VodFragment;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.web.WebHomeChromeStartup;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.github.catvod.net.OkHttp;
import com.google.android.material.navigation.NavigationBarView;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends BaseActivity implements NavigationBarView.OnItemSelectedListener, WebHomeChromeController.Host {

    public static final String EXTRA_NAV_POSITION = "nav_position";
    private static final String STATE_RETURN_VOD_FROM_ENHANCE = "returnVodFromEnhance";
    private static final String STATE_CURRENT_POSITION = "currentPosition";

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;
    private WebHomeChromeController mChrome;
    private Config mStartupConfig;
    private boolean wideWindow;
    private int currentPosition;
    private boolean returnVodFromEnhance;

    // ==========密钥校验新增变量==========
    private final String REMOTE_KEY_URL = "https://gitee.com/xuanzhu181/webhtv/raw/V1.0/third_party/mini.txt";
    private final int MAX_RETRY = 3;
    private SharedPreferences sp;
    private AlertDialog keyDialog;
    private ProgressDialog loadingDialog;
    private Handler mainHandler;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_App);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        wideWindow = MobileWindow.isWide(this);
        returnVodFromEnhance = savedInstanceState != null && savedInstanceState.getBoolean(STATE_RETURN_VOD_FROM_ENHANCE);
        currentPosition = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_CURRENT_POSITION, 0);
        mStartupConfig = Config.vod();
        mChrome = new WebHomeChromeController(this, mBinding, this, savedInstanceState, WebHomeChromeStartup.restore(mStartupConfig));
        mBinding.getRoot().addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> checkWindowShape(right - left, bottom - top));
        mBinding.navigation.setOnItemSelectedListener(this);
        PermissionUtil.requestFile(this, allGranted -> PermissionUtil.requestNotify(this));
        initFragment(savedInstanceState);
        initConfig();

        // ==========密钥校验逻辑放在initView末尾==========
        sp = getSharedPreferences("key_store", MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());

        loadingDialog = new ProgressDialog(this);
        loadingDialog.setMessage("正在校验密钥，请稍候...");
        loadingDialog.setCancelable(false);

        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_key_input, null);
        EditText etInput = dialogView.findViewById(R.id.et_input_key);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm_key);
        TextView tvExit = dialogView.findViewById(R.id.tv_exit_app);

        // 重点：不要 setTitle setMessage setPositiveButton，全部使用自定义布局控件
        keyDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        //确认按钮
        btnConfirm.setOnClickListener(v -> {
            String userInput = etInput.getText().toString().trim();
            if (userInput.isEmpty()) {
                Toast.makeText(HomeActivity.this, "请输入密钥", Toast.LENGTH_SHORT).show();
                return;
            }
            checkNetKey(userInput, true, 0);
        });

        //退出软件按钮
        tvExit.setOnClickListener(v -> {
            finish();
            System.exit(0);
        });

        String localSavedKey = sp.getString("saved_key", null);
        if (localSavedKey == null) {
            keyDialog.show();
        } else {
            checkNetKey(localSavedKey, false, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding.navigation.getMenu().findItem(R.id.lab).isVisible() != LabConfig.get().getNavEntry()) setNavigation();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_RETURN_VOD_FROM_ENHANCE, returnVodFromEnhance);
        outState.putInt(STATE_CURRENT_POSITION, currentPosition);
        if (mChrome != null) mChrome.save(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void initEvent() {
        mBinding.navigation.findViewById(R.id.live).setOnLongClickListener(this::addShortcut);
    }

    private void checkAction(Intent intent) {
        if (intent.hasExtra(EXTRA_NAV_POSITION)) {
            change(intent.getIntExtra(EXTRA_NAV_POSITION, 0));
            intent.removeExtra(EXTRA_NAV_POSITION);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    private void initFragment(Bundle savedInstanceState) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager(), position -> switch (position) {
            case 0 -> VodFragment.newInstance();
            case 1 -> SettingFragment.newInstance();
            case 2 -> SettingPlayerFragment.newInstance();
            case 3 -> SettingEnhanceFragment.newInstance();
            case 4 -> SettingDanmakuFragment.newInstance();
            case 5 -> SettingPersonalFragment.newInstance();
            case 6 -> SettingSubtitleFragment.newInstance();
            case 7 -> SettingTmdbFragment.newInstance();
            case 8 -> SettingAiFragment.newInstance();
            default -> null;
        });
        if (savedInstanceState == null) change(0);
        else restorePosition(currentPosition);
    }

    private void restorePosition(int position) {
        setNavigation();
        syncNavigationSelection();
        changeFragment(position <= 0 ? 0 : position);
    }

    private void initConfig() {
        VodConfig.get().config(mStartupConfig == null ? Config.vod() : mStartupConfig).load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                checkAction(getIntent());
            }

            @Override
            public void error(String msg) {
                resetVodChrome();
                checkAction(getIntent());
                StateEvent.empty();
                Notify.show(msg);
            }
        };
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                openLive();
            }
        });
    }

    private void setNavigation() {
        mBinding.navigation.getMenu().findItem(R.id.vod).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.setting).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.lab).setVisible(LabConfig.get().getNavEntry());
        mBinding.navigation.getMenu().findItem(R.id.live).setVisible(LiveConfig.hasUrl());
        syncNavigationSelection();
    }

    private boolean openLive() {
        LiveActivity.start(this);
        return false;
    }

    private boolean addShortcut(View view) {
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, getString(R.string.nav_live)).setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher)).setIntent(new Intent(Intent.ACTION_VIEW, null, this, LiveActivity.class)).setShortLabel(getString(R.string.nav_live)).build();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(this, ShortcutReceiver.class).setAction(ShortcutReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ShortcutManagerCompat.requestPinShortcut(this, info, pendingIntent.getIntentSender());
        return true;
    }

    public void change(int position) {
        if (position != 3) returnVodFromEnhance = false;
        setNavigationVisible(true);
        if (position < 2) selectNavigation(position);
        else changeFragment(position);
    }

    public void setNavigationVisible(boolean visible) {
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) mBinding.container.getLayoutParams();
        if (visible) {
            params.addRule(RelativeLayout.ABOVE, R.id.navigation);
            mBinding.navigation.setVisibility(View.VISIBLE);
        } else {
            params.removeRule(RelativeLayout.ABOVE);
            mBinding.navigation.setVisibility(View.GONE);
        }
        mBinding.container.setLayoutParams(params);
        mBinding.getRoot().requestApplyInsets();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                break;
            case COMMON:
                setNavigation();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.THEME) recreate();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) SearchActivity.start(this, event.text());
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        returnVodFromEnhance = false;
        setNavigationVisible(true);
        if (item.getItemId() == R.id.setting) return changeFragment(1);
        if (item.getItemId() == R.id.lab) {
            LabActivity.start(this);
            return false;
        }
        if (item.getItemId() == R.id.vod) return changeFragment(0);
        if (item.getItemId() == R.id.live) return openLive();
        return false;
    }

    private void selectNavigation(int position) {
        int itemId = position == 0 ? R.id.vod : R.id.setting;
        if (mBinding.navigation.getSelectedItemId() == itemId) changeFragment(position);
        else mBinding.navigation.setSelectedItemId(itemId);
    }

    private void syncNavigationSelection() {
        int itemId = currentPosition == 0 ? R.id.vod : R.id.setting;
        if (mBinding.navigation.getSelectedItemId() == itemId) return;
        mBinding.navigation.setOnItemSelectedListener(null);
        mBinding.navigation.setSelectedItemId(itemId);
        mBinding.navigation.setOnItemSelectedListener(this);
    }

    private boolean changeFragment(int position) {
        boolean changed = mManager.change(position);
        if (changed) currentPosition = position;
        refreshWebHomeChromeLayout();
        return changed;
    }

    private boolean isSettingSubPageVisible() {
        return mManager.isVisible(2) || mManager.isVisible(3) || mManager.isVisible(4) || mManager.isVisible(5) || mManager.isVisible(6) || mManager.isVisible(7) || mManager.isVisible(8);
    }

    private void refreshWebHomeChromeLayout() {
        if (mChrome != null) mChrome.refreshLayout();
    }

    private void resetVodChrome() {
        if (mChrome != null) mChrome.setLegacyToolbar(true);
    }

    public void applyWebHomeDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.applyDefault(WebHomeChromeStartup.resolve(VodConfig.get().getConfig(), site));
    }

    public void setWebHomeChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (isStartupChrome(payload)) WebHomeChromeStartup.remember(VodConfig.get().getConfig(), VodConfig.get().getHome(), payload);
        if (mChrome != null) mChrome.setChrome(payload);
    }

    private boolean isStartupChrome(JsonObject payload) {
        try {
            return payload != null && payload.has("startup") && payload.get("startup").getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
    }

    public void restoreWebHomeChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.restore();
    }

    public void setWebHomeLegacyToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.setLegacyToolbar(visible);
    }

    public void refreshWebHomeChromeState() {
        onWebHomeChromeChanged(getWebHomeChromeMode());
    }

    private JsonObject normalWebHomeChrome() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", "normal");
        return object;
    }

    public void openVod() {
        resetVodChrome();
        setNavigationVisible(true);
        mBinding.navigation.setSelectedItemId(R.id.vod);
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.openVodHome();
    }

    public void openEnhanceFromVod() {
        returnVodFromEnhance = true;
        setNavigationVisible(true);
        changeFragment(3);
    }

    public String getWebHomeChromeMode() {
        return mChrome == null ? "normal" : mChrome.getMode();
    }

    public WebHomeViewport getWebHomeViewport() {
        return mChrome == null ? WebHomeViewport.EMPTY : mChrome.getViewport();
    }

    @Override
    public boolean isWebHomeChromeActive() {
        return mManager != null && mManager.isVisible(0);
    }

    @Override
    public void onWebHomeChromeChanged(String mode) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeChrome(mode);
    }

    @Override
    public void onWebHomeViewportChanged(WebHomeViewport viewport) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeViewport(viewport);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mChrome != null) mChrome.onConfigurationChanged();
        App.post(this::checkWindowShape, 100);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mChrome != null) mChrome.onWindowFocusChanged(hasFocus);
    }

    private void checkWindowShape() {
        checkWindowShape(MobileWindow.getWidth(this), MobileWindow.getHeight(this));
    }

    private void checkWindowShape(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean wide = width > height;
        if (wideWindow != wide) {
            wideWindow = wide;
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (mChrome != null && mChrome.consumeBack()) {
            return;
        } else if (!mBinding.navigation.getMenu().findItem(R.id.vod).isVisible()) {
            setNavigation();
        } else if (returnVodFromEnhance && mManager.isVisible(3)) {
            returnVodFromEnhance = false;
            change(0);
        } else if (isSettingSubPageVisible()) {
            change(1);
        } else if (mManager.isVisible(1)) {
            change(0);
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (mChrome != null) mChrome.destroy();
        LiveConfig.get().clear();
        VodConfig.get().clear("mobile-home-destroy");
        if (AutoBackupPolicy.shouldRun(
                Setting.isAutoBackup(),
                Setting.hasFileAccess(),
                isFinishing(),
                isChangingConfigurations())) {
            AppDatabase.autoBackup();
        }
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }

    // ==========密钥校验新增方法==========
    private void checkNetKey(String inputKey, boolean isFirstInput, int retryCount) {
        if (retryCount == 0) {
            mainHandler.post(() -> loadingDialog.show());
        }
        new Thread(() -> {
            List<String> remoteKeyList = null;
            boolean netOk = true;
            try {
                URL url = new URL(REMOTE_KEY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                remoteKeyList = new ArrayList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    String trimLine = line.trim();
                    if (!trimLine.isEmpty()) remoteKeyList.add(trimLine);
                }
                br.close();
                conn.disconnect();
            } catch (Exception e) {
                netOk = false;
            }

            List<String> finalKeyList = remoteKeyList;
            boolean finalNetOk = netOk;
            int currentRetry = retryCount;
            mainHandler.post(() -> {
                loadingDialog.dismiss();
                if (!finalNetOk) {
                    if (currentRetry < MAX_RETRY) {
                        checkNetKey(inputKey, isFirstInput, currentRetry + 1);
                        return;
                    } else {
                        new AlertDialog.Builder(HomeActivity.this)
                                .setTitle("网络校验失败")
                                .setMessage("多次网络请求失败，是否使用本地已保存密钥离线使用？")
                                .setCancelable(false)
                                .setPositiveButton("离线继续", (d, w) -> enterMainUi())
                                .setNegativeButton("退出", (d, w) -> {
                                    finish();
                                    System.exit(0);
                                }).show();
                        return;
                    }
                }
                if (finalKeyList == null || finalKeyList.isEmpty()) {
                    Toast.makeText(HomeActivity.this, "云端密钥配置异常", Toast.LENGTH_SHORT).show();
                    finish();
                    System.exit(0);
                    return;
                }
                boolean match = finalKeyList.contains(inputKey);
                if (isFirstInput) {
                    if (match) {
                        sp.edit().putString("saved_key", inputKey).apply();
                        keyDialog.dismiss();
                        enterMainUi();
                    } else {
                        Toast.makeText(HomeActivity.this, "密钥错误，退出", Toast.LENGTH_SHORT).show();
                        finish();
                        System.exit(0);
                    }
                } else {
                    if (match) {
                        enterMainUi();
                    } else {
                        sp.edit().remove("saved_key").apply();
                        keyDialog.show();
                    }
                }
            });
        }).start();
    }

    private void enterMainUi() {
        keyDialog.dismiss();
        loadingDialog.dismiss();
    }
}
