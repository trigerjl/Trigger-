package com.triger.poc;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public final class ChatDatabase extends SQLiteOpenHelper {

    public static final String SENDER_SELF = "me";

    public static final int STATUS_SENDING = 0;
    public static final int STATUS_DELIVERED = 1;
    public static final int STATUS_FAILED = 2;
    /** Исходящее сообщение прочитано собеседником (read receipt). */
    public static final int STATUS_READ = 3;

    /** Обычное текстовое сообщение. */
    public static final String TYPE_TEXT = "text";
    /** Голосовое сообщение: содержимое в audioPath. */
    public static final String TYPE_AUDIO = "audio";

    private static final String DB_NAME = "chat_history.db";
    private static final int DB_VERSION = 7;

    private static final String TABLE_MESSAGES = "messages";
    private static final String TABLE_NEWS = "news";
    private static final String NEWS_AUTHOR_ADDRESS = "authorAddress";
    private static final String NEWS_AUTHOR_NICKNAME = "authorNickname";
    private static final String NEWS_SIGNATURE = "signature";
    private static final int MAX_NEWS = 100;
    private static final String COL_ID = "id";
    private static final String COL_PEER_ID = "peerId";
    private static final String COL_SENDER_ID = "senderId";
    private static final String COL_TEXT = "text";
    private static final String COL_TIMESTAMP = "timestamp";
    private static final String COL_IS_READ = "is_read";
    private static final String COL_STATUS = "status";
    private static final String COL_REPLY_TO_ID = "reply_to_id";
    private static final String COL_REPLY_PREVIEW = "reply_preview";
    private static final String COL_MESSAGE_TYPE = "messageType";
    private static final String COL_AUDIO_PATH = "audioPath";
    private static final String COL_ORIGINAL_TEXT = "original_text";
    private static final String COL_SOURCE_LANG = "source_lang";
    private static final String COL_TRANSLATED = "translated";

    private static volatile ChatDatabase instance;

    private ChatDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    public static ChatDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (ChatDatabase.class) {
                if (instance == null) {
                    instance = new ChatDatabase(context);
                }
            }
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_MESSAGES + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_PEER_ID + " TEXT NOT NULL, "
                + COL_SENDER_ID + " TEXT NOT NULL, "
                + COL_TEXT + " TEXT NOT NULL, "
                + COL_TIMESTAMP + " INTEGER NOT NULL, "
                + COL_IS_READ + " INTEGER NOT NULL DEFAULT 0, "
                + COL_STATUS + " INTEGER NOT NULL DEFAULT " + STATUS_DELIVERED + ", "
                + COL_REPLY_TO_ID + " INTEGER NOT NULL DEFAULT 0, "
                + COL_REPLY_PREVIEW + " TEXT NOT NULL DEFAULT '', "
                + COL_MESSAGE_TYPE + " TEXT NOT NULL DEFAULT '" + TYPE_TEXT + "', "
                + COL_AUDIO_PATH + " TEXT NOT NULL DEFAULT '', "
                + COL_ORIGINAL_TEXT + " TEXT NOT NULL DEFAULT '', "
                + COL_SOURCE_LANG + " TEXT NOT NULL DEFAULT '', "
                + COL_TRANSLATED + " INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_messages_peer_time ON " + TABLE_MESSAGES
                + " (" + COL_PEER_ID + ", " + COL_TIMESTAMP + ")");
        createNewsTable(db);
    }

    private static void createNewsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NEWS + " ("
                + COL_ID + " TEXT PRIMARY KEY, "
                + NEWS_AUTHOR_ADDRESS + " TEXT NOT NULL, "
                + NEWS_AUTHOR_NICKNAME + " TEXT NOT NULL DEFAULT '', "
                + COL_TEXT + " TEXT NOT NULL, "
                + COL_TIMESTAMP + " INTEGER NOT NULL, "
                + NEWS_SIGNATURE + " TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_news_time ON " + TABLE_NEWS
                + " (" + COL_TIMESTAMP + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN "
                    + COL_IS_READ + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE " + TABLE_MESSAGES + " SET " + COL_IS_READ + " = 1 WHERE "
                    + COL_SENDER_ID + " = '" + SENDER_SELF + "'");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN "
                    + COL_STATUS + " INTEGER NOT NULL DEFAULT " + STATUS_DELIVERED);
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN "
                    + COL_REPLY_TO_ID + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN "
                    + COL_REPLY_PREVIEW + " TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN "
                    + COL_MESSAGE_TYPE + " TEXT NOT NULL DEFAULT '" + TYPE_TEXT + "'");
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN "
                    + COL_AUDIO_PATH + " TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN "
                    + COL_ORIGINAL_TEXT + " TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN "
                    + COL_SOURCE_LANG + " TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN "
                    + COL_TRANSLATED + " INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 7) {
            createNewsTable(db);
        }
    }

    public long saveMessage(String peerId, String senderId, String text) {
        return saveMessage(peerId, senderId, text, STATUS_DELIVERED);
    }

    public long saveMessage(String peerId, String senderId, String text, int status) {
        return insert(peerId, senderId, text, true, status, 0, "", TYPE_TEXT, "");
    }

    public long saveMessage(String peerId, String senderId, String text, int status,
                            long replyToId, String replyPreview) {
        return insert(peerId, senderId, text, true, status, replyToId, replyPreview,
                TYPE_TEXT, "");
    }

    /** Сохраняет исходящее голосовое сообщение (файл уже записан по audioPath). */
    public long saveAudioMessage(String peerId, String senderId, String audioPath,
                                 int status, long replyToId, String replyPreview) {
        return insert(peerId, senderId, "", true, status, replyToId, replyPreview,
                TYPE_AUDIO, audioPath);
    }

    /** Сохраняет входящее сообщение как непрочитанное. */
    public long saveIncomingMessage(String peerId, String senderId, String text) {
        return saveIncomingMessage(peerId, senderId, text, 0, "");
    }

    public long saveIncomingMessage(String peerId, String senderId, String text,
                                    long replyToId, String replyPreview) {
        return insert(peerId, senderId, text, false, STATUS_DELIVERED,
                replyToId, replyPreview, TYPE_TEXT, "");
    }

    /** Сохраняет входящее голосовое сообщение как непрочитанное. */
    public long saveIncomingAudioMessage(String peerId, String senderId, String audioPath,
                                         long replyToId, String replyPreview) {
        return insert(peerId, senderId, "", false, STATUS_DELIVERED,
                replyToId, replyPreview, TYPE_AUDIO, audioPath);
    }

    private long insert(String peerId, String senderId, String text, boolean read,
                        int status, long replyToId, String replyPreview,
                        String messageType, String audioPath) {
        if (TYPE_TEXT.equals(messageType) && ChatActivity.isTechnicalChatText(text)) {
            return -1L;
        }
        ContentValues values = new ContentValues();
        values.put(COL_PEER_ID, peerId == null ? "" : peerId);
        values.put(COL_SENDER_ID, senderId == null ? "" : senderId);
        values.put(COL_TEXT, text == null ? "" : text);
        values.put(COL_TIMESTAMP, System.currentTimeMillis());
        values.put(COL_IS_READ, read ? 1 : 0);
        values.put(COL_STATUS, status);
        values.put(COL_REPLY_TO_ID, Math.max(replyToId, 0));
        values.put(COL_REPLY_PREVIEW, replyPreview == null ? "" : replyPreview);
        values.put(COL_MESSAGE_TYPE,
                TYPE_AUDIO.equals(messageType) ? TYPE_AUDIO : TYPE_TEXT);
        values.put(COL_AUDIO_PATH, audioPath == null ? "" : audioPath);
        return getWritableDatabase().insert(TABLE_MESSAGES, null, values);
    }

    public int updateMessageStatus(long messageId, int status) {
        ContentValues values = new ContentValues();
        values.put(COL_STATUS, status);
        return getWritableDatabase().update(TABLE_MESSAGES, values,
                COL_ID + " = ?", new String[]{String.valueOf(messageId)});
    }

    /** Помечает все сообщения чата прочитанными. */
    public int markMessagesRead(String peerId) {
        if (peerId == null || peerId.isEmpty()) {
            return 0;
        }
        ContentValues values = new ContentValues();
        values.put(COL_IS_READ, 1);
        return getWritableDatabase().update(TABLE_MESSAGES, values,
                COL_PEER_ID + " = ? AND " + COL_IS_READ + " = 0",
                new String[]{peerId});
    }

    @NonNull
    public List<Message> getMessages(String peerId) {
        List<Message> result = new ArrayList<>();
        String where = COL_PEER_ID + " = ?";
        String[] args = new String[]{peerId == null ? "" : peerId};
        String order = COL_TIMESTAMP + " ASC, " + COL_ID + " ASC";
        try (Cursor cursor = getReadableDatabase().query(TABLE_MESSAGES, null,
                where, args, null, null, order)) {
            int idIndex = cursor.getColumnIndexOrThrow(COL_ID);
            int peerIndex = cursor.getColumnIndexOrThrow(COL_PEER_ID);
            int senderIndex = cursor.getColumnIndexOrThrow(COL_SENDER_ID);
            int textIndex = cursor.getColumnIndexOrThrow(COL_TEXT);
            int timeIndex = cursor.getColumnIndexOrThrow(COL_TIMESTAMP);
            int readIndex = cursor.getColumnIndexOrThrow(COL_IS_READ);
            int statusIndex = cursor.getColumnIndexOrThrow(COL_STATUS);
            int replyToIndex = cursor.getColumnIndexOrThrow(COL_REPLY_TO_ID);
            int replyPreviewIndex = cursor.getColumnIndexOrThrow(COL_REPLY_PREVIEW);
            int typeIndex = cursor.getColumnIndexOrThrow(COL_MESSAGE_TYPE);
            int audioIndex = cursor.getColumnIndexOrThrow(COL_AUDIO_PATH);
            while (cursor.moveToNext()) {
                result.add(new Message(
                        cursor.getLong(idIndex),
                        cursor.getString(peerIndex),
                        cursor.getString(senderIndex),
                        cursor.getString(textIndex),
                        cursor.getLong(timeIndex),
                        cursor.getInt(readIndex) != 0,
                        cursor.getInt(statusIndex),
                        cursor.getLong(replyToIndex),
                        cursor.getString(replyPreviewIndex),
                        cursor.getString(typeIndex),
                        cursor.getString(audioIndex)));
            }
        }
        return result;
    }

    @NonNull
    public List<Message> getAllMessages() {
        List<Message> result = new ArrayList<>();
        String order = COL_TIMESTAMP + " ASC, " + COL_ID + " ASC";
        try (Cursor cursor = getReadableDatabase().query(TABLE_MESSAGES, null,
                null, null, null, null, order)) {
            while (cursor.moveToNext()) {
                result.add(rowToMessage(cursor));
            }
        }
        return result;
    }

    /** Поиск по тексту сообщений чата (LIKE, без учёта регистра SQLite). */
    @NonNull
    public List<Message> searchMessages(String peerId, String query) {
        List<Message> result = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return result;
        }
        String escaped = query.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String where = COL_PEER_ID + " = ? AND " + COL_TEXT + " LIKE ? ESCAPE '\\'";
        String[] args = new String[]{
                peerId == null ? "" : peerId,
                "%" + escaped + "%"};
        String order = COL_TIMESTAMP + " DESC, " + COL_ID + " DESC";
        try (Cursor cursor = getReadableDatabase().query(TABLE_MESSAGES, null,
                where, args, null, null, order)) {
            while (cursor.moveToNext()) {
                result.add(rowToMessage(cursor));
            }
        }
        return result;
    }

    @NonNull
    private static Message rowToMessage(Cursor cursor) {
        return new Message(
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_PEER_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_SENDER_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_TEXT)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_READ)) != 0,
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_STATUS)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_REPLY_TO_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_REPLY_PREVIEW)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_MESSAGE_TYPE)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_AUDIO_PATH)));
    }

    public void replaceAllMessages(List<Message> messages) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_MESSAGES, null, null);
            if (messages != null) {
                for (Message message : messages) {
                    if (message == null || (!message.isAudio()
                            && ChatActivity.isTechnicalChatText(message.text))) {
                        continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put(COL_ID, Math.max(message.id, 0));
                    values.put(COL_PEER_ID, message.peerId == null ? "" : message.peerId);
                    values.put(COL_SENDER_ID, message.senderId == null ? "" : message.senderId);
                    values.put(COL_TEXT, message.text == null ? "" : message.text);
                    values.put(COL_TIMESTAMP, message.timestamp);
                    values.put(COL_IS_READ, message.isRead ? 1 : 0);
                    values.put(COL_STATUS, message.status);
                    values.put(COL_REPLY_TO_ID, Math.max(message.replyToId, 0));
                    values.put(COL_REPLY_PREVIEW,
                            message.replyPreview == null ? "" : message.replyPreview);
                    values.put(COL_MESSAGE_TYPE, message.isAudio() ? TYPE_AUDIO : TYPE_TEXT);
                    values.put(COL_AUDIO_PATH, message.audioPath == null ? "" : message.audioPath);
                    db.insertWithOnConflict(TABLE_MESSAGES, null, values,
                            SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public Message getLastMessage(String peerId) {
        String where = COL_PEER_ID + " = ?";
        String[] args = new String[]{peerId == null ? "" : peerId};
        String order = COL_TIMESTAMP + " DESC, " + COL_ID + " DESC";
        try (Cursor cursor = getReadableDatabase().query(TABLE_MESSAGES, null,
                where, args, null, null, order, "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return rowToMessage(cursor);
        }
    }

    /** Последнее сообщение, которое можно показать в предпросмотре чата. */
    public Message getLastDisplayMessage(String peerId) {
        String where = COL_PEER_ID + " = ?";
        String[] args = new String[]{peerId == null ? "" : peerId};
        String order = COL_TIMESTAMP + " DESC, " + COL_ID + " DESC";
        try (Cursor cursor = getReadableDatabase().query(TABLE_MESSAGES, null,
                where, args, null, null, order, "20")) {
            while (cursor.moveToNext()) {
                Message message = rowToMessage(cursor);
                if (!message.isAudio() && ChatActivity.isTechnicalChatText(message.text)) {
                    continue;
                }
                return message;
            }
        }
        return null;
    }

    /**
     * Помечает исходящие сообщения (до момента upTo включительно) прочитанными
     * собеседником. Возвращает число обновлённых строк.
     */
    public int markOutgoingMessagesRead(String peerId, long upToTimestamp) {
        if (peerId == null || peerId.isEmpty() || upToTimestamp <= 0) {
            return 0;
        }
        ContentValues values = new ContentValues();
        values.put(COL_STATUS, STATUS_READ);
        return getWritableDatabase().update(TABLE_MESSAGES, values,
                COL_PEER_ID + " = ? AND " + COL_SENDER_ID + " = ? AND "
                        + COL_TIMESTAMP + " <= ? AND " + COL_STATUS + " IN ("
                        + STATUS_SENDING + ", " + STATUS_DELIVERED + ")",
                new String[]{peerId, SENDER_SELF, String.valueOf(upToTimestamp)});
    }

    /** Метка времени последнего непрочитанного входящего сообщения чата (0 — нет таких). */
    public long getLastUnreadIncomingTimestamp(String peerId) {
        if (peerId == null || peerId.isEmpty()) {
            return 0L;
        }
        String sql = "SELECT MAX(" + COL_TIMESTAMP + ") FROM " + TABLE_MESSAGES
                + " WHERE " + COL_PEER_ID + " = ? AND " + COL_IS_READ + " = 0"
                + " AND " + COL_SENDER_ID + " != ?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql,
                new String[]{peerId, SENDER_SELF})) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        }
        return 0L;
    }

    @NonNull
    public List<String> getConversationPeerIds() {
        List<String> result = new ArrayList<>();
        String sql = "SELECT " + COL_PEER_ID + ", MAX(" + COL_TIMESTAMP + ") AS last_ts"
                + " FROM " + TABLE_MESSAGES
                + " WHERE " + COL_PEER_ID + " IS NOT NULL AND " + COL_PEER_ID + " != ''"
                + " GROUP BY " + COL_PEER_ID
                + " ORDER BY last_ts DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            int peerIndex = cursor.getColumnIndexOrThrow(COL_PEER_ID);
            while (cursor.moveToNext()) {
                String peer = cursor.getString(peerIndex);
                if (peer != null && !peer.isEmpty()) {
                    result.add(peer);
                }
            }
        }
        return result;
    }

    public int deleteChat(String peerId) {
        if (peerId == null || peerId.isEmpty()) {
            return 0;
        }
        List<String> audioPaths = new ArrayList<>();
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.query(TABLE_MESSAGES,
                new String[]{COL_AUDIO_PATH},
                COL_PEER_ID + " = ? AND " + COL_MESSAGE_TYPE + " = ? AND " + COL_AUDIO_PATH + " != ''",
                new String[]{peerId, TYPE_AUDIO}, null, null, null)) {
            int audioIndex = cursor.getColumnIndexOrThrow(COL_AUDIO_PATH);
            while (cursor.moveToNext()) {
                String path = cursor.getString(audioIndex);
                if (path != null && !path.isEmpty()) {
                    audioPaths.add(path);
                }
            }
        } catch (Throwable ignored) {
        }
        int deleted = db.delete(TABLE_MESSAGES, COL_PEER_ID + " = ?",
                new String[]{peerId});
        for (String path : audioPaths) {
            try {
                java.io.File file = new java.io.File(path);
                if (file.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            } catch (Throwable ignored) {
            }
        }
        return deleted;
    }

    public void saveNews(NewsItem item) {
        if (item == null || item.getId().isEmpty() || item.getText().isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_ID, item.getId());
        values.put(NEWS_AUTHOR_ADDRESS, item.getAuthorAddress());
        values.put(NEWS_AUTHOR_NICKNAME, item.getAuthorNickname());
        values.put(COL_TEXT, item.getText());
        values.put(COL_TIMESTAMP, item.getTimestamp());
        values.put(NEWS_SIGNATURE, item.getSignature());
        db.insertWithOnConflict(TABLE_NEWS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        db.execSQL("DELETE FROM " + TABLE_NEWS + " WHERE " + COL_ID + " NOT IN ("
                + "SELECT " + COL_ID + " FROM " + TABLE_NEWS
                + " ORDER BY " + COL_TIMESTAMP + " DESC, " + COL_ID + " DESC LIMIT "
                + MAX_NEWS + ")");
    }

    public boolean hasNews(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        try (Cursor cursor = getReadableDatabase().query(TABLE_NEWS,
                new String[]{COL_ID}, COL_ID + " = ?", new String[]{id},
                null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    @NonNull
    public List<NewsItem> getRecentNews(int limit) {
        List<NewsItem> result = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(limit, MAX_NEWS));
        String order = COL_TIMESTAMP + " DESC, " + COL_ID + " DESC";
        try (Cursor cursor = getReadableDatabase().query(TABLE_NEWS, null,
                null, null, null, null, order, String.valueOf(safeLimit))) {
            while (cursor.moveToNext()) {
                result.add(new NewsItem(
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(NEWS_AUTHOR_ADDRESS)),
                        cursor.getString(cursor.getColumnIndexOrThrow(NEWS_AUTHOR_NICKNAME)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_TEXT)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        cursor.getString(cursor.getColumnIndexOrThrow(NEWS_SIGNATURE))));
            }
        }
        return result;
    }

    public static final class Message {
        public final long id;
        public final String peerId;
        public final String senderId;
        public final String text;
        public final long timestamp;
        public final boolean isRead;
        public final int status;
        public final long replyToId;
        public final String replyPreview;
        public final String messageType;
        public final String audioPath;

        Message(long id, String peerId, String senderId, String text, long timestamp,
                boolean isRead, int status, long replyToId, String replyPreview,
                String messageType, String audioPath) {
            this.id = id;
            this.peerId = peerId;
            this.senderId = senderId;
            this.text = text;
            this.timestamp = timestamp;
            this.isRead = isRead;
            this.status = status;
            this.replyToId = replyToId;
            this.replyPreview = replyPreview == null ? "" : replyPreview;
            this.messageType = TYPE_AUDIO.equals(messageType) ? TYPE_AUDIO : TYPE_TEXT;
            this.audioPath = audioPath == null ? "" : audioPath;
        }

        public boolean isOutgoing() {
            return SENDER_SELF.equals(senderId);
        }

        public boolean isAudio() {
            return TYPE_AUDIO.equals(messageType);
        }
    }
}
