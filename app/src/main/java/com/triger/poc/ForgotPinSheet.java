package com.triger.poc;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/** Сброс забытого PIN-кода после подтверждения приватным ключом. */
public class ForgotPinSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onPinReset();
    }

    private Listener listener;

    public static ForgotPinSheet newInstance() {
        return new ForgotPinSheet();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_forgot_pin, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        EditText keyInput = view.findViewById(R.id.et_recovery_private_key);
        ImageButton toggleKey = view.findViewById(R.id.btn_toggle_recovery_key);
        TextView errorView = view.findViewById(R.id.tv_forgot_pin_error);
        MaterialButton resetButton = view.findViewById(R.id.btn_reset_pin);

        toggleKey.setOnClickListener(v -> {
            boolean hidden = keyInput.getTransformationMethod()
                    instanceof PasswordTransformationMethod;
            keyInput.setTransformationMethod(hidden
                    ? null
                    : PasswordTransformationMethod.getInstance());
            toggleKey.setImageResource(hidden
                    ? R.drawable.ic_visibility_off
                    : R.drawable.ic_visibility);
            toggleKey.setContentDescription(hidden
                    ? getString(R.string.private_key_hide)
                    : getString(R.string.private_key_show));
            keyInput.setSelection(keyInput.getText().length());
        });

        keyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                errorView.setVisibility(View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        keyInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                resetButton.performClick();
                return true;
            }
            return false;
        });

        resetButton.setOnClickListener(v -> resetPin(keyInput, errorView));

        view.findViewById(R.id.btn_forgot_pin_close).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btn_forgot_pin_cancel).setOnClickListener(v -> dismiss());
    }

    private void resetPin(EditText keyInput, TextView errorView) {
        String entered = keyInput.getText() == null ? "" : keyInput.getText().toString();
        if (entered.trim().isEmpty()) {
            showError(errorView, getString(R.string.error_enter_private_key));
            return;
        }
        String stored = KeyManager.getPrivateKeyHex(requireContext());
        if (stored == null || stored.trim().isEmpty()) {
            showError(errorView, getString(R.string.error_private_key_not_found));
            return;
        }
        if (!normalizeKey(entered).equals(normalizeKey(stored))) {
            showError(errorView, getString(R.string.error_private_key_invalid));
            keyInput.selectAll();
            return;
        }

        PinManager.clearPin(requireContext());
        Toast.makeText(requireContext(), R.string.pin_reset_toast, Toast.LENGTH_SHORT).show();
        Listener callback = listener;
        dismissAllowingStateLoss();
        if (callback != null) {
            callback.onPinReset();
        }
    }

    private static void showError(TextView errorView, String message) {
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        String key = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (key.startsWith("0x")) {
            key = key.substring(2);
        }
        return key;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        }
    }
}
