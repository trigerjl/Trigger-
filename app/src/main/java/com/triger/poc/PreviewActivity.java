package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Полноэкранная примерка рамки или аватара. */
public class PreviewActivity extends AppCompatActivity {

    public static final String EXTRA_ITEM_ID = "extra_item_id";

    private FrameLayout previewStage;
    private FrameLayout avatarStage;
    private ImageView previewAvatar;
    private ImageView previewIcon;
    private ImageView previewFrame;
    private TextView previewName;
    private TextView previewRequirement;
    private MaterialButton applyButton;
    private CosmeticManager.Item item;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        previewStage = findViewById(R.id.fl_preview_stage);
        avatarStage = findViewById(R.id.fl_preview_avatar_stage);
        previewAvatar = findViewById(R.id.iv_preview_avatar);
        previewIcon = findViewById(R.id.iv_preview_icon);
        previewFrame = findViewById(R.id.iv_preview_frame);
        previewName = findViewById(R.id.tv_preview_name);
        previewRequirement = findViewById(R.id.tv_preview_requirement);
        applyButton = findViewById(R.id.btn_preview_apply);
        MaterialButton closeButton = findViewById(R.id.btn_preview_close);
        CosmeticManager.applyCircleClip(previewAvatar);
        CosmeticManager.applyCircleClip(previewIcon);

        item = CosmeticManager.findItem(getIntent().getStringExtra(EXTRA_ITEM_ID));
        if (item == null) {
            finish();
            return;
        }

        boolean unlocked = CosmeticManager.hasItem(this, item.id);
        previewName.setText(item.nameRes);
        previewRequirement.setText(buildRequirementText(unlocked));
        renderPreview();
        startFrameAnimation();

        applyButton.setVisibility(unlocked ? View.VISIBLE : View.GONE);
        applyButton.setOnClickListener(v -> {
            if (CosmeticManager.equipItem(this, item.id)) {
                Toast.makeText(this, R.string.cosmetics_equipped, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });
        closeButton.setOnClickListener(v -> finish());
    }

    private CharSequence buildRequirementText(boolean unlocked) {
        String requirement = item.getRequirementText(this);
        if (!unlocked) {
            return getString(R.string.cosmetics_locked, requirement);
        }
        if (TextUtils.isEmpty(requirement)) {
            return getString(R.string.cosmetics_unlocked);
        }
        return getString(R.string.cosmetics_unlocked) + " · " + requirement;
    }

    private void renderPreview() {
        avatarStage.setBackground(null);
        previewAvatar.setImageDrawable(null);
        previewAvatar.setVisibility(View.GONE);
        previewIcon.setImageDrawable(null);
        previewIcon.setVisibility(View.GONE);

        if (item.isAvatar()) {
            showAvatarCosmetic(item.id);
        } else {
            Bitmap custom = AvatarStore.load(this);
            String avatarId = CosmeticManager.getEquippedAvatar(this);
            if (custom != null) {
                previewAvatar.setImageBitmap(custom);
                previewAvatar.setVisibility(View.VISIBLE);
            } else if (!TextUtils.isEmpty(avatarId)) {
                showAvatarCosmetic(avatarId);
            } else {
                previewAvatar.setImageBitmap(generateAvatar(profileSeed()));
                previewAvatar.setVisibility(View.VISIBLE);
            }
        }

        String frameId = item.isFrame()
                ? item.id : CosmeticManager.getEquippedFrame(this);
        CosmeticManager.applyFrame(previewFrame, frameId);
    }

    private void showAvatarCosmetic(String avatarId) {
        Drawable background = getDrawable(CosmeticManager.avatarBackgroundRes(avatarId));
        avatarStage.setBackground(background);
        previewIcon.setImageResource(CosmeticManager.avatarIconRes(avatarId));
        previewIcon.setVisibility(View.VISIBLE);
    }

    private void startFrameAnimation() {
        Drawable frame = previewFrame.getDrawable();
        if (frame instanceof Animatable) {
            ((Animatable) frame).start();
        }
        Drawable background = avatarStage.getBackground();
        if (background instanceof Animatable) {
            ((Animatable) background).start();
        }
    }

    private String profileSeed() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME,
                Context.MODE_PRIVATE);
        String name = prefs.getString(SettingsActivity.KEY_USERNAME, "");
        return TextUtils.isEmpty(name) ? "triger" : name;
    }

    private Bitmap generateAvatar(String seed) {
        int size = 512;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(colorFromSeed(seed));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(size * 0.35f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(getInitials(seed), size / 2f,
                size / 2f + textPaint.getTextSize() / 3f, textPaint);
        return bitmap;
    }

    private int colorFromSeed(String seed) {
        byte[] hash = sha256(seed);
        int r = (hash[0] & 0xFF) % 200 + 55;
        int g = (hash[1] & 0xFF) % 200 + 55;
        int b = (hash[2] & 0xFF) % 200 + 55;
        return Color.rgb(r, g, b);
    }

    private String getInitials(String seed) {
        String s = seed.replaceAll("[^A-Za-zА-Яа-я0-9]", " ").trim();
        if (s.isEmpty()) {
            return "TR";
        }
        String[] parts = s.split("\\s+");
        if (parts.length >= 2) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1))
                    .toUpperCase(Locale.ROOT);
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

    @Override
    protected void onDestroy() {
        if (previewStage != null) {
            Drawable frame = previewFrame.getDrawable();
            if (frame instanceof Animatable) {
                ((Animatable) frame).stop();
            }
            Drawable background = avatarStage.getBackground();
            if (background instanceof Animatable) {
                ((Animatable) background).stop();
            }
        }
        super.onDestroy();
    }
}
