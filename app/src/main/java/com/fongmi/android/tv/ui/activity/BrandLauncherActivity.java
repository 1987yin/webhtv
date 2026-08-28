package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Launcher entry that only exists to carry a per-branding startup window.
 *
 * The system draws the starting window from the launched component's manifest theme before the
 * process runs, and {@code <activity-alias>} cannot declare {@code android:theme}. So each branding
 * needs its own real launcher activity, which hands the task over to {@link HomeActivity} at once.
 */
public abstract class BrandLauncherActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, HomeActivity.class));
        finish();
        overridePendingTransition(0, 0);
    }
}
