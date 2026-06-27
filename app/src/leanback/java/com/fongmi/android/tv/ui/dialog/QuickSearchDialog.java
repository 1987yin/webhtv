package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.DialogQuickSearchBinding;
import com.fongmi.android.tv.ui.adapter.QuickAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class QuickSearchDialog extends BaseAlertDialog implements QuickAdapter.OnClickListener {

    private final List<Vod> pending;
    private DialogQuickSearchBinding binding;
    private QuickAdapter.OnClickListener listener;
    private DialogInterface.OnDismissListener dismissListener;
    private QuickAdapter adapter;
    private int panelWidth;

    public QuickSearchDialog() {
        pending = new ArrayList<>();
    }

    public static QuickSearchDialog create() {
        return new QuickSearchDialog();
    }

    public QuickSearchDialog listener(QuickAdapter.OnClickListener listener) {
        this.listener = listener;
        return this;
    }

    public QuickSearchDialog dismissListener(DialogInterface.OnDismissListener dismissListener) {
        this.dismissListener = dismissListener;
        return this;
    }

    public QuickSearchDialog items(List<Vod> items) {
        pending.addAll(items);
        return this;
    }

    public void addAll(List<Vod> items) {
        if (adapter == null) pending.addAll(items);
        else adapter.addAll(items);
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogQuickSearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        panelWidth = Math.round(ResUtil.getScreenWidth() * 0.42f);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        binding.recycler.setAdapter(adapter = new QuickAdapter(this));
        adapter.setWidth(panelWidth - ResUtil.dp2px(32));
        adapter.setNextFocus(0, 0);
        if (!pending.isEmpty()) {
            adapter.addAll(pending);
            pending.clear();
        }
        binding.recycler.post(() -> focusPosition(0));
        binding.recycler.postDelayed(() -> focusPosition(0), 160);
    }

    private void focusPosition(int position) {
        if (adapter == null || adapter.getItemCount() <= position) return;
        binding.recycler.scrollToPosition(position);
        RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(position);
        if (holder != null) holder.itemView.requestFocus();
        else binding.recycler.requestFocus();
    }

    @Override
    public void onItemClick(Vod item) {
        dismiss();
        if (listener != null) listener.onItemClick(item);
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        if (dismissListener != null) dismissListener.onDismiss(dialog);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable android.os.Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(0);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0f);
        }
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = getDialog() == null ? null : getDialog().getWindow();
        if (window == null) return;
        window.getDecorView().setPadding(0, 0, 0, 0);
        clearParentPadding();
        window.setGravity(Gravity.BOTTOM | Gravity.END);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = panelWidth;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = 0;
        params.y = 0;
        window.setAttributes(params);
        window.setLayout(panelWidth, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void clearParentPadding() {
        View view = binding.getRoot();
        while (view.getParent() instanceof View parent) {
            if (parent instanceof ViewGroup group) group.setPadding(0, 0, 0, 0);
            view = parent;
        }
    }
}
