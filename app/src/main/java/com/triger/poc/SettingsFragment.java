package com.triger.poc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

public class SettingsFragment extends Fragment {

    private static final String PREFS_NAME = SettingsActivity.PREFS_NAME;
    private static final String KEY_THEME = SettingsActivity.KEY_THEME;

    private TextView tvAppVersion;
    private SwitchMaterial switchDarkTheme;
    private SwitchMaterial switchSpvMode;
    private SwitchMaterial switchDnd;
    private TextView tvLanguageStatus;
    private TextView tvDisplayCurrencyStatus;
    private String currentLanguageCode;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        switchDarkTheme = view.findViewById(R.id.switch_dark_theme);
        switchSpvMode = view.findViewById(R.id.switch_spv_mode);
        switchDnd = view.findViewById(R.id.switch_dnd);
        tvAppVersion = view.findViewById(R.id.tv_app_version);
        tvLanguageStatus = view.findViewById(R.id.tv_language_status);
        tvDisplayCurrencyStatus = view.findViewById(R.id.tv_display_currency_status);
        currentLanguageCode = LanguageActivity.getSavedLanguage(requireContext());
        BlockStorage blockStorage = new BlockStorage(requireContext());

        switchDarkTheme.setChecked(SettingsActivity.isDarkTheme(requireContext()));
        switchSpvMode.setChecked(blockStorage.isSpvMode());
        switchDnd.setChecked(SettingsActivity.isDndEnabled(requireContext()));
        tvAppVersion.setText(appVersionText());
        updateLanguageStatus();
        updateDisplayCurrencyStatus();

        switchDarkTheme.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs().edit().putBoolean(KEY_THEME, isChecked).apply();
            requireActivity().recreate();
        });
        switchDnd.setOnCheckedChangeListener((buttonView, isChecked) ->
                SettingsActivity.setDndEnabled(requireContext(), isChecked));
        switchSpvMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            BlockStorage storage = new BlockStorage(requireContext());
            if (isChecked) {
                storage.enableSpvMode();
            } else {
                storage.disableSpvMode();
                IrohNode.getInstance().requestAllBlocksFromPeers();
                IrohNode.getInstance().requestChainFromAllPeers();
            }
        });

        view.findViewById(R.id.item_theme).setOnClickListener(v -> switchDarkTheme.toggle());
        view.findViewById(R.id.item_spv_mode).setOnClickListener(v -> switchSpvMode.toggle());
        view.findViewById(R.id.item_dnd).setOnClickListener(v -> switchDnd.toggle());
        view.findViewById(R.id.item_language).setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), LanguageActivity.class);
            intent.putExtra(LanguageActivity.EXTRA_RETURN_TO_CALLER, true);
            startActivity(intent);
        });
        view.findViewById(R.id.item_display_currency)
                .setOnClickListener(v -> showCurrencyPicker());
        view.findViewById(R.id.item_account_security).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SecurityActivity.class)));
        view.findViewById(R.id.item_help).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), HelpActivity.class)));
        view.findViewById(R.id.item_check_updates).setOnClickListener(v ->
                UpdateChecker.check(requireActivity()));
        view.findViewById(R.id.item_about).setOnClickListener(v -> showAboutDialog());
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void onResume() {
        super.onResume();
        String savedLanguage = LanguageActivity.getSavedLanguage(requireContext());
        if (currentLanguageCode != null && !currentLanguageCode.equals(savedLanguage)) {
            requireActivity().recreate();
            return;
        }
        updateLanguageStatus();
    }

    private void updateLanguageStatus() {
        if (tvLanguageStatus != null) {
            tvLanguageStatus.setText(LanguageActivity.getSavedLanguageName(requireContext()));
        }
    }

    private void updateDisplayCurrencyStatus() {
        if (tvDisplayCurrencyStatus == null || getContext() == null) {
            return;
        }
        String currency = CurrencyConverter.resolveDisplayCurrency(requireContext());
        tvDisplayCurrencyStatus.setText(
                CurrencyConverter.currencyLabel(requireContext(), currency));
    }

    private void showCurrencyPicker() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        final List<String> currencies = CurrencyConverter.SUPPORTED_CURRENCIES;
        String current = CurrencyConverter.resolveDisplayCurrency(requireContext());
        int checked = currencies.indexOf(current);
        String[] labels = new String[currencies.size()];
        for (int i = 0; i < currencies.size(); i++) {
            labels[i] = CurrencyConverter.currencyLabel(requireContext(), currencies.get(i));
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_display_currency)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    CurrencyConverter.setDisplayCurrency(requireContext(),
                            currencies.get(which));
                    updateDisplayCurrencyStatus();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void showAboutDialog() {
        AboutSheetDialogFragment.newInstance()
                .show(getChildFragmentManager(), "about");
    }

    private String appVersionText() {
        String name = getString(R.string.app_name);
        try {
            PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            return getString(R.string.app_version_name_format, name,
                    info.versionName == null ? "1.0" : info.versionName);
        } catch (Exception e) {
            return getString(R.string.app_version_name_format, name, "1.0");
        }
    }
}
