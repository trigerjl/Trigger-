package com.triger.poc;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

public class PrivateKeySheet extends BottomSheetDialogFragment {

    private static final int REQUEST_SEED_AUTH = 5501;

    private String privateKey = "";
    private String mnemonic = "";
    private boolean privateKeyRevealed = false;
    private boolean seedAuthorized = false;

    private MaterialButtonToggleGroup keyTypeToggle;
    private View keySection;
    private View seedSection;
    private TextView keyView;
    private GridLayout seedGrid;
    private MaterialButton revealButton;

    public static PrivateKeySheet newInstance() {
        return new PrivateKeySheet();
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
        return inflater.inflate(R.layout.sheet_private_key, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        keyTypeToggle = view.findViewById(R.id.toggle_key_type);
        keySection = view.findViewById(R.id.ll_private_key_section);
        seedSection = view.findViewById(R.id.ll_seed_phrase_section);
        keyView = view.findViewById(R.id.tv_private_key);
        seedGrid = view.findViewById(R.id.grid_seed_phrase);
        revealButton = view.findViewById(R.id.btn_reveal_private_key);
        MaterialButton copyPrivateButton = view.findViewById(R.id.btn_copy_private_key);
        MaterialButton copySeedButton = view.findViewById(R.id.btn_copy_seed_phrase);
        MaterialButton privateTab = view.findViewById(R.id.btn_tab_private_key);
        MaterialButton seedTab = view.findViewById(R.id.btn_tab_seed);

        String storedKey = KeyManager.getPrivateKeyHex(requireContext());
        privateKey = storedKey == null ? "" : storedKey.trim();
        String storedMnemonic = KeyManager.getMnemonic(requireContext());
        mnemonic = storedMnemonic == null ? "" : storedMnemonic.trim();

        revealButton.setOnClickListener(v -> onRevealClicked());
        copyPrivateButton.setOnClickListener(v ->
                copyText(privateKey, R.string.private_key_copied));
        copySeedButton.setOnClickListener(v ->
                copyText(mnemonic, R.string.seed_phrase_copied));
        privateTab.setOnClickListener(v -> selectPrivateTab());
        seedTab.setOnClickListener(v -> selectSeedTab());

        view.findViewById(R.id.btn_private_key_close).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btn_private_key_done).setOnClickListener(v -> dismiss());

        keyTypeToggle.check(R.id.btn_tab_seed);
        selectSeedTab();
    }

    private void selectPrivateTab() {
        seedAuthorized = false;
        seedSection.setVisibility(View.GONE);
        if (privateKey.isEmpty()) {
            keySection.setVisibility(View.GONE);
            revealButton.setVisibility(View.VISIBLE);
            revealButton.setEnabled(false);
            revealButton.setText(R.string.private_key_not_created);
            return;
        }
        if (privateKeyRevealed) {
            keySection.setVisibility(View.VISIBLE);
            revealButton.setVisibility(View.GONE);
            return;
        }
        keySection.setVisibility(View.GONE);
        revealButton.setVisibility(View.VISIBLE);
        revealButton.setEnabled(true);
        revealButton.setText(R.string.private_key_show);
    }

    private void selectSeedTab() {
        seedAuthorized = false;
        keySection.setVisibility(View.GONE);
        seedSection.setVisibility(View.GONE);
        revealButton.setVisibility(View.VISIBLE);
        if (mnemonic.isEmpty()) {
            revealButton.setEnabled(false);
            revealButton.setText(R.string.seed_phrase_unavailable);
            return;
        }
        revealButton.setEnabled(true);
        revealButton.setText(R.string.seed_phrase_show);
    }

    private void onRevealClicked() {
        boolean seedTabSelected = keyTypeToggle.getCheckedButtonId() == R.id.btn_tab_seed;
        if (seedTabSelected) {
            if (mnemonic.isEmpty()) {
                Toast.makeText(requireContext(), R.string.seed_phrase_unavailable,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (!PinManager.isPinSet(requireContext())) {
                Toast.makeText(requireContext(), R.string.seed_phrase_pin_required,
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (seedAuthorized) {
                showSeedPhrase();
                return;
            }
            Intent intent = new Intent(requireContext(), PinActivity.class);
            intent.putExtra(PinActivity.EXTRA_VERIFY_ONLY, true);
            startActivityForResult(intent, REQUEST_SEED_AUTH);
            return;
        }

        if (privateKey.isEmpty()) {
            Toast.makeText(requireContext(), R.string.error_private_key_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        privateKeyRevealed = true;
        keyView.setText(privateKey);
        keySection.setVisibility(View.VISIBLE);
        revealButton.setVisibility(View.GONE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_SEED_AUTH) {
            if (resultCode == Activity.RESULT_OK) {
                seedAuthorized = true;
                showSeedPhrase();
            } else {
                Toast.makeText(requireContext(), R.string.seed_phrase_auth_cancelled,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void showSeedPhrase() {
        if (!seedAuthorized || mnemonic.isEmpty() || seedGrid == null) {
            return;
        }
        renderSeedGrid();
        seedSection.setVisibility(View.VISIBLE);
        revealButton.setVisibility(View.GONE);
    }

    private void renderSeedGrid() {
        seedGrid.removeAllViews();
        String[] words = mnemonic.split("\\s+");
        int margin = dp(4);
        int padding = dp(10);
        for (int i = 0; i < words.length; i++) {
            TextView cell = new TextView(requireContext());
            cell.setText(getString(R.string.seed_word_format, i + 1, words[i]));
            cell.setTextColor(requireContext().getColor(R.color.text_primary_dark));
            cell.setTextSize(15);
            cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundResource(R.drawable.bg_input_rounded);
            cell.setPadding(padding, padding, padding, padding);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(i % 3, 1f);
            params.rowSpec = GridLayout.spec(i / 3);
            params.setMargins(margin, margin, margin, margin);
            cell.setLayoutParams(params);
            seedGrid.addView(cell);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void copyText(String value, int toastRes) {
        if (value == null || value.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("wallet_secret", value));
        Toast.makeText(requireContext(), toastRes, Toast.LENGTH_SHORT).show();
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
