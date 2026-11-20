package com.triger.poc;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.HashSet;
import java.util.Set;

/**
 * Нижнее окно для установки, смены и удаления PIN-кода.
 */
public class PinSheetDialogFragment extends BottomSheetDialogFragment {

    public static final int MODE_SETUP = 0;
    public static final int MODE_MANAGE = 1;

    private static final String ARG_MODE = "mode";

    public interface Callback {
        /**
         * @param pinSet  true, если PIN установлен/изменён.
         * @param skipped true, если пользователь отказался от установки.
         */
        void onResult(boolean pinSet, boolean skipped);
    }

    private int mode = MODE_SETUP;
    private Callback callback;

    private View flCurrent;
    private EditText etCurrent;
    private EditText etNew;
    private EditText etConfirm;
    private ImageButton btnToggleCurrent;
    private ImageButton btnToggleNew;
    private ImageButton btnToggleConfirm;
    private TextView tvError;
    private TextView tvWarning;
    private MaterialButton btnSave;
    private MaterialButton btnSecondary;

    private View llStrength;
    private View[] strengthBars;
    private TextView tvStrength;
    private ImageView ivReqLength;
    private TextView tvReqLength;
    private ImageView ivReqRepeat;
    private TextView tvReqRepeat;

    public static PinSheetDialogFragment newInstance(int mode) {
        PinSheetDialogFragment fragment = new PinSheetDialogFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_MODE, mode);
        fragment.setArguments(args);
        return fragment;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        setStyle(STYLE_NORMAL, R.style.Triger_BottomSheetDialog);
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            mode = args.getInt(ARG_MODE, MODE_SETUP);
        }
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
            sheet.setBackgroundResource(android.R.color.transparent);
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
        return inflater.inflate(R.layout.dialog_pin_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView tvTitle = view.findViewById(R.id.tv_pin_sheet_title);
        TextView tvSubtitle = view.findViewById(R.id.tv_pin_sheet_subtitle);
        flCurrent = view.findViewById(R.id.fl_pin_current);
        etCurrent = view.findViewById(R.id.et_pin_current);
        etNew = view.findViewById(R.id.et_pin_new);
        etConfirm = view.findViewById(R.id.et_pin_confirm);
        btnToggleCurrent = view.findViewById(R.id.btn_toggle_current);
        btnToggleNew = view.findViewById(R.id.btn_toggle_new);
        btnToggleConfirm = view.findViewById(R.id.btn_toggle_confirm);
        tvError = view.findViewById(R.id.tv_pin_sheet_error);
        tvWarning = view.findViewById(R.id.tv_pin_warning);
        btnSave = view.findViewById(R.id.btn_pin_save);
        btnSecondary = view.findViewById(R.id.btn_pin_secondary);
        MaterialButton btnCancel = view.findViewById(R.id.btn_pin_cancel);

        llStrength = view.findViewById(R.id.ll_pin_strength);
        strengthBars = new View[]{
                view.findViewById(R.id.view_strength_1),
                view.findViewById(R.id.view_strength_2),
                view.findViewById(R.id.view_strength_3),
                view.findViewById(R.id.view_strength_4)
        };
        tvStrength = view.findViewById(R.id.tv_pin_strength);
        ivReqLength = view.findViewById(R.id.iv_req_length);
        tvReqLength = view.findViewById(R.id.tv_req_length);
        ivReqRepeat = view.findViewById(R.id.iv_req_repeat);
        tvReqRepeat = view.findViewById(R.id.tv_req_repeat);

        setupPasswordToggle(etCurrent, btnToggleCurrent);
        setupPasswordToggle(etNew, btnToggleNew);
        setupPasswordToggle(etConfirm, btnToggleConfirm);

        boolean manage = mode == MODE_MANAGE;
        if (manage) {
            tvTitle.setText(R.string.pin_change_title);
            tvSubtitle.setText(R.string.pin_security_hint);
            flCurrent.setVisibility(View.VISIBLE);
            etNew.setHint(R.string.pin_new_hint);
            etConfirm.setHint(R.string.pin_confirm_hint);
            btnSave.setText(R.string.pin_change_action);
            btnSecondary.setText(R.string.action_delete);
            btnSecondary.setTextColor(requireContext().getColor(R.color.danger_red));
            tvWarning.setVisibility(View.GONE);
        } else {
            tvTitle.setText(R.string.pin_setup_title);
            tvSubtitle.setText(R.string.pin_security_hint);
            flCurrent.setVisibility(View.GONE);
            etNew.setHint(R.string.pin_label);
            etConfirm.setHint(R.string.pin_confirm_hint);
            btnSave.setText(R.string.pin_save_action);
            btnSecondary.setText(R.string.action_skip);
            btnSecondary.setTextColor(requireContext().getColor(R.color.text_secondary_dark));
            tvWarning.setVisibility(View.VISIBLE);
        }

        btnSave.setOnClickListener(v -> {
            if (manage) {
                saveManagedPin();
            } else {
                saveSetupPin();
            }
        });
        btnSecondary.setOnClickListener(v -> {
            if (manage) {
                deleteManagedPin();
            } else {
                confirmSkip();
            }
        });
        btnCancel.setOnClickListener(v -> dismiss());

        etConfirm.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnSave.performClick();
                return true;
            }
            return false;
        });
        clearErrorOnEdit(etCurrent);
        clearErrorOnEdit(etNew);
        clearErrorOnEdit(etConfirm);

        etNew.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateStrength(valueOf(etNew));
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        updateStrength(valueOf(etNew));

        if (manage) {
            etCurrent.requestFocus();
        } else {
            etNew.requestFocus();
        }
    }

    private void setupPasswordToggle(EditText input, ImageButton toggle) {
        if (input == null || toggle == null) {
            return;
        }
        toggle.setOnClickListener(v -> {
            boolean hidden = input.getTransformationMethod()
                    instanceof PasswordTransformationMethod;
            input.setTransformationMethod(hidden
                    ? null
                    : PasswordTransformationMethod.getInstance());
            toggle.setImageResource(hidden
                    ? R.drawable.ic_visibility_off
                    : R.drawable.ic_visibility);
            toggle.setContentDescription(hidden
                    ? getString(R.string.pin_hide)
                    : getString(R.string.pin_show));
            input.setSelection(input.getText().length());
        });
    }

    private void updateStrength(String pin) {
        if (llStrength == null || strengthBars == null) {
            return;
        }
        if (TextUtils.isEmpty(pin)) {
            llStrength.setVisibility(View.GONE);
            updateRequirements("");
            return;
        }

        llStrength.setVisibility(View.VISIBLE);

        int length = pin.length();
        int score = 0;
        if (length >= 4) {
            score++;
        }
        if (length >= 6) {
            score++;
        }
        if (hasUniqueDigits(pin)) {
            score++;
        }
        if (length >= 4 && !isEasyPattern(pin)) {
            score++;
        }

        int filled = Math.max(1, score);
        int activeColor;
        String label;
        if (score <= 1) {
            activeColor = requireContext().getColor(R.color.danger_red);
            label = getString(R.string.pin_strength_weak);
        } else if (score <= 3) {
            activeColor = requireContext().getColor(R.color.accent_orange);
            label = getString(R.string.pin_strength_medium);
        } else {
            activeColor = requireContext().getColor(R.color.accent_purple);
            label = getString(R.string.pin_strength_strong);
        }

        int trackColor = requireContext().getColor(R.color.pin_strength_track);
        for (int i = 0; i < strengthBars.length; i++) {
            strengthBars[i].setBackgroundTintList(ColorStateList.valueOf(
                    i < filled ? activeColor : trackColor));
        }
        tvStrength.setText(getString(R.string.pin_strength_format, label));
        tvStrength.setTextColor(activeColor);

        updateRequirements(pin);
    }

    private void updateRequirements(String pin) {
        boolean lengthOk = pin.length() >= 4;
        boolean uniqueOk = !TextUtils.isEmpty(pin) && hasUniqueDigits(pin);
        applyRequirement(ivReqLength, tvReqLength, lengthOk);
        applyRequirement(ivReqRepeat, tvReqRepeat, uniqueOk);
    }

    private void applyRequirement(ImageView icon, TextView text, boolean met) {
        if (icon == null || text == null) {
            return;
        }
        int color = requireContext().getColor(
                met ? R.color.accent_purple : R.color.text_secondary_dark);
        icon.setImageTintList(ColorStateList.valueOf(color));
        icon.setAlpha(met ? 1f : 0.45f);
        text.setTextColor(color);
    }

    private void saveSetupPin() {
        String pin = valueOf(etNew);
        if (!PinManager.isValidPin(pin)) {
            showError(getString(R.string.pin_error_length));
            return;
        }
        if (!pin.equals(valueOf(etConfirm))) {
            showError(getString(R.string.pin_error_mismatch));
            return;
        }
        PinManager.setPin(requireContext(), pin);
        Toast.makeText(requireContext(), R.string.pin_set_toast, Toast.LENGTH_SHORT).show();
        finish(true, false);
    }

    private void saveManagedPin() {
        String current = valueOf(etCurrent);
        if (!PinManager.checkPin(requireContext(), current)) {
            showError(getString(R.string.pin_error_current_incorrect));
            etCurrent.selectAll();
            return;
        }
        String pin = valueOf(etNew);
        if (!PinManager.isValidPin(pin)) {
            showError(getString(R.string.pin_error_new_length));
            return;
        }
        if (!pin.equals(valueOf(etConfirm))) {
            showError(getString(R.string.pin_error_mismatch));
            return;
        }
        PinManager.setPin(requireContext(), pin);
        Toast.makeText(requireContext(), R.string.pin_updated_toast, Toast.LENGTH_SHORT).show();
        finish(true, false);
    }

    private void deleteManagedPin() {
        String current = valueOf(etCurrent);
        if (!PinManager.checkPin(requireContext(), current)) {
            showError(getString(R.string.pin_error_enter_current_to_delete));
            etCurrent.requestFocus();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pin_delete_title)
                .setMessage(R.string.pin_delete_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    PinManager.clearPin(requireContext());
                    Toast.makeText(requireContext(), R.string.pin_deleted_toast,
                            Toast.LENGTH_SHORT).show();
                    finish(false, false);
                })
                .show();
    }

    private void confirmSkip() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pin_skip_title)
                .setMessage(R.string.pin_skip_message)
                .setNegativeButton(R.string.action_back, null)
                .setPositiveButton(R.string.action_continue,
                        (dialog, which) -> finish(false, true))
                .show();
    }

    private void finish(boolean pinSet, boolean skipped) {
        Callback cb = callback;
        dismissAllowingStateLoss();
        if (cb != null) {
            cb.onResult(pinSet, skipped);
        }
    }

    private void showError(String message) {
        if (tvError != null) {
            tvError.setText(message);
            tvError.setVisibility(View.VISIBLE);
        }
    }

    private void clearErrorOnEdit(EditText editText) {
        if (editText == null) {
            return;
        }
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (tvError != null) {
                    tvError.setVisibility(View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private static boolean hasUniqueDigits(String pin) {
        Set<Character> digits = new HashSet<>();
        for (int i = 0; i < pin.length(); i++) {
            if (!digits.add(pin.charAt(i))) {
                return false;
            }
        }
        return !pin.isEmpty();
    }

    private static boolean isEasyPattern(String pin) {
        if (pin.length() < 2) {
            return true;
        }
        boolean allSame = true;
        boolean ascending = true;
        boolean descending = true;
        for (int i = 1; i < pin.length(); i++) {
            char previous = pin.charAt(i - 1);
            char current = pin.charAt(i);
            if (current != previous) {
                allSame = false;
            }
            if (current != (char) (previous + 1)) {
                ascending = false;
            }
            if (current != (char) (previous - 1)) {
                descending = false;
            }
        }
        return allSame || ascending || descending;
    }

    private static String valueOf(EditText input) {
        if (input == null || input.getText() == null) {
            return "";
        }
        String value = input.getText().toString().trim();
        return TextUtils.isEmpty(value) ? "" : value;
    }
}
