package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TmdbSeasonOffsetDialog extends AppCompatDialogFragment {

    private final String currentTitle;
    private RecyclerView recycler;
    private TextView empty;
    private OffsetAdapter adapter;

    public static void show(FragmentActivity activity, String currentTitle) {
        // 防止重复弹出：同 tag 的 dialog 已存在(显示中)则不重复 show
        if (activity.getSupportFragmentManager().findFragmentByTag("TmdbSeasonOffsetDialog") != null) {
            return;
        }
        new TmdbSeasonOffsetDialog(currentTitle).show(activity.getSupportFragmentManager(), "TmdbSeasonOffsetDialog");
    }

    public TmdbSeasonOffsetDialog(String currentTitle) {
        this.currentTitle = currentTitle;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_tmdb_season_offset, null);
        recycler = view.findViewById(R.id.recycler);
        empty = view.findViewById(R.id.empty);
        Button add = view.findViewById(R.id.add);
        Button close = view.findViewById(R.id.close);

        adapter = new OffsetAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(getActivity()));
        recycler.setAdapter(adapter);
        refreshList();

        add.setOnClickListener(v -> showAddDialog());
        close.setOnClickListener(v -> dismiss());

        AlertDialog dialog = new AlertDialog.Builder(getActivity()).setView(view).create();
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    private void refreshList() {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> e : Setting.getTmdbSeasonOffsetMap().entrySet()) {
            entries.add(new Entry(e.getKey(), e.getValue()));
        }
        entries.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        adapter.setItems(entries);
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showAddDialog() {
        EditText nameEdit = new EditText(getActivity());
        nameEdit.setHint(R.string.tmdb_season_offset_name_hint);
        nameEdit.setSingleLine(true);
        if (currentTitle != null && !currentTitle.isEmpty()) {
            nameEdit.setText(currentTitle);
            nameEdit.setSelection(currentTitle.length());
        }
        EditText offsetEdit = new EditText(getActivity());
        offsetEdit.setHint(R.string.tmdb_season_offset_value_hint);
        offsetEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        offsetEdit.setSingleLine(true);

        LinearLayout layout = new LinearLayout(getActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(nameEdit);
        layout.addView(offsetEdit);

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.tmdb_season_offset_add_title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String name = nameEdit.getText().toString().trim();
                    String offsetStr = offsetEdit.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(getActivity(), R.string.tmdb_season_offset_name_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int offset;
                    try {
                        offset = Integer.parseInt(offsetStr);
                    } catch (NumberFormatException e) {
                        Toast.makeText(getActivity(), R.string.tmdb_season_offset_value_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Setting.putTmdbSeasonOffset(name, offset);
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static class Entry {
        final String title;
        final int offset;

        Entry(String title, int offset) {
            this.title = title;
            this.offset = offset;
        }
    }

    private class OffsetAdapter extends RecyclerView.Adapter<OffsetAdapter.ItemHolder> {
        private final List<Entry> items = new ArrayList<>();

        void setItems(List<Entry> items) {
            this.items.clear();
            this.items.addAll(items);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public void onBindViewHolder(@NonNull ItemHolder holder, int position) {
            Entry item = items.get(position);
            holder.title.setText(item.title);
            holder.offset.setText(item.offset > 0 ? ("+" + item.offset) : String.valueOf(item.offset));
            holder.delete.setOnClickListener(v -> {
                Setting.removeTmdbSeasonOffset(item.title);
                refreshList();
            });
        }

        @NonNull
        @Override
        public ItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_season_offset, parent, false);
            return new ItemHolder(view);
        }

        class ItemHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView offset;
            final ImageButton delete;

            ItemHolder(View view) {
                super(view);
                title = view.findViewById(R.id.title);
                offset = view.findViewById(R.id.offset);
                delete = view.findViewById(R.id.delete);
            }
        }
    }
}
