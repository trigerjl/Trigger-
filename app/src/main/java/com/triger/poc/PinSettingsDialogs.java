package com.triger.poc;

import android.content.Context;

import androidx.fragment.app.FragmentActivity;

/** Точка входа к нижнему окну управления PIN-кодом из настроек. */
public final class PinSettingsDialogs {

    private PinSettingsDialogs() {
    }

    public static void show(Context context, Runnable onChanged) {
        if (!(context instanceof FragmentActivity)) {
            return;
        }
        FragmentActivity activity = (FragmentActivity) context;
        int mode = PinManager.isPinSet(context)
                ? PinSheetDialogFragment.MODE_MANAGE
                : PinSheetDialogFragment.MODE_SETUP;
        PinSheetDialogFragment sheet = PinSheetDialogFragment.newInstance(mode);
        sheet.setCallback((pinSet, skipped) -> {
            if (onChanged != null) {
                onChanged.run();
            }
        });
        sheet.show(activity.getSupportFragmentManager(), "pin_settings");
    }
}
