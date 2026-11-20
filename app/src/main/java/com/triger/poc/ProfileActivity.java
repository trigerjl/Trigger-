package com.triger.poc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public class ProfileActivity extends AppCompatActivity {

    private EditText etUsername;
    private ImageView ivAvatar;
    private TextView tvAvatarSeed;
    private TextView tvEndpointId;
    private String endpointId = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        etUsername = findViewById(R.id.et_username);
        ivAvatar = findViewById(R.id.iv_avatar);
        tvAvatarSeed = findViewById(R.id.tv_avatar_seed);
        tvEndpointId = findViewById(R.id.tv_endpoint_id);

        endpointId = IrohNode.getInstance().getEndpointId();
        if (TextUtils.isEmpty(endpointId)) {
            endpointId = IrohNode.getInstance().getSelfEndpointId();
        }
        if (TextUtils.isEmpty(endpointId)) {
            endpointId = "";
        }

        SharedPreferences sp = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String savedUsername = sp.getString(SettingsActivity.KEY_USERNAME, "");
        if (TextUtils.isEmpty(savedUsername)) {
            savedUsername = PeerNameGenerator.generateName(this, endpointId);
        }
        etUsername.setText(savedUsername);
        updateAvatar(savedUsername, endpointId);
        tvEndpointId.setText(getString(R.string.profile_endpoint_id_format,
                endpointId.isEmpty() ? getString(R.string.placeholder_dash) : endpointId));

        TextView tvHardware = findViewById(R.id.tv_profile_hardware_protection);
        if (tvHardware != null) {
            tvHardware.setText(DeviceIdentity.isStrongBoxBacked(this)
                    ? R.string.device_strongbox_available
                    : R.string.device_strongbox_unavailable);
        }

        Button btnSave = findViewById(R.id.btn_save_profile);
        btnSave.setOnClickListener(v -> {
            String newName = etUsername.getText() == null ? "" : etUsername.getText().toString().trim();
            if (newName.isEmpty()) {
                newName = PeerNameGenerator.generateName(this, endpointId);
                etUsername.setText(newName);
            }
            sp.edit().putString(SettingsActivity.KEY_USERNAME, newName).apply();
            IrohNode.getInstance().broadcastProfile();
            Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
            updateAvatar(newName, endpointId);
        });

        Button btnCopy = findViewById(R.id.btn_copy_endpoint);
        btnCopy.setOnClickListener(v -> {
            if (TextUtils.isEmpty(endpointId)) {
                Toast.makeText(this, R.string.profile_endpoint_unavailable,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("endpoint_id", endpointId));
            }
            Toast.makeText(this, R.string.profile_endpoint_copied, Toast.LENGTH_SHORT).show();
        });
    }

    private void updateAvatar(String name, String seed) {
        String s = TextUtils.isEmpty(seed) ? name : seed;
        if (TextUtils.isEmpty(s)) {
            s = "triger";
        }
        tvAvatarSeed.setText(PeerNameGenerator.shortId(s));
        Bitmap custom = AvatarStore.load(this);
        if (custom != null) {
            CosmeticManager.clearAvatarDecor(ivAvatar);
            ivAvatar.setImageBitmap(custom);
            return;
        }
        String equippedAvatar = CosmeticManager.getEquippedAvatar(this);
        if (!TextUtils.isEmpty(equippedAvatar)) {
            CosmeticManager.applyAvatar(ivAvatar, equippedAvatar);
            return;
        }
        CosmeticManager.clearAvatarDecor(ivAvatar);
        Bitmap bmp = generateAvatar(s);
        ivAvatar.setImageBitmap(bmp);
    }

    private Bitmap generateAvatar(String seed) {
        int size = 256;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int color = colorFromSeed(seed);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(size * 0.35f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        String initials = getInitials(seed);
        canvas.drawText(initials, size / 2f, size / 2f + textPaint.getTextSize() / 3f, textPaint);
        return bitmap;
    }

    private int colorFromSeed(String seed) {
        byte[] hash = sha256(seed);
        int r = hash[0] & 0xFF;
        int g = hash[1] & 0xFF;
        int b = hash[2] & 0xFF;
        r = (r % 200) + 55;
        g = (g % 200) + 55;
        b = (b % 200) + 55;
        return Color.rgb(r, g, b);
    }

    private String getInitials(String seed) {
        String s = seed.replaceAll("[^A-Za-zА-Яа-я0-9]", " ").trim();
        if (s.isEmpty()) {
            return "TR";
        }
        String[] parts = s.split("\\s+");
        if (parts.length >= 2) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.ROOT);
        }
        if (parts[0].length() >= 2) {
            return parts[0].substring(0, 2).toUpperCase(Locale.ROOT);
        }
        return (parts[0].substring(0, 1) + "X").toUpperCase(Locale.ROOT);
    }

    private byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[]{(byte) 100, (byte) 150, (byte) 200};
        }
    }
}
