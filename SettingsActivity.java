package com.triger.poc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "triger_prefs";
    public static final String KEY_THEME = "theme";
    public static final String KEY_RPC_URL = "rpc_url";
    public static final String KEY_CONTRACT_ADDRESS = "contract_address";
    public static final String KEY_TEST_PRIVATE_KEY = "test_private_key";
    public static final String KEY_ALLOW_PLAINTEXT = "allow_plaintext";
    public static final String KEY_MINING_INTERVAL = "mining_interval";
    public static final int DEFAULT_MINING_INTERVAL = 60;

    private static final String TAG = "SettingsActivity";

    public static int getMiningIntervalSeconds(Context context) {
        return prefs(context).getInt(KEY_MINING_INTERVAL, DEFAULT_MINING_INTERVAL);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private SwitchCompat switchDarkTheme;
    private EditText etRpcUrl;
    private EditText etContract;
    private EditText etPrivateKey;
    private RadioGroup rgMiningInterval;
    private TextView tvAppVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean dark = prefs(this).getBoolean(KEY_THEME, false);
        setTheme(dark ? R.style.Theme_Triger_Dark : R.style.Theme_Triger_Light);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        switchDarkTheme = findViewById(R.id.switch_dark_theme);
        etRpcUrl = findViewById(R.id.et_rpc_url);
        etContract = findViewById(R.id.et_contract_address);
        etPrivateKey = findViewById(R.id.et_private_key);
        rgMiningInterval = findViewById(R.id.rg_mining_interval);
        tvAppVersion = findViewById(R.id.tv_app_version);

        SharedPreferences sp = prefs(this);
        switchDarkTheme.setChecked(sp.getBoolean(KEY_THEME, false));
        etRpcUrl.setText(sp.getString(KEY_RPC_URL, ""));
        etContract.setText(sp.getString(KEY_CONTRACT_ADDRESS, ""));
        etPrivateKey.setText(sp.getString(KEY_TEST_PRIVATE_KEY, ""));
        checkIntervalRadio(sp.getInt(KEY_MINING_INTERVAL, DEFAULT_MINING_INTERVAL));
        tvAppVersion.setText(appVersionText());

        switchDarkTheme.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs(SettingsActivity.this).edit().putBoolean(KEY_THEME, isChecked).apply();
            recreate();
        });

        rgMiningInterval.setOnCheckedChangeListener((group, checkedId) -> {
            int seconds = intervalFromRadio(checkedId);
            prefs(SettingsActivity.this).edit().putInt(KEY_MINING_INTERVAL, seconds).apply();
            Toast.makeText(SettingsActivity.this,
                    "Интервал майнинга сохранён: " + seconds + " с", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_save_network).setOnClickListener(v -> saveNetworkSettings());
        findViewById(R.id.btn_clear_history).setOnClickListener(v -> {
            TransactionHistory.clearHistory(SettingsActivity.this);
            Toast.makeText(SettingsActivity.this, "История транзакций очищена", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_invite_friend).setOnClickListener(v -> inviteFriend());
    }

    private void inviteFriend() {
        String peerId = IrohNode.getInstance().getEndpointId();
        if (peerId == null || peerId.isEmpty()) {
            Toast.makeText(this, "Endpoint ID ещё не готов, подождите запуска узла", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = "Присоединяйся к сети Triger! Мой Endpoint ID: " + peerId
                + ". Скачай приложение и найди меня в сети.";
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Пригласить друга"));
    }

    private void saveNetworkSettings() {
        String rpc = text(etRpcUrl);
        String contract = text(etContract);
        String privateKey = text(etPrivateKey);
        prefs(this).edit()
                .putString(KEY_RPC_URL, rpc)
                .putString(KEY_CONTRACT_ADDRESS, contract)
                .putString(KEY_TEST_PRIVATE_KEY, privateKey)
                .apply();
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
    }

    private String text(EditText field) {
        return field == null || field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void checkIntervalRadio(int seconds) {
        if (seconds <= 10) {
            rgMiningInterval.check(R.id.rb_interval_10);
        } else if (seconds >= 60) {
            rgMiningInterval.check(R.id.rb_interval_60);
        } else {
            rgMiningInterval.check(R.id.rb_interval_30);
        }
    }

    private int intervalFromRadio(int checkedId) {
        if (checkedId == R.id.rb_interval_10) {
            return 10;
        }
        if (checkedId == R.id.rb_interval_60) {
            return 60;
        }
        return 30;
    }

    private String appVersionText() {
        String name = getString(R.string.app_name);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return name + " v" + (info.versionName == null ? "1.0" : info.versionName);
        } catch (Exception e) {
            return name + " v1.0";
        }
    }
}
