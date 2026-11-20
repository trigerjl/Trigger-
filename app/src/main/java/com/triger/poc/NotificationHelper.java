package com.triger.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

public final class NotificationHelper {

    private static final String CHANNEL_MESSAGES = "triger_messages";
    private static final String CHANNEL_MESSAGES_SILENT = "triger_messages_silent";
    private static final String CHANNEL_MINING = "triger_mining";
    private static final String CHANNEL_MINING_SILENT = "triger_mining_silent";
    private static final int MESSAGE_NOTIFICATION_BASE = 1000;
    private static final int REWARD_NOTIFICATION_ID = 2001;
    private static final int UNLOCK_NOTIFICATION_BASE = 3000;
    private static final int DISPUTE_NOTIFICATION_BASE = 4000;
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    private NotificationHelper() {
    }

    public static void showMessageNotification(Context context, String peerName, String messageText) {
        showMessageNotification(context, null, peerName, messageText);
    }

    public static void showMessageNotification(Context context, String peerId,
                                               String peerName, String messageText) {
        if (context == null) {
            return;
        }
        if (!TextUtils.isEmpty(peerId)) {
            try {
                String selfEndpoint = IrohNode.getInstance().getSelfEndpointId();
                if (!TextUtils.isEmpty(selfEndpoint)
                        && selfEndpoint.equals(peerId.trim())) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        Context appContext = context.getApplicationContext();
        createChannels(appContext);

        String name = TextUtils.isEmpty(peerName)
                ? appContext.getString(R.string.notification_contact_fallback) : peerName;
        String text = messageText == null ? "" : messageText;
        boolean dnd = SettingsActivity.isDndEnabled(appContext);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                appContext, dnd ? CHANNEL_MESSAGES_SILENT : CHANNEL_MESSAGES)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(appContext.getString(
                        R.string.notification_message_title_format, name))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(dnd ? NotificationCompat.PRIORITY_LOW
                        : NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(appContext, peerId));
        applyDnd(builder, dnd);

        notifySafely(appContext, messageId(TextUtils.isEmpty(peerId) ? peerName : peerId),
                builder.build());
    }

    public static void cancelMessageNotification(Context context, String peerId) {
        if (context == null || TextUtils.isEmpty(peerId)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        try {
            NotificationManagerCompat.from(appContext).cancel(messageId(peerId));
        } catch (Throwable ignored) {
        }
    }

    public static void showDisputeNotification(Context context, String disputeId,
                                               String messageText) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        createChannels(appContext);

        String text = messageText == null ? "" : messageText;
        boolean dnd = SettingsActivity.isDndEnabled(appContext);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                appContext, dnd ? CHANNEL_MESSAGES_SILENT : CHANNEL_MESSAGES)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(appContext.getString(R.string.dispute_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(dnd ? NotificationCompat.PRIORITY_LOW
                        : NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(appContext, null));
        applyDnd(builder, dnd);

        int id = DISPUTE_NOTIFICATION_BASE + ((
                disputeId == null ? "" : disputeId).hashCode() & 0x7fffffff) % 1000;
        notifySafely(appContext, id, builder.build());
    }

    public static void showMiningRewardNotification(Context context, double amount) {
        if (context == null || amount <= 0.0) {
            return;
        }
        Context appContext = context.getApplicationContext();
        createChannels(appContext);
        boolean dnd = SettingsActivity.isDndEnabled(appContext);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                appContext, dnd ? CHANNEL_MINING_SILENT : CHANNEL_MINING)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(appContext.getString(
                        R.string.notification_mining_reward_title_format,
                        Tokenomics.formatAmount(amount)))
                .setContentText(appContext.getString(R.string.notification_mining_reward_text))
                .setPriority(dnd ? NotificationCompat.PRIORITY_LOW
                        : NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(appContext, null));
        applyDnd(builder, dnd);

        notifySafely(appContext, REWARD_NOTIFICATION_ID, builder.build());
    }

    public static void showCosmeticUnlockNotification(Context context, String itemName) {
        if (context == null || TextUtils.isEmpty(itemName)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        createChannels(appContext);
        boolean dnd = SettingsActivity.isDndEnabled(appContext);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                appContext, dnd ? CHANNEL_MINING_SILENT : CHANNEL_MINING)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(appContext.getString(
                        R.string.cosmetics_unlock_notification, itemName))
                .setContentText(appContext.getString(R.string.cosmetics_achievements))
                .setPriority(dnd ? NotificationCompat.PRIORITY_LOW
                        : NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(appContext, null));
        applyDnd(builder, dnd);

        int id = UNLOCK_NOTIFICATION_BASE + (itemName.hashCode() & 0x7fffffff) % 1000;
        notifySafely(appContext, id, builder.build());
    }

    private static void applyDnd(NotificationCompat.Builder builder, boolean dnd) {
        if (dnd) {
            builder.setSound(null);
            builder.setVibrate(null);
            builder.setDefaults(0);
            builder.setOnlyAlertOnce(true);
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);
        }
    }

    private static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        NotificationChannel messages = new NotificationChannel(
                CHANNEL_MESSAGES, context.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription(context.getString(
                R.string.notification_channel_messages_desc));
        messages.enableVibration(true);
        messages.setShowBadge(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            messages.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        }
        manager.createNotificationChannel(messages);

        NotificationChannel messagesSilent = new NotificationChannel(
                CHANNEL_MESSAGES_SILENT,
                context.getString(R.string.notification_channel_messages_silent),
                NotificationManager.IMPORTANCE_LOW);
        messagesSilent.setDescription(context.getString(
                R.string.notification_channel_messages_desc));
        messagesSilent.setSound(null, null);
        messagesSilent.enableVibration(false);
        manager.createNotificationChannel(messagesSilent);

        NotificationChannel mining = new NotificationChannel(
                CHANNEL_MINING, context.getString(R.string.notification_channel_mining),
                NotificationManager.IMPORTANCE_DEFAULT);
        mining.setDescription(context.getString(
                R.string.notification_channel_mining_desc));
        mining.enableVibration(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mining.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        }
        manager.createNotificationChannel(mining);

        NotificationChannel miningSilent = new NotificationChannel(
                CHANNEL_MINING_SILENT,
                context.getString(R.string.notification_channel_mining_silent),
                NotificationManager.IMPORTANCE_LOW);
        miningSilent.setDescription(context.getString(
                R.string.notification_channel_mining_desc));
        miningSilent.setSound(null, null);
        miningSilent.enableVibration(false);
        manager.createNotificationChannel(miningSilent);
    }

    private static int messageId(String peerName) {
        int hash = peerName == null ? 0 : peerName.hashCode();
        return MESSAGE_NOTIFICATION_BASE + ((hash & 0x7fffffff) % 1000);
    }

    private static PendingIntent openAppIntent(Context context, String peerId) {
        Intent intent;
        int requestCode = 0;
        if (TextUtils.isEmpty(peerId)) {
            intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        } else {
            intent = new Intent(context, ChatActivity.class);
            intent.putExtra(ChatActivity.EXTRA_PEER_ID, peerId);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            requestCode = peerId.hashCode();
        }
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notifySafely(Context context, int id, android.app.Notification notification) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return;
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification);
        } catch (SecurityException ignored) {
        }
    }
}
