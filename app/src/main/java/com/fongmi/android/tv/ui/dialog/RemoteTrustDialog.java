package com.fongmi.android.tv.ui.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.remote.RemoteAgent;
import com.fongmi.android.tv.remote.RemoteAgentService;
import com.fongmi.android.tv.remote.RemoteClient;
import com.fongmi.android.tv.remote.RemoteModels.BindCodeResponse;
import com.fongmi.android.tv.remote.RemoteModels.ClaimResponse;
import com.fongmi.android.tv.remote.RemoteModels.CommandDetailResponse;
import com.fongmi.android.tv.remote.RemoteModels.CommandResponse;
import com.fongmi.android.tv.remote.RemoteModels.DevicesResponse;
import com.fongmi.android.tv.remote.RemoteModels.RemoteBindGrant;
import com.fongmi.android.tv.remote.RemoteModels.RemoteCapabilities;
import com.fongmi.android.tv.remote.RemoteModels.RemoteCommand;
import com.fongmi.android.tv.remote.RemoteModels.RemoteCommandResult;
import com.fongmi.android.tv.remote.RemoteModels.RemoteDevice;
import com.fongmi.android.tv.remote.RemoteModels.RemoteGroup;
import com.fongmi.android.tv.remote.RemoteModels.RemoteProfile;
import com.fongmi.android.tv.remote.RemoteModels.ServerCapabilities;
import com.fongmi.android.tv.remote.RemoteStore;
import com.fongmi.android.tv.remote.RemoteTokens;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class RemoteTrustDialog {

    private static final int PAGE_DEVICES = 0;
    private static final int PAGE_DETAIL = 1;
    private static final int PAGE_SETTINGS = 2;

    private RemoteTrustDialog() {
    }

    public static void show(Fragment fragment, Runnable callback) {
        show(fragment.requireActivity(), callback);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        Binding binding = build(activity);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setView(binding.root)
                .create();
        binding.dialog = dialog;
        binding.callback = callback;
        render(activity, binding);
        dialog.setOnShowListener(d -> {
            configureWindow(activity, dialog);
            binding.close.setOnClickListener(v -> dialog.dismiss());
            binding.devicesTab.setOnClickListener(v -> {
                binding.page = PAGE_DEVICES;
                render(activity, binding);
            });
            binding.settingsTab.setOnClickListener(v -> {
                binding.page = PAGE_SETTINGS;
                render(activity, binding);
            });
        });
        dialog.show();
    }

    private static Binding build(Context context) {
        Binding binding = new Binding();
        binding.root = new LinearLayoutCompat(context);
        binding.root.setOrientation(LinearLayoutCompat.VERTICAL);
        binding.root.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        binding.root.setBackground(background(context, "#FFFFFF", Color.TRANSPARENT, 16));

        LinearLayoutCompat header = row(context);
        MaterialTextView title = text(context, context.getString(R.string.setting_remote_trust), 21, "#202124", true);
        header.addView(title, weight());
        binding.close = closeButton(context);
        header.addView(binding.close, new LinearLayoutCompat.LayoutParams(dp(context, 38), dp(context, 38)));
        binding.root.addView(header, matchWrap());

        binding.summary = text(context, "", 13, "#5F6368", false);
        binding.summary.setPadding(0, dp(context, 2), 0, dp(context, 6));
        binding.root.addView(binding.summary, matchWrap());

        LinearLayoutCompat tabs = row(context);
        binding.devicesTab = tab(context, R.string.remote_trust_tab_devices);
        binding.settingsTab = tab(context, R.string.remote_trust_tab_settings);
        tabs.addView(binding.devicesTab, weight());
        tabs.addView(binding.settingsTab, leftWeight(context, 6));
        binding.root.addView(tabs, topMargin(matchWrap(), 6));

        binding.scroll = new NestedScrollView(context);
        binding.scroll.setFillViewport(false);

        binding.content = new LinearLayoutCompat(context);
        binding.content.setOrientation(LinearLayoutCompat.VERTICAL);
        binding.scroll.addView(binding.content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        binding.root.addView(binding.scroll, topMargin(matchWrap(), 10));

        binding.server = input(context, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true);
        binding.serverLayout = inputLayout(context, R.string.remote_trust_server_url, binding.server);
        binding.enabled = check(context, R.string.remote_trust_enable);
        binding.keepOnline = check(context, R.string.remote_trust_keep_online);
        return binding;
    }

    private static void configureWindow(Context context, AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (ResUtil.getScreenWidth(context) * (ResUtil.isLand(context) ? 0.72f : 0.92f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setAttributes(params);
        window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void render(Context context, Binding binding) {
        initFields(binding);
        binding.actions.clear();
        binding.content.removeAllViews();
        binding.summary.setText(RemoteStore.summary(context) + (Setting.hasFileAccess() ? "" : "\n" + context.getString(R.string.remote_trust_file_permission_hint)));
        binding.devicesTab.setChecked(binding.page == PAGE_DEVICES || binding.page == PAGE_DETAIL);
        binding.settingsTab.setChecked(binding.page == PAGE_SETTINGS);
        if (binding.page == PAGE_SETTINGS) renderSettings(context, binding);
        else if (binding.page == PAGE_DETAIL) renderDeviceDetail(context, binding);
        else renderDevices(context, binding);
        setBusy(binding, binding.busy);
        if (binding.callback != null) binding.callback.run();
    }

    private static void initFields(Binding binding) {
        if (binding.initialized) return;
        binding.initialized = true;
        RemoteProfile profile = RemoteStore.firstProfile();
        if (profile == null) {
            binding.enabled.setChecked(true);
            return;
        }
        binding.server.setText(TextUtils.isEmpty(profile.serverUrl) ? profile.serverOrigin : profile.serverUrl);
        binding.enabled.setChecked(profile.enabled);
        binding.keepOnline.setChecked(profile.keepOnline);
    }

    private static void renderDevices(Context context, Binding binding) {
        RemoteProfile profile = currentProfile(binding);
        binding.content.addView(sectionTitle(context, R.string.remote_trust_local_status), matchWrap());
        binding.content.addView(panel(context, localStatus(context, profile)), topMargin(matchWrap(), 6));

        LinearLayoutCompat actions = row(context);
        MaterialButton bind = primaryAction(binding, context, R.string.remote_trust_bind_local);
        MaterialButton add = tonalAction(binding, context, R.string.remote_trust_add_device);
        bind.setOnClickListener(v -> showBindCodeDialog((FragmentActivity) context, binding));
        add.setOnClickListener(v -> showAddDeviceDialog((FragmentActivity) context, binding));
        actions.addView(bind, weight());
        actions.addView(add, leftWeight(context));
        binding.content.addView(actions, topMargin(matchWrap(), 10));

        LinearLayoutCompat titleRow = row(context);
        titleRow.addView(sectionTitle(context, R.string.remote_trust_device_list), weight());
        MaterialButton refresh = smallOutlineAction(binding, context, R.string.remote_trust_refresh_devices);
        refresh.setOnClickListener(v -> refreshDevices((FragmentActivity) context, binding));
        titleRow.addView(refresh, fixed(context, 86, 32));
        binding.content.addView(titleRow, topMargin(matchWrap(), 16));

        List<DeviceRow> rows = deviceRows(profile);
        if (rows.isEmpty()) {
            binding.content.addView(emptyPanel(context, context.getString(R.string.remote_trust_no_devices)), topMargin(matchWrap(), 8));
            if (profile == null) {
                MaterialButton settings = outlineAction(binding, context, R.string.remote_trust_go_settings);
                settings.setOnClickListener(v -> {
                    binding.page = PAGE_SETTINGS;
                    render(context, binding);
                });
                binding.content.addView(settings, topMargin(matchWrap(), 10));
            }
            return;
        }
        for (DeviceRow row : rows) {
            MaterialButton item = listButton(context, deviceText(context, profile, row.group, row.device));
            bindAction(binding, item);
            item.setOnClickListener(v -> {
                binding.selectedGroupId = row.group.groupId;
                binding.selectedDeviceId = row.device.deviceId;
                binding.lastResult = "";
                binding.page = PAGE_DETAIL;
                render(context, binding);
            });
            binding.content.addView(item, topMargin(matchWrap(), 8));
        }
    }

    private static void renderDeviceDetail(Context context, Binding binding) {
        RemoteProfile profile = currentProfile(binding);
        DeviceRow row = selectedRow(profile, binding);
        if (row == null) {
            binding.page = PAGE_DEVICES;
            renderDevices(context, binding);
            return;
        }
        MaterialButton back = smallOutlineAction(binding, context, R.string.remote_trust_back_devices);
        back.setOnClickListener(v -> {
            binding.page = PAGE_DEVICES;
            render(context, binding);
        });
        binding.content.addView(back, matchWrap());

        binding.content.addView(sectionTitle(context, deviceName(row.device)), topMargin(matchWrap(), 12));
        binding.content.addView(panel(context, deviceDetailText(context, profile, row.group, row.device)), topMargin(matchWrap(), 6));

        LinearLayoutCompat row1 = row(context);
        MaterialButton status = primaryAction(binding, context, R.string.remote_trust_action_status);
        MaterialButton search = tonalAction(binding, context, R.string.remote_trust_action_search);
        status.setOnClickListener(v -> sendCommand((FragmentActivity) context, binding, "device.status", new JsonObject()));
        search.setOnClickListener(v -> showTextCommandDialog((FragmentActivity) context, binding, R.string.remote_trust_action_search, R.string.remote_trust_search_keyword, "action.search", "word"));
        row1.addView(status, weight());
        row1.addView(search, leftWeight(context));
        binding.content.addView(row1, topMargin(matchWrap(), 12));

        LinearLayoutCompat row2 = row(context);
        MaterialButton push = tonalAction(binding, context, R.string.remote_trust_action_push);
        MaterialButton log = outlineAction(binding, context, R.string.remote_trust_action_log);
        push.setOnClickListener(v -> showTextCommandDialog((FragmentActivity) context, binding, R.string.remote_trust_action_push, R.string.remote_trust_push_url, "action.push", "url"));
        log.setOnClickListener(v -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("limit", 200);
            sendCommand((FragmentActivity) context, binding, "log.recent", payload);
        });
        row2.addView(push, weight());
        row2.addView(log, leftWeight(context));
        binding.content.addView(row2, topMargin(matchWrap(), 8));

        if (!TextUtils.isEmpty(binding.lastResult)) {
            binding.content.addView(sectionTitle(context, R.string.remote_trust_command_result_title), topMargin(matchWrap(), 14));
            binding.content.addView(panel(context, binding.lastResult), topMargin(matchWrap(), 6));
        }
    }

    private static void renderSettings(Context context, Binding binding) {
        binding.content.addView(sectionTitle(context, R.string.remote_trust_settings_title), matchWrap());
        binding.content.addView(binding.serverLayout, topMargin(matchWrap(), 8));

        String state = TextUtils.isEmpty(binding.serviceStateText) ? context.getString(R.string.remote_trust_service_unchecked) : binding.serviceStateText;
        binding.content.addView(servicePanel(context, state, binding.serviceDetailText), topMargin(matchWrap(), 10));

        LinearLayoutCompat serviceRow = row(context);
        MaterialButton detect = tonalAction(binding, context, R.string.remote_trust_detect_service);
        MaterialButton save = primaryAction(binding, context, R.string.remote_trust_save_register);
        detect.setOnClickListener(v -> detectService((FragmentActivity) context, binding));
        save.setOnClickListener(v -> saveAndRegister((FragmentActivity) context, binding));
        serviceRow.addView(detect, weight());
        serviceRow.addView(save, leftWeight(context));
        binding.content.addView(serviceRow, topMargin(matchWrap(), 10));

        if (!TextUtils.isEmpty(binding.diagnostics)) {
            MaterialButton copy = outlineAction(binding, context, R.string.remote_trust_copy_diagnostics);
            copy.setOnClickListener(v -> copyText(context, context.getString(R.string.setting_remote_trust), binding.diagnostics, R.string.remote_trust_diagnostics_copied));
            binding.content.addView(copy, topMargin(fixedHeight(context, 34), 8));
        }

        detach(binding.enabled);
        detach(binding.keepOnline);
        LinearLayoutCompat toggles = card(context);
        toggles.addView(binding.enabled, compactWrap());
        toggles.addView(binding.keepOnline, compactWrap());
        binding.content.addView(toggles, topMargin(matchWrap(), 12));

        if (!Setting.hasFileAccess()) {
            MaterialButton permission = outlineAction(binding, context, R.string.remote_trust_file_permission);
            permission.setOnClickListener(v -> requestFileAccess((FragmentActivity) context, binding));
            binding.content.addView(permission, topMargin(fixedHeight(context, 34), 8));
        }

        binding.content.addView(divider(context), topMargin(matchWrap(), 14));
        MaterialButton clear = dangerAction(binding, context, R.string.remote_trust_clear);
        clear.setOnClickListener(v -> confirmClear((FragmentActivity) context, binding));
        binding.content.addView(clear, topMargin(fixedHeight(context, 36), 10));
    }

    private static void saveAndRegister(FragmentActivity activity, Binding binding) {
        RemoteProfile profile;
        try {
            profile = prepare(binding);
        } catch (Throwable e) {
            Notify.show(e.getMessage());
            return;
        }
        setBusy(binding, true);
        Task.execute(() -> {
            try {
                new RemoteClient(profile).register();
                RemoteStore.upsertProfile(profile);
                RemoteAgent.get().start();
                App.post(() -> {
                    setBusy(binding, false);
                    Notify.show(R.string.remote_trust_register_done);
                    render(activity, binding);
                });
            } catch (Throwable e) {
                App.post(() -> {
                    setBusy(binding, false);
                    Notify.show(e.getMessage());
                });
            }
        });
    }

    private static void detectService(FragmentActivity activity, Binding binding) {
        String serverUrl = textOf(binding.server);
        String origin = RemoteTokens.normalizeOrigin(serverUrl);
        if (TextUtils.isEmpty(origin)) {
            Notify.show(R.string.remote_trust_server_required);
            return;
        }
        RemoteProfile probe = new RemoteProfile();
        probe.serverUrl = serverUrl.trim();
        probe.serverOrigin = origin;
        setBusy(binding, true);
        Task.execute(() -> {
            try {
                ServerCapabilities capabilities = new RemoteClient(probe).capabilities();
                String detail = formatCapabilities(activity, capabilities);
                String diagnostics = origin + "/api/server/capabilities\n" + App.gson().toJson(capabilities);
                App.post(() -> {
                    setBusy(binding, false);
                    binding.serviceStateText = activity.getString(R.string.remote_trust_service_ok);
                    binding.serviceDetailText = detail;
                    binding.diagnostics = diagnostics;
                    render(activity, binding);
                });
            } catch (Throwable e) {
                App.post(() -> {
                    setBusy(binding, false);
                    binding.serviceStateText = activity.getString(R.string.remote_trust_service_error);
                    binding.serviceDetailText = activity.getString(R.string.remote_trust_service_failed_with_reason, conciseError(activity, e));
                    binding.diagnostics = origin + "/api/server/capabilities\n" + e.getMessage();
                    render(activity, binding);
                });
            }
        });
    }

    private static void showBindCodeDialog(FragmentActivity activity, Binding binding) {
        if (currentProfile(binding) == null) {
            binding.page = PAGE_SETTINGS;
            render(activity, binding);
            Notify.show(R.string.remote_trust_no_profile);
            return;
        }
        if (TextUtils.isEmpty(binding.bindCode)) {
            createBindCode(activity, binding, true);
            return;
        }
        LinearLayoutCompat root = dialogRoot(activity);
        MaterialTextView code = text(activity, binding.bindCode, 28, "#202124", true);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        root.addView(code, matchWrap());
        root.addView(caption(activity, R.string.remote_trust_bind_code_hint), topMargin(matchWrap(), 8));
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.remote_trust_bind_local_title)
                .setView(root)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setNeutralButton(R.string.remote_trust_refresh_bind_code, (dialog, which) -> createBindCode(activity, binding, true))
                .setPositiveButton(R.string.remote_trust_copy, (dialog, which) -> copyCode(activity, binding))
                .show();
    }

    private static void createBindCode(FragmentActivity activity, Binding binding, boolean reopen) {
        RemoteProfile profile = currentProfile(binding);
        if (profile == null) {
            binding.page = PAGE_SETTINGS;
            render(activity, binding);
            Notify.show(R.string.remote_trust_no_profile);
            return;
        }
        RemoteBindGrant grant = new RemoteBindGrant();
        grant.bindGrantToken = RemoteTokens.randomCapability("bgt");
        grant.grantId = RemoteTokens.bindGrantId(profile.serverOrigin, grant.bindGrantToken);
        grant.createdAt = System.currentTimeMillis();
        RemoteStore.addBindGrant(profile.serverOrigin, grant);
        setBusy(binding, true);
        Task.execute(() -> {
            try {
                RemoteClient client = new RemoteClient(profile);
                client.register();
                BindCodeResponse response = client.createBindCode(grant);
                RemoteStore.upsertProfile(profile);
                RemoteAgent.get().start();
                App.post(() -> {
                    setBusy(binding, false);
                    binding.bindCode = response == null ? "" : response.code;
                    Notify.show(R.string.remote_trust_bind_code_done);
                    render(activity, binding);
                    if (reopen) showBindCodeDialog(activity, binding);
                });
            } catch (Throwable e) {
                App.post(() -> {
                    setBusy(binding, false);
                    Notify.show(e.getMessage());
                });
            }
        });
    }

    private static void showAddDeviceDialog(FragmentActivity activity, Binding binding) {
        RemoteProfile profile = currentProfile(binding);
        if (profile == null) {
            binding.page = PAGE_SETTINGS;
            render(activity, binding);
            Notify.show(R.string.remote_trust_no_profile);
            return;
        }
        LinearLayoutCompat root = dialogRoot(activity);
        TextInputEditText code = input(activity, InputType.TYPE_CLASS_NUMBER, true);
        TextInputEditText alias = input(activity, InputType.TYPE_CLASS_TEXT, true);
        root.addView(inputLayout(activity, R.string.remote_trust_bind_code, code), matchWrap());
        root.addView(inputLayout(activity, R.string.remote_trust_device_alias, alias), topMargin(matchWrap(), 8));
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.remote_trust_add_device_title)
                .setView(root)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.remote_trust_add_device, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = textOf(code);
            if (TextUtils.isEmpty(value)) {
                Notify.show(R.string.remote_trust_code_required);
                return;
            }
            dialog.dismiss();
            addDevice(activity, binding, value, textOf(alias));
        }));
        dialog.show();
    }

    private static void addDevice(FragmentActivity activity, Binding binding, String code, String alias) {
        RemoteProfile profile = currentProfile(binding);
        if (profile == null) {
            Notify.show(R.string.remote_trust_no_profile);
            return;
        }
        String groupToken = firstGroupToken(profile);
        setBusy(binding, true);
        Task.execute(() -> {
            try {
                RemoteClient client = new RemoteClient(profile);
                client.register();
                ClaimResponse response = client.claim(code, groupToken, alias);
                RemoteGroup group = RemoteStore.upsertClaimGroup(profile.serverOrigin, response, alias);
                RemoteProfile updated = RemoteStore.getProfileByOrigin(profile.serverOrigin);
                if (updated != null) {
                    RemoteClient updatedClient = new RemoteClient(updated);
                    updatedClient.register();
                    if (group != null) refreshGroup(updatedClient, updated.serverOrigin, group);
                }
                RemoteAgent.get().start();
                App.post(() -> {
                    setBusy(binding, false);
                    if (response != null) {
                        binding.selectedGroupId = response.groupId;
                        binding.selectedDeviceId = response.deviceId;
                    }
                    binding.page = PAGE_DEVICES;
                    Notify.show(R.string.remote_trust_add_done);
                    render(activity, binding);
                });
            } catch (Throwable e) {
                App.post(() -> {
                    setBusy(binding, false);
                    Notify.show(e.getMessage());
                });
            }
        });
    }

    private static void refreshDevices(FragmentActivity activity, Binding binding) {
        RemoteProfile profile = currentProfile(binding);
        if (profile == null) {
            binding.page = PAGE_SETTINGS;
            render(activity, binding);
            Notify.show(R.string.remote_trust_no_profile);
            return;
        }
        if (profile.groups == null || profile.groups.isEmpty()) {
            Notify.show(R.string.remote_trust_no_group);
            return;
        }
        setBusy(binding, true);
        Task.execute(() -> {
            try {
                RemoteClient client = new RemoteClient(profile);
                client.register();
                int count = 0;
                for (RemoteGroup group : new ArrayList<>(profile.groups)) count += refreshGroup(client, profile.serverOrigin, group);
                RemoteAgent.get().start();
                int refreshed = count;
                App.post(() -> {
                    setBusy(binding, false);
                    Notify.show(activity.getString(R.string.remote_trust_devices_refreshed, refreshed));
                    render(activity, binding);
                });
            } catch (Throwable e) {
                App.post(() -> {
                    setBusy(binding, false);
                    Notify.show(e.getMessage());
                });
            }
        });
    }

    private static int refreshGroup(RemoteClient client, String serverOrigin, RemoteGroup group) throws Exception {
        DevicesResponse response = client.listDevices(group);
        List<RemoteDevice> devices = response == null ? new ArrayList<>() : response.devices;
        RemoteStore.upsertDevices(serverOrigin, group.groupId, devices);
        return devices == null ? 0 : devices.size();
    }

    private static void showTextCommandDialog(FragmentActivity activity, Binding binding, int title, int hint, String type, String payloadKey) {
        TextInputEditText input = input(activity, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true);
        LinearLayoutCompat root = dialogRoot(activity);
        root.addView(inputLayout(activity, hint, input), matchWrap());
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(title)
                .setView(root)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton("action.push".equals(type) ? R.string.remote_trust_send_push : R.string.remote_trust_send_search, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = textOf(input);
            if (TextUtils.isEmpty(value)) {
                Notify.show(hint);
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty(payloadKey, value);
            dialog.dismiss();
            sendCommand(activity, binding, type, payload);
        }));
        dialog.show();
    }

    private static void sendCommand(FragmentActivity activity, Binding binding, String type, JsonObject payload) {
        RemoteProfile profile = currentProfile(binding);
        DeviceRow selected = selectedRow(profile, binding);
        if (profile == null || selected == null) {
            Notify.show(R.string.remote_trust_no_device_selected);
            return;
        }
        setBusy(binding, true);
        binding.lastResult = "";
        Task.execute(() -> {
            try {
                RemoteClient client = new RemoteClient(profile);
                CommandResponse response = client.createCommand(selected.group, selected.device.deviceId, type, payload);
                String commandId = response == null ? "" : response.commandId;
                if (TextUtils.isEmpty(commandId) && response != null && response.command != null) commandId = response.command.id;
                RemoteCommand command = waitCommand(client, selected.group, commandId, response == null ? null : response.command);
                String result = formatCommand(activity, type, command);
                App.post(() -> {
                    setBusy(binding, false);
                    binding.lastResult = result;
                    binding.page = PAGE_DETAIL;
                    render(activity, binding);
                });
            } catch (Throwable e) {
                App.post(() -> {
                    setBusy(binding, false);
                    binding.lastResult = e.getMessage();
                    Notify.show(e.getMessage());
                    render(activity, binding);
                });
            }
        });
    }

    private static RemoteCommand waitCommand(RemoteClient client, RemoteGroup group, String commandId, RemoteCommand fallback) throws Exception {
        if (TextUtils.isEmpty(commandId)) return fallback;
        RemoteCommand command = fallback;
        for (int i = 0; i < 8; i++) {
            Thread.sleep(i == 0 ? 700 : 1000);
            CommandDetailResponse detail = client.getCommand(group, commandId);
            if (detail != null && detail.command != null) command = detail.command;
            if (command != null && ("done".equals(command.status) || "failed".equals(command.status))) break;
        }
        return command;
    }

    private static String formatCommand(Context context, String type, RemoteCommand command) {
        if (command == null) return context.getString(R.string.remote_trust_empty_result);
        StringBuilder builder = new StringBuilder();
        builder.append(context.getString(R.string.remote_trust_command_result, type, TextUtils.isEmpty(command.status) ? "queued" : command.status));
        RemoteCommandResult result = command.result;
        if (result == null) {
            builder.append('\n').append(context.getString(R.string.remote_trust_command_waiting));
            return builder.toString();
        }
        builder.append('\n').append(result.ok ? context.getString(R.string.remote_trust_command_success) : context.getString(R.string.remote_trust_command_failed));
        if (!TextUtils.isEmpty(result.message)) builder.append(": ").append(result.message);
        String data = formatData(result.data);
        if (!TextUtils.isEmpty(data)) builder.append('\n').append(data);
        return builder.toString();
    }

    private static String formatData(JsonElement data) {
        if (data == null || data.isJsonNull()) return "";
        if (data.isJsonObject()) {
            JsonObject object = data.getAsJsonObject();
            if (object.has("lines") && object.get("lines").isJsonArray()) return lines(object.getAsJsonArray("lines"));
        }
        return App.gson().toJson(data);
    }

    private static String lines(JsonArray array) {
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, array.size() - 80);
        for (int i = start; i < array.size(); i++) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(array.get(i).getAsString());
        }
        return builder.toString();
    }

    private static RemoteProfile prepare(Binding binding) {
        String serverUrl = textOf(binding.server);
        return RemoteStore.prepareProfile(serverUrl, binding.enabled.isChecked(), binding.keepOnline.isChecked());
    }

    private static RemoteProfile currentProfile(Binding binding) {
        String serverUrl = textOf(binding.server);
        if (TextUtils.isEmpty(serverUrl)) return RemoteStore.firstProfile();
        String origin = RemoteTokens.normalizeOrigin(serverUrl);
        return TextUtils.isEmpty(origin) ? null : RemoteStore.getProfileByOrigin(origin);
    }

    private static void requestFileAccess(FragmentActivity activity, Binding binding) {
        PermissionUtil.requestFile(activity, granted -> {
            if (granted) RemoteStore.save(RemoteStore.get());
            render(activity, binding);
        });
    }

    private static void confirmClear(FragmentActivity activity, Binding binding) {
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.remote_trust_clear)
                .setMessage(R.string.remote_trust_clear_message)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                    RemoteStore.clear();
                    RemoteAgent.get().stop();
                    RemoteAgentService.stop(activity);
                    binding.initialized = false;
                    binding.bindCode = "";
                    binding.selectedGroupId = "";
                    binding.selectedDeviceId = "";
                    binding.serviceStateText = "";
                    binding.serviceDetailText = "";
                    binding.diagnostics = "";
                    binding.page = PAGE_DEVICES;
                    render(activity, binding);
                })
                .show();
    }

    private static void copyCode(Context context, Binding binding) {
        if (TextUtils.isEmpty(binding.bindCode)) return;
        copyText(context, context.getString(R.string.setting_remote_trust), binding.bindCode, R.string.remote_trust_bind_code_copied);
    }

    private static void copyText(Context context, String label, String text, int message) {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText(label, text));
        Notify.show(message);
    }

    private static String localStatus(Context context, RemoteProfile profile) {
        if (profile == null) return context.getString(R.string.remote_trust_no_profile);
        StringBuilder builder = new StringBuilder();
        builder.append(context.getString(R.string.remote_trust_server_url)).append(": ").append(displayOrigin(profile.serverOrigin));
        builder.append('\n').append(context.getString(R.string.remote_trust_device_id)).append(": ").append(shortId(profile.deviceId));
        builder.append('\n').append(context.getString(R.string.remote_trust_status_summary, profile.keepOnline ? context.getString(R.string.remote_trust_status_online) : context.getString(R.string.remote_trust_status_enabled), 1, profile.groups == null ? 0 : profile.groups.size()));
        return builder.toString();
    }

    private static List<DeviceRow> deviceRows(RemoteProfile profile) {
        List<DeviceRow> rows = new ArrayList<>();
        if (profile == null || profile.groups == null) return rows;
        for (RemoteGroup group : profile.groups) {
            if (group == null || group.devices == null) continue;
            for (RemoteDevice device : group.devices) {
                if (device != null && !TextUtils.isEmpty(device.deviceId)) rows.add(new DeviceRow(group, device));
            }
        }
        return rows;
    }

    private static DeviceRow selectedRow(RemoteProfile profile, Binding binding) {
        if (profile == null) return null;
        for (DeviceRow row : deviceRows(profile)) {
            if (TextUtils.equals(row.group.groupId, binding.selectedGroupId) && TextUtils.equals(row.device.deviceId, binding.selectedDeviceId)) return row;
        }
        return null;
    }

    private static String firstGroupToken(RemoteProfile profile) {
        if (profile == null || profile.groups == null) return "";
        for (RemoteGroup group : profile.groups) if (group != null && !TextUtils.isEmpty(group.groupToken)) return group.groupToken;
        return "";
    }

    private static String deviceText(Context context, RemoteProfile profile, RemoteGroup group, RemoteDevice device) {
        return deviceName(device) + " · " + deviceState(context, device) + deviceTime(device) + "\n" + groupName(context, group) + " · " + shortId(device.deviceId) + selfSuffix(context, profile, device);
    }

    private static String deviceDetailText(Context context, RemoteProfile profile, RemoteGroup group, RemoteDevice device) {
        StringBuilder builder = new StringBuilder();
        builder.append(deviceState(context, device)).append(deviceTime(device));
        builder.append('\n').append(groupName(context, group));
        builder.append('\n').append(context.getString(R.string.remote_trust_device_id)).append(": ").append(shortId(device.deviceId)).append(selfSuffix(context, profile, device));
        if (!TextUtils.isEmpty(device.appVersion)) builder.append('\n').append(context.getString(R.string.remote_trust_app_version)).append(": ").append(device.appVersion);
        return builder.toString();
    }

    private static String deviceName(RemoteDevice device) {
        return TextUtils.isEmpty(device.name) ? shortId(device.deviceId) : device.name;
    }

    private static String deviceState(Context context, RemoteDevice device) {
        return device.online ? context.getString(R.string.remote_trust_device_online) : context.getString(R.string.remote_trust_device_offline);
    }

    private static String deviceTime(RemoteDevice device) {
        return device.lastSeen <= 0 ? "" : " · " + new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(device.lastSeen));
    }

    private static String selfSuffix(Context context, RemoteProfile profile, RemoteDevice device) {
        return profile != null && TextUtils.equals(profile.deviceId, device.deviceId) ? " · " + context.getString(R.string.remote_trust_self_device) : "";
    }

    private static String groupName(Context context, RemoteGroup group) {
        return TextUtils.isEmpty(group.name) ? context.getString(R.string.remote_trust_group_title, shortId(group.groupId)) : group.name;
    }

    private static String formatCapabilities(Context context, ServerCapabilities server) {
        if (server == null) return "";
        RemoteCapabilities capabilities = server.capabilities == null ? new RemoteCapabilities() : server.capabilities;
        List<String> support = new ArrayList<>();
        support.add(context.getString(R.string.remote_trust_cap_device));
        if (capabilities.configManage) support.add(context.getString(R.string.remote_trust_cap_config));
        if (capabilities.remoteSync) support.add(context.getString(R.string.remote_trust_cap_sync));
        if (capabilities.pushAction) support.add(context.getString(R.string.remote_trust_cap_push));
        if (capabilities.recentLog) support.add(context.getString(R.string.remote_trust_cap_log));
        String supportText = support.isEmpty() ? context.getString(R.string.remote_trust_support_none) : TextUtils.join(", ", support);
        return context.getString(R.string.remote_trust_service_info,
                empty(server.serverMode),
                empty(server.relayMode),
                supportText,
                formatBytes(server.maxSyncPartBytes));
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "-";
        long mb = bytes / 1024 / 1024;
        return mb > 0 ? mb + " MB" : bytes + " B";
    }

    private static String empty(String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }

    private static String conciseError(Context context, Throwable e) {
        String message = e == null ? "" : e.getMessage();
        if (TextUtils.isEmpty(message)) return context.getString(R.string.remote_trust_service_request_failed);
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("failed to connect") || lower.contains("timeout") || lower.contains("timed out") || lower.contains("unable to resolve") || lower.contains("connection refused")) {
            return context.getString(R.string.remote_trust_service_connect_failed);
        }
        return shorten(message.replace('\n', ' '), 72);
    }

    private static String displayOrigin(String value) {
        if (TextUtils.isEmpty(value)) return "-";
        String text = value.replace("https://", "").replace("http://", "");
        if (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return shortenMiddle(text, 34);
    }

    private static String shorten(String value, int max) {
        if (TextUtils.isEmpty(value) || value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String shortenMiddle(String value, int max) {
        if (TextUtils.isEmpty(value) || value.length() <= max) return value;
        int keep = Math.max(8, (max - 1) / 2);
        return value.substring(0, keep) + "…" + value.substring(value.length() - keep);
    }

    private static String shortId(String value) {
        if (TextUtils.isEmpty(value)) return "";
        return value.length() <= 8 ? value : value.substring(value.length() - 8);
    }

    private static String textOf(TextInputEditText input) {
        return input == null || input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static void setBusy(Binding binding, boolean busy) {
        binding.busy = busy;
        binding.devicesTab.setEnabled(!busy);
        binding.settingsTab.setEnabled(!busy);
        binding.server.setEnabled(!busy);
        binding.enabled.setEnabled(!busy);
        binding.keepOnline.setEnabled(!busy);
        for (MaterialButton button : binding.actions) button.setEnabled(!busy);
    }

    private static void detach(View view) {
        if (view == null || !(view.getParent() instanceof ViewGroup)) return;
        ((ViewGroup) view.getParent()).removeView(view);
    }

    private static LinearLayoutCompat dialogRoot(Context context) {
        LinearLayoutCompat root = new LinearLayoutCompat(context);
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        root.setPadding(dp(context, 2), dp(context, 4), dp(context, 2), 0);
        return root;
    }

    private static LinearLayoutCompat row(Context context) {
        LinearLayoutCompat row = new LinearLayoutCompat(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        return row;
    }

    private static MaterialTextView sectionTitle(Context context, int resId) {
        return sectionTitle(context, context.getString(resId));
    }

    private static MaterialTextView sectionTitle(Context context, String value) {
        MaterialTextView view = text(context, value, 15, "#202124", true);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private static MaterialTextView caption(Context context, int resId) {
        return text(context, context.getString(resId), 12, "#5F6368", false);
    }

    private static MaterialTextView panel(Context context, String value) {
        MaterialTextView view = text(context, value, 13, "#3C4043", false);
        view.setTextIsSelectable(true);
        view.setLineSpacing(0, 1.08f);
        view.setPadding(dp(context, 10), dp(context, 9), dp(context, 10), dp(context, 9));
        view.setBackground(background(context, "#F8F9FA", Color.parseColor("#DADCE0"), 8));
        return view;
    }

    private static LinearLayoutCompat servicePanel(Context context, String state, String detail) {
        LinearLayoutCompat card = card(context);
        MaterialTextView title = text(context, state, 14, "#202124", true);
        card.addView(title, matchWrap());
        if (!TextUtils.isEmpty(detail)) {
            MaterialTextView text = text(context, detail, 12, "#5F6368", false);
            text.setPadding(0, dp(context, 4), 0, 0);
            text.setMaxLines(5);
            text.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(text, matchWrap());
        }
        return card;
    }

    private static LinearLayoutCompat emptyPanel(Context context, String value) {
        LinearLayoutCompat card = card(context);
        MaterialTextView text = text(context, value, 13, "#5F6368", false);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(context, 8), 0, dp(context, 8));
        card.addView(text, matchWrap());
        return card;
    }

    private static LinearLayoutCompat card(Context context) {
        LinearLayoutCompat view = new LinearLayoutCompat(context);
        view.setOrientation(LinearLayoutCompat.VERTICAL);
        view.setPadding(dp(context, 10), dp(context, 9), dp(context, 10), dp(context, 9));
        view.setBackground(background(context, "#F8F9FA", Color.parseColor("#DADCE0"), 8));
        return view;
    }

    private static MaterialTextView text(Context context, String value, int sp, String color, boolean bold) {
        MaterialTextView view = new MaterialTextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private static TextInputEditText input(Context context, int inputType, boolean singleLine) {
        TextInputEditText input = new TextInputEditText(context);
        input.setInputType(inputType);
        input.setSingleLine(singleLine);
        input.setTextSize(14);
        input.setMinHeight(dp(context, 46));
        return input;
    }

    private static TextInputLayout inputLayout(Context context, int hint, TextInputEditText input) {
        TextInputLayout layout = new TextInputLayout(context);
        layout.setHint(context.getString(hint));
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxCornerRadii(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        layout.addView(input, matchWrap());
        return layout;
    }

    private static com.google.android.material.checkbox.MaterialCheckBox check(Context context, int resId) {
        com.google.android.material.checkbox.MaterialCheckBox box = new com.google.android.material.checkbox.MaterialCheckBox(context);
        box.setText(resId);
        box.setTextSize(14);
        box.setButtonTintList(ContextCompat.getColorStateList(context, R.color.dialog_checkbox_tint));
        box.setPadding(0, 0, 0, 0);
        return box;
    }

    private static MaterialButton tab(Context context, int resId) {
        MaterialButton button = segment(context, context.getString(resId));
        button.setCheckable(true);
        return button;
    }

    private static MaterialButton primaryAction(Binding binding, Context context, int resId) {
        MaterialButton button = primary(context, context.getString(resId));
        bindAction(binding, button);
        return button;
    }

    private static MaterialButton tonalAction(Binding binding, Context context, int resId) {
        MaterialButton button = tonal(context, context.getString(resId));
        bindAction(binding, button);
        return button;
    }

    private static MaterialButton outlineAction(Binding binding, Context context, int resId) {
        MaterialButton button = outline(context, context.getString(resId));
        bindAction(binding, button);
        return button;
    }

    private static MaterialButton dangerAction(Binding binding, Context context, int resId) {
        MaterialButton button = outlineAction(binding, context, resId);
        button.setTextColor(Color.parseColor("#B3261E"));
        button.setStrokeColor(ColorStateList.valueOf(Color.parseColor("#F2B8B5")));
        return button;
    }

    private static MaterialButton smallOutlineAction(Binding binding, Context context, int resId) {
        MaterialButton button = outlineAction(binding, context, resId);
        button.setTextSize(12);
        return button;
    }

    private static MaterialButton button(Context context, int resId) {
        return button(context, context.getString(resId));
    }

    private static MaterialButton button(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(dp(context, 32));
        button.setMinimumHeight(dp(context, 32));
        button.setPadding(dp(context, 6), 0, dp(context, 6), 0);
        button.setMaxLines(2);
        button.setEllipsize(TextUtils.TruncateAt.END);
        return button;
    }

    private static MaterialButton primary(Context context, String text) {
        MaterialButton button = button(context, text);
        button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.dialog_primary_button_bg));
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.dialog_primary_button_text));
        return button;
    }

    private static MaterialButton tonal(Context context, String text) {
        MaterialButton button = button(context, text);
        button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.dialog_tonal_button_bg));
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.dialog_tonal_button_text));
        return button;
    }

    private static MaterialButton outline(Context context, String text) {
        MaterialButton button = button(context, text);
        button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_bg));
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_text));
        button.setStrokeColor(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_stroke));
        button.setStrokeWidth(dp(context, 1));
        return button;
    }

    private static MaterialButton segment(Context context, String text) {
        MaterialButton button = outline(context, text);
        button.setBackgroundTintList(segmentBackground());
        button.setTextColor(segmentText());
        button.setStrokeColor(segmentStroke());
        return button;
    }

    private static MaterialButton closeButton(Context context) {
        MaterialButton button = button(context, "×");
        button.setTextSize(20);
        button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_bg));
        button.setTextColor(Color.parseColor("#5F6368"));
        return button;
    }

    private static MaterialButton listButton(Context context, String text) {
        MaterialButton button = outline(context, text);
        button.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        button.setTextColor(Color.parseColor("#202124"));
        button.setMinHeight(dp(context, 56));
        button.setMaxLines(3);
        return button;
    }

    private static void bindAction(Binding binding, MaterialButton button) {
        binding.actions.add(button);
    }

    private static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(Color.parseColor("#E8EAED"));
        view.setMinimumHeight(dp(context, 1));
        return view;
    }

    private static GradientDrawable background(Context context, String color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(dp(context, radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    private static ColorStateList segmentBackground() {
        return new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_focused},
                new int[]{android.R.attr.state_pressed},
                new int[]{}
        }, new int[]{
                Color.parseColor("#0B57D0"),
                Color.parseColor("#F1F3F4"),
                Color.parseColor("#E8F0FE"),
                Color.parseColor("#E8F0FE"),
                Color.WHITE
        });
    }

    private static ColorStateList segmentText() {
        return new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_enabled},
                new int[]{}
        }, new int[]{
                Color.WHITE,
                Color.parseColor("#9AA0A6"),
                Color.parseColor("#202124")
        });
    }

    private static ColorStateList segmentStroke() {
        return new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{android.R.attr.state_focused},
                new int[]{android.R.attr.state_pressed},
                new int[]{}
        }, new int[]{
                Color.parseColor("#0B57D0"),
                Color.parseColor("#0B57D0"),
                Color.parseColor("#0B57D0"),
                Color.parseColor("#C8CDD2")
        });
    }

    private static LinearLayoutCompat.LayoutParams matchWrap() {
        return new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayoutCompat.LayoutParams weight() {
        return new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private static LinearLayoutCompat.LayoutParams leftWeight(Context context) {
        LinearLayoutCompat.LayoutParams params = weight();
        params.setMarginStart(dp(context, 8));
        return params;
    }

    private static LinearLayoutCompat.LayoutParams leftWeight(Context context, int marginDp) {
        LinearLayoutCompat.LayoutParams params = weight();
        params.setMarginStart(dp(context, marginDp));
        return params;
    }

    private static LinearLayoutCompat.LayoutParams fixed(Context context, int widthDp, int heightDp) {
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(dp(context, widthDp), dp(context, heightDp));
        params.setMarginStart(dp(context, 8));
        return params;
    }

    private static LinearLayoutCompat.LayoutParams fixedHeight(Context context, int heightDp) {
        return new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp));
    }

    private static LinearLayoutCompat.LayoutParams compactWrap() {
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = 0;
        params.bottomMargin = 0;
        return params;
    }

    private static LinearLayoutCompat.LayoutParams topMargin(LinearLayoutCompat.LayoutParams params, int topDp) {
        params.topMargin = dp(App.get(), topDp);
        return params;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class DeviceRow {
        private final RemoteGroup group;
        private final RemoteDevice device;

        private DeviceRow(RemoteGroup group, RemoteDevice device) {
            this.group = group;
            this.device = device;
        }
    }

    private static final class Binding {
        private LinearLayoutCompat root;
        private NestedScrollView scroll;
        private AlertDialog dialog;
        private Runnable callback;
        private LinearLayoutCompat content;
        private MaterialTextView summary;
        private MaterialButton close;
        private MaterialButton devicesTab;
        private MaterialButton settingsTab;
        private TextInputEditText server;
        private TextInputLayout serverLayout;
        private com.google.android.material.checkbox.MaterialCheckBox enabled;
        private com.google.android.material.checkbox.MaterialCheckBox keepOnline;
        private final List<MaterialButton> actions = new ArrayList<>();
        private boolean initialized;
        private boolean busy;
        private int page = PAGE_DEVICES;
        private String bindCode = "";
        private String selectedGroupId = "";
        private String selectedDeviceId = "";
        private String lastResult = "";
        private String serviceStateText = "";
        private String serviceDetailText = "";
        private String diagnostics = "";
    }
}
