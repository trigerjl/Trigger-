package com.triger.poc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.checkbox.MaterialCheckBox;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class SecurityActivity extends AppCompatActivity {

    private static final int REQUEST_EXPORT_DATA = 5401;
    private static final int REQUEST_IMPORT_DATA = 5402;
    private static final String BACKUP_MIME_TYPE = "application/json";

    private TextView tvPinStatus;
    private MaterialCheckBox cbIncludeExtended;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageActivity.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvPinStatus = findViewById(R.id.tv_pin_status);
        cbIncludeExtended = findViewById(R.id.cb_include_extended);
        updatePinStatus();

        findViewById(R.id.item_pin).setOnClickListener(v ->
                PinSettingsDialogs.show(this, this::updatePinStatus));
        findViewById(R.id.item_export_key).setOnClickListener(v ->
                PrivateKeySheet.newInstance()
                        .show(getSupportFragmentManager(), "private_key_export"));
        findViewById(R.id.item_recovery).setOnClickListener(v ->
                AccountRecoverySheet.newInstance()
                        .show(getSupportFragmentManager(), "account_recovery"));
        findViewById(R.id.btn_export_data).setOnClickListener(v -> exportAccountData());
        findViewById(R.id.btn_import_data).setOnClickListener(v -> importAccountData());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePinStatus();
    }

    private void updatePinStatus() {
        if (tvPinStatus == null) {
            return;
        }
        tvPinStatus.setText(PinManager.isPinSet(this)
                ? R.string.settings_pin_set
                : R.string.settings_pin_not_set_action);
    }

    private void exportAccountData() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(BACKUP_MIME_TYPE);
        intent.putExtra(Intent.EXTRA_TITLE, "triger-account-backup.json");
        try {
            startActivityForResult(intent, REQUEST_EXPORT_DATA);
        } catch (Exception e) {
            Toast.makeText(this, R.string.account_data_export_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void importAccountData() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{BACKUP_MIME_TYPE, "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_IMPORT_DATA);
        } catch (Exception e) {
            Toast.makeText(this, R.string.account_data_import_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_EXPORT_DATA) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                writeBackup(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT_DATA) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                readBackup(data.getData());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void writeBackup(Uri uri) {
        boolean includeExtended = cbIncludeExtended != null && cbIncludeExtended.isChecked();
        try {
            String json = AccountBackup.exportJson(this, includeExtended);
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IllegalStateException("output stream unavailable");
                }
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            Toast.makeText(this,
                    includeExtended ? R.string.backup_full_export
                            : R.string.account_data_exported,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.account_data_export_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void readBackup(Uri uri) {
        try {
            String json;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
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

            SignerService.stopMining(this);
            AccountBackup.ImportResult result = AccountBackup.importJson(this, json);
            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent(MainActivity.ACTION_ACCOUNT_RESTORED));
            Toast.makeText(this,
                    getString(R.string.account_data_imported, result.transactions,
                            Tokenomics.formatAmount(result.balance)),
                    Toast.LENGTH_LONG).show();
        } catch (JSONException e) {
            Toast.makeText(this, R.string.account_data_import_error,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.account_data_import_error,
                    Toast.LENGTH_SHORT).show();
        }
    }
}
