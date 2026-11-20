package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class AvatarStore {

    /** Ключ в SharedPreferences, где хранится URI выбранного из галереи фото. */
    public static final String KEY_CUSTOM_AVATAR_URI = "custom_avatar_uri";

    private static final String FILE_NAME = "profile_avatar.png";
    private static final int AVATAR_SIZE = 512;

    private AvatarStore() {
    }

    public static File avatarFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static boolean hasAvatar(Context context) {
        File file = avatarFile(context);
        return file.isFile() && file.length() > 0;
    }

    /** URI последнего выбранного из галереи фото или null, если своё фото не выбирали. */
    public static Uri getCustomAvatarUri(Context context) {
        if (context == null) {
            return null;
        }
        String raw = prefs(context).getString(KEY_CUSTOM_AVATAR_URI, "");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return Uri.parse(raw);
    }

    public static Bitmap load(Context context) {
        if (context == null) {
            return null;
        }
        Bitmap stored = decodeStored(context);
        if (stored != null) {
            return stored;
        }
        Uri customUri = getCustomAvatarUri(context);
        if (customUri != null && save(context, customUri)) {
            stored = decodeStored(context);
        }
        return stored;
    }

    public static boolean save(Context context, Uri uri) {
        if (context == null || uri == null) {
            return false;
        }
        Bitmap source = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            if (maxSide <= 0) {
                return false;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            while (maxSide / (sample * 2) >= AVATAR_SIZE) {
                sample *= 2;
            }
            options.inSampleSize = sample;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                source = BitmapFactory.decodeStream(in, null, options);
            }
            if (source == null) {
                return false;
            }

            Bitmap square = cropToSquare(source, AVATAR_SIZE);
            File target = avatarFile(context);
            File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (!square.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    return false;
                }
            }
            if (target.exists() && !target.delete()) {
                return false;
            }
            if (!temp.renameTo(target)) {
                return false;
            }
            prefs(context).edit()
                    .putString(KEY_CUSTOM_AVATAR_URI, uri.toString()).apply();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (source != null) {
                source.recycle();
            }
        }
    }

    private static Bitmap decodeStored(Context context) {
        if (!hasAvatar(context)) {
            return null;
        }
        try {
            Bitmap src = BitmapFactory.decodeFile(avatarFile(context).getAbsolutePath());
            return src == null ? null : circular(src);
        } catch (Throwable t) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static Bitmap cropToSquare(Bitmap source, int targetSize) {
        int width = source.getWidth();
        int height = source.getHeight();
        int side = Math.min(width, height);
        int left = (width - side) / 2;
        int top = (height - side) / 2;

        Bitmap output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Rect src = new Rect(left, top, left + side, top + side);
        RectF dst = new RectF(0, 0, targetSize, targetSize);
        canvas.drawBitmap(source, src, dst, paint);
        return output;
    }

    private static Bitmap circular(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int side = Math.min(width, height);
        int left = (width - side) / 2;
        int top = (height - side) / 2;

        Bitmap output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        Rect src = new Rect(left, top, left + side, top + side);
        RectF dst = new RectF(0, 0, side, side);
        canvas.drawBitmap(source, src, dst, paint);
        return output;
    }
}
