package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Рамки профиля и аватары, открываемые за достижения майнинга. */
public final class CosmeticManager {

    public static final String PREFS_NAME = "triger_cosmetics";
    public static final String TYPE_FRAME = "frame";
    public static final String TYPE_AVATAR = "avatar";

    public static final long ONE_HOUR_MS = 60L * 60L * 1000L;
    public static final long HUNDRED_HOURS_MS = 100L * ONE_HOUR_MS;

    /** Серый цвет для заблокированных предметов. */
    public static final int LOCKED_TINT = 0xFF9E9E9E;

    public static final String KEY_OWNED_PREFIX = "owned_";
    public static final String KEY_EQUIPPED_FRAME = "equipped_frame";
    public static final String KEY_EQUIPPED_AVATAR = "equipped_avatar";

    public static final class Item {
        public final String id;
        public final int nameRes;
        public final int goalNameRes;
        public final int unlockNameRes;
        public final int blocksRequired;
        public final long miningTimeRequiredMs;
        public final String type;
        public final int color;

        Item(String id, int nameRes, int goalNameRes, int unlockNameRes, int blocksRequired,
             long miningTimeRequiredMs, String type, int color) {
            this.id = id;
            this.nameRes = nameRes;
            this.goalNameRes = goalNameRes;
            this.unlockNameRes = unlockNameRes;
            this.blocksRequired = blocksRequired;
            this.miningTimeRequiredMs = miningTimeRequiredMs;
            this.type = type;
            this.color = color;
        }

        public boolean isFrame() {
            return TYPE_FRAME.equals(type);
        }

        public boolean isAvatar() {
            return TYPE_AVATAR.equals(type);
        }

        public boolean isUnlockedBy(int minedBlocks, long miningTimeMs) {
            if (blocksRequired > 0 && minedBlocks < blocksRequired) {
                return false;
            }
            if (miningTimeRequiredMs > 0 && miningTimeMs < miningTimeRequiredMs) {
                return false;
            }
            return true;
        }

        public String getRequirementText(Context context) {
            if (context == null) {
                return "";
            }
            if (blocksRequired > 0) {
                return context.getString(R.string.cosmetics_requirement_blocks,
                        blocksRequired);
            }
            if (miningTimeRequiredMs > 0) {
                return context.getString(R.string.cosmetics_requirement_mining_hours,
                        Math.max(1L, miningTimeRequiredMs / ONE_HOUR_MS));
            }
            return "";
        }
    }

    private static final List<Item> ITEMS = Collections.unmodifiableList(Arrays.asList(
            new Item("frame_gold", R.string.cosmetic_frame_gold,
                    R.string.cosmetic_frame_gold_goal, R.string.cosmetic_frame_gold_unlock,
                    1000, 0L, TYPE_FRAME, 0xFFFFD54F),
            new Item("frame_neon", R.string.cosmetic_frame_neon,
                    R.string.cosmetic_frame_neon_goal, R.string.cosmetic_frame_neon_unlock,
                    250, 0L, TYPE_FRAME, 0xFF00E5FF),
            new Item("frame_flame", R.string.cosmetic_frame_flame,
                    R.string.cosmetic_frame_flame_goal, R.string.cosmetic_frame_flame_unlock,
                    100, 0L, TYPE_FRAME, 0xFFFF5722),
            new Item("frame_cosmos", R.string.cosmetic_frame_cosmos,
                    R.string.cosmetic_frame_cosmos_goal, R.string.cosmetic_frame_cosmos_unlock,
                    500, 0L, TYPE_FRAME, 0xFF9C6BFF),
            new Item("frame_alien", R.string.cosmetic_frame_alien,
                    R.string.cosmetic_frame_alien_goal, R.string.cosmetic_frame_alien_unlock,
                    50, 0L, TYPE_FRAME, 0xFF76FF03),
            new Item("frame_crystal", R.string.cosmetic_frame_crystal,
                    R.string.cosmetic_frame_crystal_goal,
                    R.string.cosmetic_frame_crystal_unlock,
                    5, 0L, TYPE_FRAME, 0xFF4DD0E1),
            new Item("frame_lightning", R.string.cosmetic_frame_lightning,
                    R.string.cosmetic_frame_lightning_goal,
                    R.string.cosmetic_frame_lightning_unlock,
                    10, 0L, TYPE_FRAME, 0xFFFFEB3B),
            new Item("frame_rainbow", R.string.cosmetic_frame_rainbow,
                    R.string.cosmetic_frame_rainbow_goal,
                    R.string.cosmetic_frame_rainbow_unlock,
                    20, 0L, TYPE_FRAME, 0xFFFF4081),
            new Item("frame_ice", R.string.cosmetic_frame_ice,
                    R.string.cosmetic_frame_ice_goal, R.string.cosmetic_frame_ice_unlock,
                    40, 0L, TYPE_FRAME, 0xFF81D4FA),
            new Item("frame_fire", R.string.cosmetic_frame_fire,
                    R.string.cosmetic_frame_fire_goal, R.string.cosmetic_frame_fire_unlock,
                    50, 0L, TYPE_FRAME, 0xFFFF6D00),
            new Item("avatar_hacker", R.string.avatar_hacker_name,
                    R.string.avatar_hacker_goal, R.string.avatar_hacker_unlock,
                    50, 0L, TYPE_AVATAR, 0xFF00E676),
            new Item("avatar_wolf", R.string.avatar_wolf_name,
                    R.string.avatar_wolf_goal, R.string.avatar_wolf_unlock,
                    1000, 0L, TYPE_AVATAR, 0xFF00E5CB),
            new Item("avatar_ai", R.string.avatar_ai_name,
                    R.string.avatar_ai_goal, R.string.avatar_ai_unlock,
                    200, 0L, TYPE_AVATAR, 0xFF4FC3F7),
            new Item("avatar_astronaut", R.string.avatar_astronaut_name,
                    R.string.avatar_astronaut_goal, R.string.avatar_astronaut_unlock,
                    500, 0L, TYPE_AVATAR, 0xFF9C6BFF),
            new Item("avatar_lion", R.string.avatar_lion_name,
                    R.string.avatar_lion_goal, R.string.avatar_lion_unlock,
                    10000, 0L, TYPE_AVATAR, 0xFFFFB300)
    ));

    private CosmeticManager() {
    }

    public static List<Item> getAvailableItems() {
        return ITEMS;
    }

    /** Предметы, отображаемые в «Моих достижениях»: только рамки. */
    public static List<Item> getAchievementItems() {
        List<Item> frames = new ArrayList<>();
        for (Item item : ITEMS) {
            if (item.isFrame()) {
                frames.add(item);
            }
        }
        return frames;
    }

    public static Item findItem(String itemId) {
        if (itemId == null) {
            return null;
        }
        String normalized = itemId.trim().toLowerCase(Locale.US);
        for (Item item : ITEMS) {
            if (item.id.equals(normalized)) {
                return item;
            }
        }
        return null;
    }

    public static boolean hasItem(Context context, String itemId) {
        if (context == null || TextUtils.isEmpty(itemId)) {
            return false;
        }
        return prefs(context).getBoolean(KEY_OWNED_PREFIX + itemId.trim().toLowerCase(Locale.US),
                false);
    }

    public static List<Item> checkUnlocks(int minedBlocks, long miningTimeMs) {
        List<Item> unlocked = new ArrayList<>();
        for (Item item : ITEMS) {
            if (item.isUnlockedBy(minedBlocks, miningTimeMs)) {
                unlocked.add(item);
            }
        }
        return unlocked;
    }

    public static List<Item> checkUnlocks(Context context, int minedBlocks, long miningTimeMs) {
        List<Item> newlyUnlocked = new ArrayList<>();
        if (context == null) {
            return newlyUnlocked;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        boolean changed = false;
        for (Item item : checkUnlocks(minedBlocks, miningTimeMs)) {
            if (!hasItem(context, item.id)) {
                editor.putBoolean(KEY_OWNED_PREFIX + item.id, true);
                newlyUnlocked.add(item);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
        return newlyUnlocked;
    }

    public static List<Item> getUnlockedItems(Context context) {
        List<Item> unlocked = new ArrayList<>();
        if (context == null) {
            return unlocked;
        }
        for (Item item : ITEMS) {
            if (hasItem(context, item.id)) {
                unlocked.add(item);
            }
        }
        return unlocked;
    }

    public static Item getNextBlockMilestone(int minedBlocks) {
        Item next = null;
        for (Item item : ITEMS) {
            if (!item.isFrame() || item.blocksRequired <= 0
                    || item.blocksRequired <= minedBlocks) {
                continue;
            }
            if (next == null || item.blocksRequired < next.blocksRequired) {
                next = item;
            }
        }
        return next;
    }

    public static long getMiningTimeMs(Context context) {
        if (context == null) {
            return 0L;
        }
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(SignerService.KEY_MINING_ACTIVE, false)) {
            return 0L;
        }
        long start = prefs.getLong(SignerService.KEY_MINING_START_TIME, 0L);
        if (start <= 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - start);
    }

    public static boolean equipItem(Context context, String itemId) {
        if (context == null) {
            return false;
        }
        Item item = findItem(itemId);
        if (item == null || !hasItem(context, item.id)) {
            return false;
        }
        if (item.isFrame()) {
            setEquippedFrame(context, item.id);
        } else if (item.isAvatar()) {
            setEquippedAvatar(context, item.id);
        }
        return true;
    }

    public static String getEquippedFrame(Context context) {
        if (context == null) {
            return "";
        }
        String id = prefs(context).getString(KEY_EQUIPPED_FRAME, "");
        if (TextUtils.isEmpty(id) || !hasItem(context, id)) {
            return "";
        }
        Item item = findItem(id);
        return item != null && item.isFrame() ? item.id : "";
    }

    public static void setEquippedFrame(Context context, String itemId) {
        if (context == null || TextUtils.isEmpty(itemId)) {
            return;
        }
        Item item = findItem(itemId);
        if (item == null || !item.isFrame() || !hasItem(context, item.id)) {
            return;
        }
        prefs(context).edit().putString(KEY_EQUIPPED_FRAME, item.id).apply();
        try {
            IrohNode.getInstance().broadcastProfile();
        } catch (Throwable ignored) {
        }
    }

    public static String getEquippedAvatar(Context context) {
        if (context == null) {
            return "";
        }
        String id = prefs(context).getString(KEY_EQUIPPED_AVATAR, "");
        if (TextUtils.isEmpty(id) || !hasItem(context, id)) {
            return "";
        }
        Item item = findItem(id);
        return item != null && item.isAvatar() ? item.id : "";
    }

    public static void setEquippedAvatar(Context context, String itemId) {
        if (context == null || TextUtils.isEmpty(itemId)) {
            return;
        }
        Item item = findItem(itemId);
        if (item == null || !item.isAvatar() || !hasItem(context, item.id)) {
            return;
        }
        prefs(context).edit().putString(KEY_EQUIPPED_AVATAR, item.id).apply();
    }

    public static boolean isFrameEquipped(Context context, String itemId) {
        return itemId != null && itemId.equalsIgnoreCase(getEquippedFrame(context));
    }

    public static boolean isAvatarEquipped(Context context, String itemId) {
        return itemId != null && itemId.equalsIgnoreCase(getEquippedAvatar(context));
    }

    /** Рисует анимированную рамку в overlay-ImageView, лежащем ПОВЕРХ аватара
     *  (рамка на весь контейнер, аватар меньше и виден через прозрачный центр).
     *  Пустой frameId убирает рамку. */
    public static void applyFrame(ImageView frameOverlay, String frameId) {
        if (frameOverlay == null) {
            return;
        }
        Item item = findItem(frameId);
        if (item == null || !item.isFrame()) {
            stopDrawable(frameOverlay.getDrawable());
            frameOverlay.setImageDrawable(null);
            frameOverlay.setTag(R.id.tag_cosmetic_frame, null);
            return;
        }
        Object applied = frameOverlay.getTag(R.id.tag_cosmetic_frame);
        if (item.id.equals(applied) && frameOverlay.getDrawable() instanceof Animatable) {
            return;
        }
        stopDrawable(frameOverlay.getDrawable());
        Drawable frame = createFrameDrawable(frameOverlay.getContext(), item.id);
        if (frame == null) {
            frameOverlay.setImageDrawable(null);
            frameOverlay.setTag(R.id.tag_cosmetic_frame, null);
            return;
        }
        frameOverlay.setImageDrawable(frame);
        frameOverlay.setTag(R.id.tag_cosmetic_frame, item.id);
        trackAnimatedDrawables(frameOverlay);
        startDrawable(frame, frameOverlay.isAttachedToWindow());
    }

    /** Применяет аватар-косметику к ImageView: анимированный фон и иконка. */
    public static void applyAvatar(ImageView avatarView, String avatarId) {
        if (avatarView == null) {
            return;
        }
        Item item = findItem(avatarId);
        if (item == null || !item.isAvatar()) {
            clearAvatarDecor(avatarView);
            return;
        }
        Object applied = avatarView.getTag(R.id.tag_cosmetic_avatar);
        if (item.id.equals(applied) && avatarView.getBackground() instanceof Animatable) {
            return;
        }
        stopDrawable(avatarView.getBackground());
        applyCircleClip(avatarView);
        Drawable background = avatarView.getContext()
                .getDrawable(avatarBackgroundRes(item.id));
        avatarView.setBackground(background);
        avatarView.setImageResource(avatarIconRes(item.id));
        avatarView.setTag(R.id.tag_cosmetic_avatar, item.id);
        trackAnimatedDrawables(avatarView);
        startDrawable(background, avatarView.isAttachedToWindow());
    }

    /** Убирает анимированный фон аватара-косметики. */
    public static void clearAvatarDecor(ImageView avatarView) {
        if (avatarView == null) {
            return;
        }
        stopDrawable(avatarView.getBackground());
        applyCircleClip(avatarView);
        avatarView.setBackground(null);
        avatarView.setTag(R.id.tag_cosmetic_avatar, null);
    }

    /** Статичная картинка аватара-косметики для сообщений чата. */
    public static Bitmap createAvatarBitmap(Context context, String avatarId, int sizePx) {
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Path circle = new Path();
        circle.addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW);
        canvas.clipPath(circle);
        int backgroundRes = avatarBackgroundRes(avatarId);
        if (backgroundRes != 0) {
            Drawable background = context.getDrawable(backgroundRes);
            if (background != null) {
                background.setBounds(0, 0, sizePx, sizePx);
                background.draw(canvas);
            }
        }
        int iconRes = avatarIconRes(avatarId);
        if (iconRes != 0) {
            Drawable icon = context.getDrawable(iconRes);
            if (icon != null) {
                icon.setBounds(0, 0, sizePx, sizePx);
                icon.draw(canvas);
            }
        }
        return bitmap;
    }

    /** Серая статичная версия косметики (форма рамки или фона аватара,
     *  без анимации) для превью заблокированных предметов. */
    public static Drawable createLockedDrawable(Context context, String itemId, int sizePx) {
        if (context == null || TextUtils.isEmpty(itemId) || sizePx <= 0) {
            return null;
        }
        Item item = findItem(itemId);
        if (item == null) {
            return null;
        }
        int res = item.isFrame() ? frameDrawableRes(item.id) : avatarBackgroundRes(item.id);
        if (res == 0) {
            return null;
        }
        Drawable source = context.getDrawable(res);
        if (source == null) {
            return null;
        }
        Bitmap buffer = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        source.setBounds(0, 0, sizePx, sizePx);
        source.draw(new Canvas(buffer));
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        Bitmap grey = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        new Canvas(grey).drawBitmap(buffer, 0f, 0f, paint);
        return new BitmapDrawable(context.getResources(), grey);
    }

    /** Ресурс анимированной рамки. 0, если рамка неизвестна. */
    public static int frameDrawableRes(String frameId) {
        Item item = findItem(frameId);
        if (item == null || !item.isFrame()) {
            return 0;
        }
        switch (item.id) {
            case "frame_gold":
                return R.drawable.frame_gold;
            case "frame_neon":
                return R.drawable.frame_neon;
            case "frame_flame":
                return R.drawable.frame_flame;
            case "frame_cosmos":
                return R.drawable.frame_cosmos;
            case "frame_alien":
                return R.drawable.frame_alien;
            case "frame_crystal":
                return R.drawable.frame_crystal;
            case "frame_lightning":
                return R.drawable.frame_lightning;
            case "frame_rainbow":
                return R.drawable.frame_rainbow;
            case "frame_ice":
                return R.drawable.frame_ice;
            case "frame_fire":
                return R.drawable.frame_fire;
            default:
                return 0;
        }
    }

    private static Drawable createFrameDrawable(Context context, String frameId) {
        int res = frameDrawableRes(frameId);
        if (res == 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), res, options);
        if (bitmap == null) {
            Drawable vector = context.getDrawable(res);
            if (vector == null) {
                return null;
            }
            int size = 512;
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            vector.setBounds(0, 0, size, size);
            vector.draw(new Canvas(bitmap));
        }
        boolean rotates = "frame_neon".equals(frameId)
                || "frame_cosmos".equals(frameId)
                || "frame_lightning".equals(frameId)
                || "frame_rainbow".equals(frameId);
        return new AnimatedFrameDrawable(bitmap, rotates);
    }

    /** Ресурс анимированного фона аватара. 0, если аватар неизвестен. */
    public static int avatarBackgroundRes(String avatarId) {
        Item item = findItem(avatarId);
        if (item == null || !item.isAvatar()) {
            return 0;
        }
        switch (item.id) {
            case "avatar_hacker":
                return R.drawable.bg_avatar_hacker;
            case "avatar_wolf":
                return R.drawable.bg_avatar_wolf;
            case "avatar_ai":
                return R.drawable.bg_avatar_ai;
            case "avatar_astronaut":
                return R.drawable.bg_avatar_astronaut;
            case "avatar_lion":
                return R.drawable.bg_avatar_lion;
            default:
                return 0;
        }
    }

    /** Ресурс иконки аватара. 0, если аватар неизвестен. */
    public static int avatarIconRes(String avatarId) {
        Item item = findItem(avatarId);
        if (item == null || !item.isAvatar()) {
            return 0;
        }
        switch (item.id) {
            case "avatar_hacker":
                return R.drawable.avatar_hacker;
            case "avatar_wolf":
                return R.drawable.avatar_wolf;
            case "avatar_ai":
                return R.drawable.avatar_ai;
            case "avatar_astronaut":
                return R.drawable.avatar_astronaut;
            case "avatar_lion":
                return R.drawable.avatar_lion;
            default:
                return 0;
        }
    }

    /** Слушатель, который останавливает анимации у откреплённых вью
     *  и запускает их снова при возврате на экран. */
    private static final View.OnAttachStateChangeListener ANIM_LISTENER =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    startDrawable(v.getForeground(), true);
                    startDrawable(v.getBackground(), true);
                    if (v instanceof ImageView) {
                        startDrawable(((ImageView) v).getDrawable(), true);
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    stopDrawable(v.getForeground());
                    stopDrawable(v.getBackground());
                    if (v instanceof ImageView) {
                        stopDrawable(((ImageView) v).getDrawable());
                    }
                }
            };

    private static void trackAnimatedDrawables(View view) {
        view.removeOnAttachStateChangeListener(ANIM_LISTENER);
        view.addOnAttachStateChangeListener(ANIM_LISTENER);
    }

    private static void startDrawable(Drawable drawable, boolean attached) {
        if (attached && drawable instanceof Animatable) {
            ((Animatable) drawable).start();
        }
    }

    private static void stopDrawable(Drawable drawable) {
        if (drawable instanceof Animatable) {
            ((Animatable) drawable).stop();
        }
    }

    /** Обрезает аватар по кругу, сохраняя рамку поверх изображения. */
    public static void applyCircleClip(View view) {
        if (view == null) {
            return;
        }
        view.setOutlineProvider(CIRCLE_OUTLINE_PROVIDER);
        view.setClipToOutline(true);
    }

    private static final ViewOutlineProvider CIRCLE_OUTLINE_PROVIDER =
            new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    int width = view.getWidth();
                    int height = view.getHeight();
                    int size = Math.min(width, height);
                    int left = (width - size) / 2;
                    int top = (height - size) / 2;
                    outline.setOval(left, top, left + size, top + size);
                }
            };

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
