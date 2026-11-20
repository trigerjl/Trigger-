package com.triger.poc;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

/** Современное нижнее окно «О приложении» с иконкой, версией и описанием. */
public class AboutSheetDialogFragment extends BottomSheetDialogFragment {

    private int debugTapCount;

    public static AboutSheetDialogFragment newInstance() {
        return new AboutSheetDialogFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        setStyle(DialogFragment.STYLE_NORMAL, R.style.Theme_Triger_BottomSheet);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null) {
            return;
        }
        Window window = getDialog().getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);
        }
        View sheet = getDialog().findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            sheet.setBackgroundResource(R.drawable.bg_bottom_sheet_dark);
            ViewGroup.LayoutParams params = sheet.getLayoutParams();
            if (params != null) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                sheet.setLayoutParams(params);
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_about_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView tvName = view.findViewById(R.id.tv_about_name);
        TextView tvVersion = view.findViewById(R.id.tv_about_version);
        MaterialButton btnClose = view.findViewById(R.id.btn_about_close);

        tvName.setText(getString(R.string.app_name));
        tvVersion.setText(getString(R.string.version_format, appVersion()));
        btnClose.setOnClickListener(v -> dismiss());
        view.setOnClickListener(v -> onDebugTap());
    }

    private void onDebugTap() {
        debugTapCount++;
        if (debugTapCount < 7) {
            return;
        }
        debugTapCount = 0;
        dismiss();
        startActivity(new Intent(requireContext(), DebugActivity.class));
    }

    private String appVersion() {
        if (getContext() == null) {
            return "1.0";
        }
        try {
            PackageInfo info = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0);
            return info.versionName == null ? "1.0" : info.versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }
}
