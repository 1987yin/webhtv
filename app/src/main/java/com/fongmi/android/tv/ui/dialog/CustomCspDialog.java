package com.fongmi.android.tv.ui.dialog;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.databinding.AdapterCustomCspBinding;
import com.fongmi.android.tv.databinding.DialogCustomCspBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CustomCspDialog extends BaseAlertDialog {

    private static final int MIN_INSERT_INDEX = 0;
    private static final int MAX_INSERT_INDEX = 9;

    private DialogCustomCspBinding binding;
    private CustomCspSetting.Registry registry;
    private CspAdapter adapter;
    private CustomCspSetting.Item pendingImport;
    private Runnable callback;
    private boolean enabled;
    private boolean textMode;
    private boolean saved;
    private long lastAddTime;

    public static void show(Fragment fragment, Runnable callback) {
        CustomCspDialog dialog = new CustomCspDialog();
        dialog.callback = callback;
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        CustomCspDialog dialog = new CustomCspDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogCustomCspBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null) return;
        Window window = getDialog().getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        int screenHeight = ResUtil.getScreenHeight(requireContext());
        boolean land = ResUtil.isLand(requireContext());
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        params.width = (int) (screenWidth * (land ? 0.76f : 0.94f));
        params.height = land ? (int) (screenHeight * 0.98f) : WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        ViewGroup.LayoutParams rootParams = binding.root.getLayoutParams();
        rootParams.height = land ? params.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        binding.root.setLayoutParams(rootParams);
        LinearLayoutCompat.LayoutParams scrollParams = (LinearLayoutCompat.LayoutParams) binding.contentScroll.getLayoutParams();
        scrollParams.height = land ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        scrollParams.weight = land ? 1 : 0;
        binding.contentScroll.setLayoutParams(scrollParams);
        binding.contentScroll.setMaxHeight(land ? 0 : (int) (screenHeight * 0.58f));
        binding.enabled.requestFocus();
    }

    @Override
    protected void initView() {
        registry = CustomCspSetting.load();
        adapter = new CspAdapter(new ArrayList<>(registry.getItems()));
        enabled = registry.isEnabled();
        updateEnabledText();
        setInsertIndex(registry.getInsertIndex());
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(adapter);
        binding.modeGroup.check(R.id.uiMode);
        syncJsonFromForm();
        showTextMode(false);
    }

    @Override
    protected void initEvent() {
        binding.enabled.setOnClickListener(view -> {
            enabled = !enabled;
            updateEnabledText();
        });
        binding.insertMinus.setOnClickListener(view -> changeInsertIndex(-1));
        binding.insertPlus.setOnClickListener(view -> changeInsertIndex(1));
        binding.modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.textMode) showTextMode(true);
            if (checkedId == R.id.uiMode && !showTextMode(false)) binding.modeGroup.check(R.id.textMode);
        });
        setupScrollableText(binding.jsonText);
        binding.add.setOnClickListener(view -> addItem());
        binding.negative.setOnClickListener(view -> closeAndSave(false));
        binding.positive.setOnClickListener(view -> onPositive());
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        save(false);
        super.onCancel(dialog);
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        save(false);
        super.onDismiss(dialog);
    }

    private void updateEnabledText() {
        binding.enabled.setText(enabled ? R.string.setting_enable : R.string.setting_disable);
        binding.enabled.setAlpha(enabled ? 1.0f : 0.65f);
    }

    private void setupScrollableText(EditText input) {
        input.setSelectAllOnFocus(false);
        input.setHorizontallyScrolling(true);
        input.setHorizontalScrollBarEnabled(true);
        input.setVerticalScrollBarEnabled(true);
        input.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.post(() -> disallowParentIntercept(view, false));
            } else {
                disallowParentIntercept(view, true);
            }
            return false;
        });
    }

    private void disallowParentIntercept(View view, boolean disallow) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    private void changeInsertIndex(int delta) {
        setInsertIndex(getInsertIndex() + delta);
    }

    private void setInsertIndex(int index) {
        int value = clampInsertIndex(index);
        binding.insertIndex.setText(String.valueOf(value + 1));
        binding.insertMinus.setAlpha(value > MIN_INSERT_INDEX ? 1.0f : 0.45f);
        binding.insertPlus.setAlpha(value < MAX_INSERT_INDEX ? 1.0f : 0.45f);
    }

    private boolean showTextMode(boolean text) {
        if (text == textMode) {
            updateModeVisibility();
            return true;
        }
        if (text) syncJsonFromForm();
        else if (!syncFormFromJson(true)) return false;
        textMode = text;
        updateModeVisibility();
        return true;
    }

    private void updateModeVisibility() {
        binding.recycler.setVisibility(textMode ? View.GONE : View.VISIBLE);
        binding.jsonLayout.setVisibility(textMode ? View.VISIBLE : View.GONE);
        binding.add.setVisibility(textMode ? View.GONE : View.VISIBLE);
    }

    private void addItem() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAddTime < 500) return;
        lastAddTime = now;
        CustomCspSetting.Item item = CustomCspSetting.createDefaultItem();
        item.setName(nextName(true));
        adapter.add(item);
        binding.recycler.scrollToPosition(adapter.getItemCount() - 1);
    }

    private String nextName(boolean webHome) {
        String prefix = webHome ? getString(R.string.setting_custom_csp_webhome) : getString(R.string.setting_custom_csp_common);
        int max = 0;
        for (CustomCspSetting.Item item : adapter.getItems()) {
            if (item.isWebHome() != webHome) continue;
            String name = item.getName();
            if (name.equals(prefix)) max = Math.max(max, 1);
            else if (name.startsWith(prefix + " ")) max = Math.max(max, parseInt(name.substring(prefix.length() + 1), 0));
        }
        int next = Math.max(1, max + 1);
        return getString(webHome ? R.string.setting_custom_csp_webhome_name : R.string.setting_custom_csp_common_name, next);
    }

    private boolean onPositive() {
        return closeAndSave(true);
    }

    private boolean closeAndSave(boolean validate) {
        if (!save(validate)) return false;
        focusBeforeDismiss();
        dismiss();
        return true;
    }

    private void focusBeforeDismiss() {
        if (binding == null) return;
        View focus = binding.root.findFocus();
        if (focus != null) focus.clearFocus();
        binding.positive.requestFocus();
    }

    private void focusBeforeRemove(View removed) {
        if (binding == null || removed == null) return;
        View focus = binding.root.findFocus();
        if (isDescendant(focus, removed)) {
            focus.clearFocus();
            binding.add.requestFocus();
        }
    }

    private boolean isDescendant(View child, View parent) {
        if (child == null || parent == null) return false;
        if (child == parent) return true;
        ViewParent viewParent = child.getParent();
        while (viewParent instanceof View) {
            if (viewParent == parent) return true;
            viewParent = viewParent.getParent();
        }
        return false;
    }

    private boolean save(boolean validate) {
        if (saved) return true;
        if (textMode && !syncFormFromJson(validate)) {
            if (validate) return false;
            saved = true;
            return true;
        }
        syncAllVisibleRows();
        registry.setEnabled(enabled);
        registry.setInsertIndex(getInsertIndex());
        registry.setItems(new ArrayList<>(adapter.getItems()));
        CustomCspSetting.save(registry);
        reloadVodConfig();
        if (callback != null) callback.run();
        saved = true;
        return true;
    }

    private void reloadVodConfig() {
        VodConfig.get().clear().config(VodConfig.get().getConfig()).load(new Callback() {
        });
    }

    private void syncJsonFromForm() {
        syncAllVisibleRows();
        registry.setEnabled(enabled);
        registry.setInsertIndex(getInsertIndex());
        registry.setItems(new ArrayList<>(adapter.getItems()));
        binding.jsonText.setText(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(registry.normalize()));
    }

    private boolean syncFormFromJson(boolean validate) {
        String text = binding.jsonText.getText() == null ? "" : binding.jsonText.getText().toString().trim();
        try {
            registry = TextUtils.isEmpty(text) ? new CustomCspSetting.Registry() : CustomCspSetting.parse(text);
        } catch (Exception e) {
            if (validate) Notify.show(R.string.setting_custom_csp_json_invalid);
            return false;
        }
        adapter.setItems(new ArrayList<>(registry.getItems()));
        enabled = registry.isEnabled();
        updateEnabledText();
        setInsertIndex(registry.getInsertIndex());
        return true;
    }

    private int getInsertIndex() {
        try {
            return clampInsertIndex(Integer.parseInt(binding.insertIndex.getText().toString().trim()) - 1);
        } catch (Exception e) {
            return MIN_INSERT_INDEX;
        }
    }

    private int clampInsertIndex(int index) {
        return Math.max(MIN_INSERT_INDEX, Math.min(MAX_INSERT_INDEX, index));
    }

    private void syncAllVisibleRows() {
        for (int i = 0; i < binding.recycler.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = binding.recycler.getChildViewHolder(binding.recycler.getChildAt(i));
            if (holder instanceof CspAdapter.ViewHolder viewHolder) viewHolder.sync();
        }
    }

    private static void setText(EditText view, String text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private void chooseFile(CustomCspSetting.Item item) {
        syncAllVisibleRows();
        pendingImport = item;
        FileChooser.from(launcher).show("text/html", new String[]{"text/html", "text/*", "application/octet-stream"});
    }

    private void editCode(CustomCspSetting.Item item) {
        syncAllVisibleRows();
        TextInputEditText input = createInput(true);
        input.setMinLines(8);
        input.setMaxLines(14);
        input.setText(Path.read(CustomCspSetting.file(item.getId(), "index.html")));
        setupScrollableText(input);
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.setting_custom_csp_code)
                .setView(createInputPanel(R.string.setting_custom_csp_code, input))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> saveCode(item, input.getText().toString()))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void editLink(CustomCspSetting.Item item) {
        syncAllVisibleRows();
        TextInputEditText input = createInput(false);
        input.setText(item.getHomePage());
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.setting_custom_csp_link)
                .setView(createInputPanel(R.string.setting_custom_csp_link, input))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    item.setHomePage(input.getText().toString().trim());
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private TextInputEditText createInput(boolean multiline) {
        TextInputEditText input = new TextInputEditText(requireContext());
        input.setSelectAllOnFocus(false);
        input.setSingleLine(!multiline);
        input.setTextColor(Color.BLACK);
        input.setHintTextColor(Color.parseColor("#666666"));
        input.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(multiline ? Gravity.START | Gravity.TOP : Gravity.CENTER_VERTICAL);
        return input;
    }

    private View createInputPanel(int hint, TextInputEditText input) {
        LinearLayoutCompat container = new LinearLayoutCompat(requireContext());
        container.setOrientation(LinearLayoutCompat.VERTICAL);
        container.setPadding(ResUtil.dp2px(20), ResUtil.dp2px(8), ResUtil.dp2px(20), 0);
        TextInputLayout layout = new TextInputLayout(requireContext());
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxBackgroundColor(Color.WHITE);
        layout.setBoxStrokeColor(ResUtil.getColor(R.color.dialog_outlined_button_stroke));
        layout.setHintTextColor(ColorStateList.valueOf(Color.parseColor("#5F6368")));
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(layout, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private void saveCode(CustomCspSetting.Item item, String code) {
        Path.write(CustomCspSetting.file(item.getId(), "index.html"), code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        item.setHomePage(CustomCspSetting.localUrl(item.getId(), "index.html"));
        adapter.notifyDataSetChanged();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null || pendingImport == null) return;
        String path = FileChooser.getPathFromUri(result.getData().getData());
        if (TextUtils.isEmpty(path)) return;
        Path.copy(Path.local(path), CustomCspSetting.file(pendingImport.getId(), "index.html"));
        pendingImport.setHomePage(CustomCspSetting.localUrl(pendingImport.getId(), "index.html"));
        pendingImport = null;
        adapter.notifyDataSetChanged();
    });

    private class CspAdapter extends RecyclerView.Adapter<CspAdapter.ViewHolder> {

        private final List<CustomCspSetting.Item> items;

        CspAdapter(List<CustomCspSetting.Item> items) {
            this.items = items;
        }

        List<CustomCspSetting.Item> getItems() {
            return items;
        }

        void add(CustomCspSetting.Item item) {
            items.add(item);
            notifyItemInserted(items.size() - 1);
        }

        void setItems(List<CustomCspSetting.Item> items) {
            this.items.clear();
            this.items.addAll(items);
            notifyDataSetChanged();
        }

        void move(int from, int to) {
            if (from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
            syncAllVisibleRows();
            Collections.swap(items, from, to);
            notifyItemMoved(from, to);
        }

        void remove(int position, View removed) {
            if (position < 0 || position >= items.size()) return;
            syncAllVisibleRows();
            focusBeforeRemove(removed);
            CustomCspSetting.Item item = items.remove(position);
            if (item.site().getKey().equals(registry.getHomeKey())) registry.setHomeKey("");
            notifyItemRemoved(position);
        }

        void setHome(CustomCspSetting.Item item) {
            syncAllVisibleRows();
            String key = item.site().getKey();
            registry.setHomeKey(key.equals(registry.getHomeKey()) ? "" : key);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterCustomCspBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        private class ViewHolder extends RecyclerView.ViewHolder {

            private final AdapterCustomCspBinding binding;
            private CustomCspSetting.Item item;
            private boolean bindingItem;
            private boolean autoName;

            ViewHolder(@NonNull AdapterCustomCspBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
                binding.name.addTextChangedListener(new TextSync(this));
                binding.key.addTextChangedListener(new TextSync(this));
                binding.type.addTextChangedListener(new TextSync(this));
                binding.api.addTextChangedListener(new TextSync(this));
                binding.homePage.addTextChangedListener(new TextSync(this));
                binding.ext.addTextChangedListener(new TextSync(this));
                binding.jar.addTextChangedListener(new TextSync(this));
                binding.click.addTextChangedListener(new TextSync(this));
                binding.playUrl.addTextChangedListener(new TextSync(this));
                binding.enabled.setOnClickListener(view -> toggleEnabled());
                binding.home.setOnCheckedChangeListener((button, checked) -> onHomeChecked(checked));
                binding.typeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> onTypeChecked(checkedId, isChecked));
                binding.hide.setOnCheckedChangeListener((button, checked) -> sync());
                binding.searchable.setOnCheckedChangeListener((button, checked) -> sync());
                binding.changeable.setOnCheckedChangeListener((button, checked) -> sync());
                binding.quickSearch.setOnCheckedChangeListener((button, checked) -> sync());
                binding.importFile.setOnClickListener(view -> chooseFile(item));
                binding.code.setOnClickListener(view -> editCode(item));
                binding.link.setOnClickListener(view -> editLink(item));
                binding.up.setOnClickListener(view -> move(getBindingAdapterPosition(), getBindingAdapterPosition() - 1));
                binding.down.setOnClickListener(view -> move(getBindingAdapterPosition(), getBindingAdapterPosition() + 1));
                binding.delete.setOnClickListener(view -> remove(getBindingAdapterPosition(), itemView));
            }

            void bind(CustomCspSetting.Item item) {
                this.item = item;
                bindingItem = true;
                autoName = isAutoName(item.getName(), item.isWebHome());
                binding.enabled.setAlpha(item.isEnabled() ? 1.0f : 0.65f);
                binding.enabled.setText(item.isEnabled() ? R.string.setting_enable : R.string.setting_disable);
                binding.typeGroup.check(item.isWebHome() ? R.id.webHomeMode : R.id.cspMode);
                setText(binding.name, item.getName());
                setText(binding.key, item.getKey());
                setText(binding.type, String.valueOf(item.getType()));
                setText(binding.api, item.getApi());
                setText(binding.homePage, item.getHomePage());
                setText(binding.ext, item.getExt());
                setText(binding.jar, item.getJar());
                setText(binding.click, item.getClick());
                setText(binding.playUrl, item.getPlayUrl());
                binding.hide.setChecked(item.getHide() == 1);
                binding.searchable.setChecked(item.getSearchable() == 1);
                binding.changeable.setChecked(item.getChangeable() == 1);
                binding.quickSearch.setChecked(item.getQuickSearch() == 1);
                boolean home = item.site().getKey().equals(registry.getHomeKey());
                binding.home.setChecked(home);
                updateTypePanels();
                updateValidity();
                bindingItem = false;
            }

            private void toggleEnabled() {
                if (item == null) return;
                boolean checked = !item.isEnabled();
                item.setEnabled(checked);
                binding.enabled.setAlpha(checked ? 1.0f : 0.65f);
                binding.enabled.setText(checked ? R.string.setting_enable : R.string.setting_disable);
            }

            private void onHomeChecked(boolean checked) {
                if (bindingItem || item == null) return;
                if (checked != item.site().getKey().equals(registry.getHomeKey())) setHome(item);
            }

            private void onTypeChecked(int checkedId, boolean isChecked) {
                if (bindingItem || item == null || !isChecked) return;
                boolean oldWebHome = item.isWebHome();
                boolean newWebHome = checkedId == R.id.webHomeMode;
                item.setWebHome(newWebHome);
                if (oldWebHome != newWebHome && autoName) {
                    String name = nextName(newWebHome);
                    item.setName(name);
                    setText(binding.name, name);
                }
                updateTypePanels();
                updateValidity();
            }

            private void updateTypePanels() {
                boolean webHome = item != null && item.isWebHome();
                binding.webHomePanel.setVisibility(webHome ? View.VISIBLE : View.GONE);
                binding.apiLayout.setVisibility(webHome ? View.GONE : View.VISIBLE);
                binding.homePageLayout.setVisibility(webHome ? View.VISIBLE : View.GONE);
                binding.cspOptionsPanel.setVisibility(webHome ? View.GONE : View.VISIBLE);
                binding.flagsPanel.setVisibility(webHome ? View.GONE : View.VISIBLE);
                binding.advancedPanel.setVisibility(webHome ? View.GONE : View.VISIBLE);
                binding.playPanel.setVisibility(webHome ? View.GONE : View.VISIBLE);
            }

            void sync() {
                if (item == null || bindingItem) return;
                String name = binding.name.getText().toString().trim();
                autoName = autoName || isAutoName(item.getName(), item.isWebHome());
                if (!name.equals(item.getName())) autoName = false;
                item.setName(name);
                if (!item.isWebHome()) {
                    item.setKey(binding.key.getText().toString().trim());
                    item.setType(parseInt(binding.type.getText().toString(), 3));
                    item.setApi(binding.api.getText().toString().trim());
                    item.setHide(binding.hide.isChecked() ? 1 : 0);
                    item.setSearchable(binding.searchable.isChecked() ? 1 : 0);
                    item.setChangeable(binding.changeable.isChecked() ? 1 : 0);
                    item.setQuickSearch(binding.quickSearch.isChecked() ? 1 : 0);
                }
                if (!item.isWebHome()) {
                    item.setHomePage("");
                    item.setExt(binding.ext.getText().toString().trim());
                    item.setJar(binding.jar.getText().toString().trim());
                    item.setClick(binding.click.getText().toString().trim());
                    item.setPlayUrl(binding.playUrl.getText().toString().trim());
                } else {
                    item.setHomePage(binding.homePage.getText().toString().trim());
                    item.setClick("");
                    item.setPlayUrl("");
                }
                updateValidity();
            }

            private boolean isAutoName(String name, boolean webHome) {
                String prefix = webHome ? getString(R.string.setting_custom_csp_webhome) : getString(R.string.setting_custom_csp_common);
                if (TextUtils.isEmpty(name)) return true;
                if (name.equals(prefix)) return true;
                return name.matches(java.util.regex.Pattern.quote(prefix) + " \\d+");
            }

            private void updateValidity() {
                if (item == null) return;
                boolean invalid = item.isEnabled() && !item.isValid();
                binding.getRoot().setActivated(invalid);
            }
        }
    }

    private int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static class TextSync extends CustomTextListener {

        private final CspAdapter.ViewHolder holder;

        TextSync(CspAdapter.ViewHolder holder) {
            this.holder = holder;
        }

        @Override
        public void afterTextChanged(Editable editable) {
            holder.sync();
        }
    }
}
