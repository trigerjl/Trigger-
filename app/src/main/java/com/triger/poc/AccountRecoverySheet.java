package com.triger.poc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Восстановление аккаунта по приватному ключу. */
public class AccountRecoverySheet extends BottomSheetDialogFragment {

    private static final int REQUEST_IMPORT_DATA = 5403;
    private static final String BACKUP_MIME_TYPE = "application/json";

    private EditText recoveryInput;
    private TextView recoveryLabel;
    private TextView recoveryError;
    private MaterialButtonToggleGroup recoveryToggle;
    private boolean seedMode = false;

    public static AccountRecoverySheet newInstance() {
        return new AccountRecoverySheet();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        setStyle(DialogFragment.STYLE_NORMAL, R.style.Triger_BottomSheetDialog);
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_account_recovery, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recoveryInput = view.findViewById(R.id.et_recovery_key);
        recoveryLabel = view.findViewById(R.id.tv_recovery_input_label);
        recoveryError = view.findViewById(R.id.tv_account_recovery_error);
        recoveryToggle = view.findViewById(R.id.toggle_recovery_type);
        ImageButton toggleKey = view.findViewById(R.id.btn_toggle_recovery_key);
        MaterialButton restoreButton = view.findViewById(R.id.btn_restore_account);
        MaterialButton keyTab = view.findViewById(R.id.btn_tab_recovery_key);
        MaterialButton seedTab = view.findViewById(R.id.btn_tab_recovery_seed);

        toggleKey.setOnClickListener(v -> {
            boolean hidden = recoveryInput.getTransformationMethod()
                    instanceof PasswordTransformationMethod;
            recoveryInput.setTransformationMethod(hidden
                    ? null
                    : PasswordTransformationMethod.getInstance());
            toggleKey.setImageResource(hidden
                    ? R.drawable.ic_visibility_off
                    : R.drawable.ic_visibility);
            toggleKey.setContentDescription(hidden
                    ? getString(R.string.private_key_hide)
                    : getString(R.string.private_key_show));
            if (recoveryInput.getText().length() > 0) {
                recoveryInput.setSelection(recoveryInput.getText().length());
            }
        });

        recoveryInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                recoveryError.setVisibility(View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        recoveryInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                restoreButton.performClick();
                return true;
            }
            return false;
        });

        keyTab.setOnClickListener(v -> setRecoveryMode(false));
        seedTab.setOnClickListener(v -> setRecoveryMode(true));
        restoreButton.setOnClickListener(v -> restoreAccount(recoveryInput, recoveryError));

        recoveryToggle.check(R.id.btn_tab_recovery_key);
        setRecoveryMode(false);

        view.findViewById(R.id.btn_account_recovery_close).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btn_account_recovery_cancel).setOnClickListener(v -> dismiss());
    }

    private void setRecoveryMode(boolean seed) {
        seedMode = seed;
        recoveryError.setVisibility(View.GONE);
        recoveryInput.setText("");
        if (seed) {
            recoveryLabel.setText(R.string.account_recovery_seed_label);
            recoveryInput.setHint(R.string.hint_seed_phrase);
            recoveryInput.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            recoveryInput.setSingleLine(false);
            recoveryInput.setMaxLines(4);
            recoveryInput.setHorizontallyScrolling(false);
            recoveryInput.setGravity(Gravity.TOP | Gravity.START);
            recoveryInput.setTransformationMethod(null);
        } else {
            recoveryLabel.setText(R.string.account_recovery_key_label);
            recoveryInput.setHint(R.string.hint_private_key_placeholder);
            recoveryInput.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            recoveryInput.setSingleLine(true);
            recoveryInput.setMaxLines(1);
            recoveryInput.setHorizontallyScrolling(true);
            recoveryInput.setGravity(Gravity.CENTER_VERTICAL);
            recoveryInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        ImageButton toggleKey = getView() == null ? null
                : getView().findViewById(R.id.btn_toggle_recovery_key);
        if (toggleKey != null) {
            boolean hidden = recoveryInput.getTransformationMethod()
                    instanceof PasswordTransformationMethod;
            toggleKey.setImageResource(hidden
                    ? R.drawable.ic_visibility
                    : R.drawable.ic_visibility_off);
            toggleKey.setContentDescription(getString(hidden
                    ? R.string.private_key_show
                    : R.string.private_key_hide));
        }
    }

    private void restoreAccount(EditText keyInput, TextView errorView) {
        String entered = keyInput.getText() == null ? "" : keyInput.getText().toString();
        if (entered.trim().isEmpty()) {
            showError(errorView, getString(seedMode
                    ? R.string.error_enter_seed_phrase
                    : R.string.error_enter_private_key));
            return;
        }
        boolean valid = seedMode
                ? KeyManager.isValidMnemonic(entered)
                : KeyManager.isValidPrivateKey(entered);
        if (!valid) {
            showError(errorView, getString(seedMode
                    ? R.string.error_seed_phrase_invalid
                    : R.string.error_private_key_invalid));
            keyInput.selectAll();
            return;
        }

        double balance = KeyManager.getLocalBalance(requireContext());
        if (balance > 1e-9) {
            confirmLossAndRestore(entered, errorView, balance);
            return;
        }
        performRestore(entered, errorView);
    }

    private void confirmLossAndRestore(String entered, TextView errorView, double balance) {
        if (!isAdded()) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.account_recovery_balance_warning_title)
                .setMessage(getString(R.string.account_recovery_balance_warning,
                        Tokenomics.formatAmount(balance)))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.account_recovery_balance_continue,
                        (d, which) -> performRestore(entered, errorView))
                .create();
        dialog.setOnShowListener(d -> {
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) {
                message.setTextColor(requireContext().getColor(R.color.danger_red));
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(requireContext().getColor(R.color.danger_red));
        });
        dialog.show();
    }

    private void performRestore(String entered, TextView errorView) {
        try {
            SignerService.stopMining(requireContext());
            String address = KeyManager.recoverAccount(requireContext(), entered);
            Toast.makeText(requireContext(),
                    getString(R.string.account_recovered_toast, address),
                    Toast.LENGTH_LONG).show();
            promptImportBackup();
        } catch (IllegalArgumentException e) {
            showError(errorView, getString(R.string.error_private_key_invalid));
        }
    }

    private void promptImportBackup() {
        if (!isAdded()) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.account_recovered_title)
                .setMessage(R.string.account_recovery_import_prompt)
                .setPositiveButton(R.string.account_recovery_import_action,
                        (dialog, which) -> launchImport())
                .setNegativeButton(R.string.action_skip, (dialog, which) -> finishRecovery())
                .setOnCancelListener(dialog -> finishRecovery())
                .show();
    }

    private void launchImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{BACKUP_MIME_TYPE, "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_IMPORT_DATA);
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.account_data_import_error,
                    Toast.LENGTH_SHORT).show();
            finishRecovery();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_IMPORT_DATA) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            importBackup(data.getData());
        } else {
            finishRecovery();
        }
    }

    private void importBackup(Uri uri) {
        try {
            String json;
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IllegalStateException("input stream unavailable");
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                json = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }

            AccountBackup.ImportResult result = AccountBackup.importJson(requireContext(), json);
            Toast.makeText(requireContext(),
                    getString(R.string.account_data_imported, result.transactions,
                            Tokenomics.formatAmount(result.balance)),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.account_data_import_error,
                    Toast.LENGTH_SHORT).show();
        } finally {
            finishRecovery();
        }
    }

    private void finishRecovery() {
        Context context = getContext();
        if (context != null) {
            LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(new Intent(MainActivity.ACTION_ACCOUNT_RESTORED));
        }
        dismissAllowingStateLoss();
    }

    private static void showError(TextView errorView, String message) {
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT);
                sheet.setElevation(0f);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        }
    }
}
