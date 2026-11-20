package com.triger.poc;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";
    private static final String PREF_HAS_SEEN_SWIPE_HINT = "has_seen_swipe_hint";
    private static final String KEY_DRAFT_PREFIX = "draft_";
    private static final int SEARCH_RESULT_LIMIT = 50;
    private static final int REQUEST_MIC_PERMISSION = 1203;
    private static final long MIN_VOICE_DURATION_MS = 1000L;
    private static final long MAX_VOICE_DURATION_MS = 60_000L;
    private static final long TYPING_SEND_INTERVAL_MS = 1000L;
    private static final long TYPING_TIMEOUT_MS = 3000L;
    private static final Pattern WALLET_ADDRESS_PATTERN =
            Pattern.compile("(?i)0x[a-f0-9]{40}");

    /** Технические сетевые сообщения, которые не должны попадать в чат. */
    public static boolean isTechnicalChatText(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        if (TransferManager.isTechnicalTransferPayload(text)) {
            return true;
        }
        String value = text.trim();
        return value.contains("REQUEST_CHAIN")
                || value.contains("GET_LAST_BLOCK")
                || value.contains("__triger_chain_req__")
                || value.contains("__triger_chain_resp__")
                || value.contains("__triger_ping__")
                || value.contains("__triger_pong__")
                || value.equalsIgnoreCase("PING")
                || value.equalsIgnoreCase("PONG");
    }

    private interface AddressClickListener {
        void onAddressClick(String address);
    }

    private interface QuoteClickListener {
        void onQuoteClick(long replyToId, String replyPreview);
    }

    private interface MessageLongClickListener {
        void onMessageLongClick(ChatMessage message);
    }

    private interface AudioPlayClickListener {
        void onAudioPlayClick(ChatMessage message);
    }

    public static final String EXTRA_PEER_ID = "peer_id";

    private static volatile boolean active = false;
    private static volatile String activePeerId = "";

    public static boolean isActive() {
        return active;
    }

    public static boolean isActiveFor(String peerId) {
        return active && peerId != null && peerId.equals(activePeerId);
    }

    private String peerId = "";
    private RecyclerView recyclerMessages;
    private EditText etInput;
    private View replyComposeBar;
    private TextView replyComposeText;
    private ChatMessage replyingTo;
    private MessageAdapter adapter;
    private ChatDatabase chatDatabase;
    private TextView titleView;
    private TextView peerLabelView;
    private ImageView ivChatAvatar;
    private ImageView ivChatFrame;
    private View typingDotsContainer;
    private View typingDot1;
    private View typingDot2;
    private View typingDot3;
    private ValueAnimator typingAnimator;
    private Bitmap ownAvatar;
    private String headerStatus = "";
    private final IrohNode irohNode = IrohNode.getInstance();
    private final Handler profileHandler = new Handler(Looper.getMainLooper());
    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    private boolean peerTyping;
    private boolean typingReported;
    private long lastTypingSentAt;
    private ImageButton btnMic;
    private MediaRecorder voiceRecorder;
    private String voiceRecordingPath;
    private long voiceRecordingStartedAt;
    private MediaPlayer audioPlayer;
    private ChatMessage playingMessage;
    private long lastReadReceiptAt;
    private final Runnable clearPeerTyping = () -> {
        peerTyping = false;
        updateTitle();
    };
    private final Runnable profileRefresh = new Runnable() {
        @Override
        public void run() {
            updateTitle();
            profileHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        findViewById(R.id.btn_chat_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_chat_search).setOnClickListener(v -> showSearchDialog());
        findViewById(R.id.btn_chat_more).setOnClickListener(this::showChatMenu);

        peerId = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_PEER_ID);
        if (peerId == null) {
            peerId = "";
        }
        if (!TextUtils.isEmpty(peerId)) {
            headerStatus = getString(R.string.chat_status_connecting);
        }

        titleView = findViewById(R.id.tv_chat_title);
        peerLabelView = findViewById(R.id.tv_chat_peer);
        ivChatAvatar = findViewById(R.id.iv_chat_avatar);
        ivChatFrame = findViewById(R.id.iv_chat_frame);
        typingDotsContainer = findViewById(R.id.ll_typing_dots);
        typingDot1 = findViewById(R.id.typing_dot_1);
        typingDot2 = findViewById(R.id.typing_dot_2);
        typingDot3 = findViewById(R.id.typing_dot_3);
        refreshOwnAvatar();
        updateTitle();
        renderPeerLabel();
        updateAvatar();

        recyclerMessages = findViewById(R.id.recycler_messages);
        etInput = findViewById(R.id.et_chat_input);
        restoreDraft();
        replyComposeBar = findViewById(R.id.ll_reply_compose);
        replyComposeText = findViewById(R.id.tv_reply_compose_text);
        findViewById(R.id.btn_reply_cancel).setOnClickListener(v -> clearReply());
        View btnSend = findViewById(R.id.btn_chat_send);

        chatDatabase = ChatDatabase.getInstance(this);
        adapter = new MessageAdapter(peerId, ownAvatar, loadHistory(peerId),
                this::showAddressActions, this::scrollToQuoteTarget, this::showMessageActions,
                this::onAudioPlayClick);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recyclerMessages.setLayoutManager(lm);
        recyclerMessages.setAdapter(adapter);
        maybeShowSwipeHint();
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                if (position == RecyclerView.NO_POSITION) {
                    return;
                }
                ChatMessage message = adapter.getItem(position);
                adapter.notifyItemChanged(position);
                if (message != null) {
                    setReplyTo(message);
                }
            }
        }).attachToRecyclerView(recyclerMessages);

        btnSend.setOnClickListener(v -> sendCurrent());
        btnMic = findViewById(R.id.btn_chat_mic);
        btnMic.setOnClickListener(v -> toggleVoiceRecording());
        etInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (TextUtils.isEmpty(s == null ? "" : s.toString().trim())) {
                    stopTyping();
                } else {
                    notifyTypingStarted();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                saveDraft(s == null ? "" : s.toString());
            }
        });

        irohNode.start(getApplicationContext());
        irohNode.startDiscovery();
        irohNode.setOnMessageListener(this::onIncomingMessage);
        irohNode.setOnReadReceiptListener(this::onPeerReadReceipt);
        irohNode.setOnTypingListener(new IrohNode.OnTypingListener() {
            @Override
            public void onTypingStart(String fromEndpointId) {
                onPeerTypingStart(fromEndpointId);
            }

            @Override
            public void onTypingStop(String fromEndpointId) {
                onPeerTypingStop(fromEndpointId);
            }
        });
        Log.i(TAG, "Chat opened: peerId=" + peerId + ", connected="
                + irohNode.getConnectedPeers().contains(peerId));
        profileHandler.post(profileRefresh);
    }

    private void setHeaderStatus(String status) {
        headerStatus = status == null ? "" : status;
        updateTitle();
    }

    private void updateTitle() {
        if (titleView == null) {
            return;
        }
        String title = TextUtils.isEmpty(peerId)
                ? getString(R.string.chat_iroh_title)
                : irohNode.getPeerDisplayName(peerId);
        titleView.setText(title);

        String status;
        if (peerTyping) {
            status = getString(R.string.chat_status_typing);
        } else if (!TextUtils.isEmpty(peerId)
                && irohNode.getConnectedPeers().contains(peerId)) {
            status = getString(R.string.chat_status_online);
        } else {
            status = headerStatus;
        }
        if (peerLabelView != null) {
            peerLabelView.setText(status);
            peerLabelView.setVisibility(TextUtils.isEmpty(status) ? View.GONE : View.VISIBLE);
        }
        if (peerTyping) {
            if (typingDotsContainer != null) {
                typingDotsContainer.setVisibility(View.VISIBLE);
            }
            startTypingAnimation();
        } else {
            if (typingDotsContainer != null) {
                typingDotsContainer.setVisibility(View.GONE);
            }
            stopTypingAnimation();
        }
        updateAvatar();
    }

    private void startTypingAnimation() {
        if (typingAnimator != null && typingAnimator.isRunning()) {
            return;
        }
        typingAnimator = ValueAnimator.ofFloat(0f, 1f);
        typingAnimator.setDuration(900L);
        typingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        typingAnimator.setInterpolator(new LinearInterpolator());
        typingAnimator.addUpdateListener(animation -> {
            float phase = (float) animation.getAnimatedValue();
            setTypingDotAlpha(typingDot1, phase, 0f);
            setTypingDotAlpha(typingDot2, phase, 0.33f);
            setTypingDotAlpha(typingDot3, phase, 0.66f);
        });
        typingAnimator.start();
    }

    private void setTypingDotAlpha(View dot, float phase, float offset) {
        if (dot == null) {
            return;
        }
        float local = (phase - offset + 1f) % 1f;
        dot.setAlpha(0.25f + 0.75f * (float) Math.abs(Math.sin(local * Math.PI)));
    }

    private void stopTypingAnimation() {
        if (typingAnimator != null) {
            typingAnimator.cancel();
            typingAnimator = null;
        }
        if (typingDot1 != null) {
            typingDot1.setAlpha(1f);
        }
        if (typingDot2 != null) {
            typingDot2.setAlpha(1f);
        }
        if (typingDot3 != null) {
            typingDot3.setAlpha(1f);
        }
    }

    private void refreshOwnAvatar() {
        Bitmap custom = AvatarStore.load(this);
        if (custom != null) {
            ownAvatar = custom;
            return;
        }
        String equippedAvatar = CosmeticManager.getEquippedAvatar(this);
        if (!TextUtils.isEmpty(equippedAvatar)) {
            ownAvatar = CosmeticManager.createAvatarBitmap(this, equippedAvatar, 128);
            return;
        }
        String self = irohNode.getSelfEndpointId();
        if (TextUtils.isEmpty(self)) {
            self = irohNode.getEndpointId();
        }
        ownAvatar = generateAvatar(TextUtils.isEmpty(self) ? "triger" : self);
    }

    private void updateAvatar() {
        if (ivChatAvatar == null) {
            return;
        }
        String seed = TextUtils.isEmpty(peerId) ? "triger" : peerId;
        Bitmap bmp = generateAvatar(seed);
        ivChatAvatar.setImageBitmap(bmp);
        CosmeticManager.applyFrame(ivChatFrame, irohNode.getPeerFrame(peerId));
    }

    private Bitmap generateAvatar(String seed) {
        int size = 128;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int color = colorFromSeed(seed);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(size * 0.32f);
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
            String a = parts[0].length() > 0 ? parts[0].substring(0, 1) : "T";
            String b = parts[1].length() > 0 ? parts[1].substring(0, 1) : "R";
            return (a + b).toUpperCase(Locale.ROOT);
        }
        if (parts[0].length() >= 2) {
            return parts[0].substring(0, 2).toUpperCase(Locale.ROOT);
        }
        String a = parts[0].length() > 0 ? parts[0].substring(0, 1) : "T";
        return (a + "X").toUpperCase(Locale.ROOT);
    }

    private byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[]{(byte) 90, (byte) 140, (byte) 200};
        }
    }

    private void renderPeerLabel() {
        updateTitle();
    }

    private void notifyTypingStarted() {
        if (TextUtils.isEmpty(peerId)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (typingReported && now - lastTypingSentAt < TYPING_SEND_INTERVAL_MS) {
            return;
        }
        typingReported = true;
        lastTypingSentAt = now;
        irohNode.sendTypingStart(peerId);
    }

    private void stopTyping() {
        if (!typingReported) {
            return;
        }
        typingReported = false;
        if (!TextUtils.isEmpty(peerId)) {
            irohNode.sendTypingStop(peerId);
        }
    }

    private void onPeerTypingStart(String fromEndpointId) {
        String sender = fromEndpointId == null ? "" : fromEndpointId;
        runOnUiThread(() -> {
            if (TextUtils.isEmpty(peerId) || !sender.equals(peerId)) {
                return;
            }
            if (!peerTyping) {
                headerStatus = getString(R.string.chat_status_online);
            }
            peerTyping = true;
            updateTitle();
            typingHandler.removeCallbacks(clearPeerTyping);
            typingHandler.postDelayed(clearPeerTyping, TYPING_TIMEOUT_MS);
        });
    }

    private void onPeerTypingStop(String fromEndpointId) {
        String sender = fromEndpointId == null ? "" : fromEndpointId;
        runOnUiThread(() -> {
            if (!sender.equals(peerId)) {
                return;
            }
            typingHandler.removeCallbacks(clearPeerTyping);
            if (peerTyping) {
                peerTyping = false;
                updateTitle();
            }
        });
    }

    private void showAddressActions(String address) {
        final String normalized = TransferManager.normalizeAddress(address);
        if (!TransferManager.isValidAddress(normalized)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(shortAddress(normalized))
                .setItems(new String[]{
                        getString(R.string.wallet_copy_address),
                        getString(R.string.chat_send_transfer)
                }, (dialog, which) -> {
                    if (which == 0) {
                        copyAddress(normalized);
                    } else {
                        Intent intent = new Intent(this, TransferActivity.class);
                        intent.putExtra(TransferActivity.EXTRA_RECIPIENT_ADDRESS, normalized);
                        startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void copyAddress(String address) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("wallet_address", address));
        Toast.makeText(this, R.string.toast_address_copied, Toast.LENGTH_SHORT).show();
    }

    private String shortAddress(String address) {
        String value = address == null ? "" : address.trim();
        if (value.length() <= 12) {
            return value;
        }
        return value.substring(0, 6) + "..." + value.substring(value.length() - 4);
    }

    private void maybeShowSwipeHint() {
        if (adapter == null || adapter.getItemCount() == 0) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME,
                MODE_PRIVATE);
        if (prefs.getBoolean(PREF_HAS_SEEN_SWIPE_HINT, false)) {
            return;
        }
        prefs.edit().putBoolean(PREF_HAS_SEEN_SWIPE_HINT, true).apply();
        Toast.makeText(this, R.string.chat_swipe_hint, Toast.LENGTH_LONG).show();
    }

    private void showMessageActions(ChatMessage message) {
        if (message == null || isFinishing()) {
            return;
        }
        String text = message.text == null ? "" : message.text;
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_message_actions_title)
                .setItems(new CharSequence[]{
                        getString(R.string.chat_action_reply),
                        getString(R.string.chat_action_copy_text)
                }, (dialog, which) -> {
                    if (which == 0) {
                        setReplyTo(message);
                    } else {
                        copyMessageText(text);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void copyMessageText(String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("message", text));
        }
        Toast.makeText(this, R.string.chat_text_copied, Toast.LENGTH_SHORT).show();
    }

    private void setReplyTo(ChatMessage message) {
        if (message == null) {
            return;
        }
        replyingTo = message;
        if (replyComposeBar != null) {
            replyComposeBar.setVisibility(View.VISIBLE);
        }
        if (replyComposeText != null) {
            replyComposeText.setText(replySnippet(message.text));
        }
        if (etInput != null) {
            etInput.requestFocus();
            etInput.post(() -> {
                InputMethodManager imm = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
    }

    private void clearReply() {
        replyingTo = null;
        if (replyComposeBar != null) {
            replyComposeBar.setVisibility(View.GONE);
        }
        if (replyComposeText != null) {
            replyComposeText.setText("");
        }
    }

    private String replySnippet(String text) {
        String preview = text == null ? "" : text.trim().replace('\n', ' ');
        if (preview.isEmpty()) {
            return getString(R.string.chat_reply_empty);
        }
        if (preview.length() > 80) {
            return preview.substring(0, 80) + "…";
        }
        return preview;
    }

    private void scrollToQuoteTarget(long replyToId, String replyPreview) {
        if (adapter == null) {
            return;
        }
        int position = adapter.findPositionByDbId(replyToId);
        if (position < 0) {
            position = adapter.findPositionByText(replyPreview);
        }
        if (position >= 0) {
            recyclerMessages.smoothScrollToPosition(position);
        }
    }

    private void showSearchDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_chat_search, null);
        EditText queryInput = content.findViewById(R.id.et_chat_search_query);
        LinearLayout results = content.findViewById(R.id.ll_chat_search_results);
        TextView emptyView = content.findViewById(R.id.tv_chat_search_empty);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.chat_search_hint)
                .setView(content)
                .setNegativeButton(R.string.action_close, null)
                .show();
        queryInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderSearchResults(dialog, s == null ? "" : s.toString(), results, emptyView);
            }
        });
        queryInput.requestFocus();
        queryInput.post(() -> {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(queryInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void renderSearchResults(AlertDialog dialog, String query,
                                     LinearLayout container, TextView emptyView) {
        if (container == null || isFinishing() || isDestroyed()) {
            return;
        }
        container.removeAllViews();
        if (chatDatabase == null || TextUtils.isEmpty(query)
                || TextUtils.isEmpty(query.trim())) {
            if (emptyView != null) {
                emptyView.setVisibility(View.GONE);
            }
            return;
        }
        List<ChatDatabase.Message> found = new ArrayList<>();
        for (ChatDatabase.Message message : chatDatabase.searchMessages(peerId, query.trim())) {
            if (message != null && !message.isAudio() && isTechnicalChatText(message.text)) {
                continue;
            }
            found.add(message);
        }
        if (found.isEmpty()) {
            if (emptyView != null) {
                emptyView.setVisibility(View.VISIBLE);
            }
            return;
        }
        if (emptyView != null) {
            emptyView.setVisibility(View.GONE);
        }
        SimpleDateFormat metaFormat = new SimpleDateFormat("d MMM · HH:mm", Locale.getDefault());
        LayoutInflater inflater = LayoutInflater.from(this);
        int limit = Math.min(found.size(), SEARCH_RESULT_LIMIT);
        for (int i = 0; i < limit; i++) {
            ChatDatabase.Message message = found.get(i);
            View row = inflater.inflate(R.layout.item_chat_search_result, container, false);
            TextView text = row.findViewById(R.id.tv_search_result_text);
            TextView meta = row.findViewById(R.id.tv_search_result_meta);
            text.setText(message.text);
            String sender = message.isOutgoing()
                    ? getString(R.string.chat_self) : getString(R.string.chat_peer);
            meta.setText(sender + " · " + metaFormat.format(new Date(message.timestamp)));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (adapter == null) {
                    return;
                }
                int position = adapter.findPositionByDbId(message.id);
                if (position >= 0) {
                    recyclerMessages.smoothScrollToPosition(position);
                }
            });
            container.addView(row);
        }
    }

    private String draftKey() {
        return KEY_DRAFT_PREFIX + (peerId == null ? "" : peerId);
    }

    private void restoreDraft() {
        if (etInput == null) {
            return;
        }
        String draft = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .getString(draftKey(), "");
        if (!TextUtils.isEmpty(draft)) {
            etInput.setText(draft);
            etInput.setSelection(draft.length());
        }
    }

    private void saveDraft(String text) {
        getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(draftKey(), text == null ? "" : text)
                .apply();
    }

    private void clearDraft() {
        getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(draftKey())
                .apply();
    }

    private List<ChatMessage> loadHistory(String chatPeerId) {
        List<ChatMessage> history = new ArrayList<>();
        if (chatDatabase == null) {
            return history;
        }
        for (ChatDatabase.Message message : chatDatabase.getMessages(chatPeerId)) {
            if (!message.isAudio() && isTechnicalChatText(message.text)) {
                continue;
            }
            history.add(new ChatMessage(message.id, message.peerId,
                    message.isAudio() ? "" : message.text,
                    message.isOutgoing(), message.timestamp, message.status,
                    message.replyToId, message.replyPreview,
                    message.messageType, message.audioPath));
        }
        return history;
    }

    private void showChatMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.chat_export);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                exportChat();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void exportChat() {
        if (chatDatabase == null) {
            Toast.makeText(this, R.string.chat_export_error, Toast.LENGTH_SHORT).show();
            return;
        }
        String peerName = TextUtils.isEmpty(peerId)
                ? getString(R.string.chat_peer) : irohNode.getPeerDisplayName(peerId);
        if (TextUtils.isEmpty(peerName)) {
            peerName = getString(R.string.chat_peer);
        }
        SimpleDateFormat lineFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        StringBuilder content = new StringBuilder();
        content.append(getString(R.string.chat_export_header_format, peerName,
                lineFormat.format(new Date())));
        List<ChatDatabase.Message> messages = chatDatabase.getMessages(peerId);
        for (ChatDatabase.Message message : messages) {
            if (!message.isAudio() && isTechnicalChatText(message.text)) {
                continue;
            }
            String text = message.isAudio()
                    ? getString(R.string.chat_voice_message)
                    : (message.text == null ? "" : message.text);
            String sender = message.isOutgoing() ? getString(R.string.chat_self) : peerName;
            content.append('[').append(lineFormat.format(new Date(message.timestamp))).append("] ")
                    .append(sender).append(": ").append(text).append('\n');
        }

        File directory = new File(getCacheDir(), "exports");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, R.string.chat_export_error, Toast.LENGTH_SHORT).show();
            return;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File file = new File(directory, "chat_" + safeFileName(peerName) + "_" + timestamp + ".txt");
        try {
            Files.write(file.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT,
                    getString(R.string.chat_export_subject_format, peerName));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setClipData(ClipData.newRawUri("chat_export", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent,
                    getString(R.string.chat_export_share)));
        } catch (Throwable t) {
            Log.w(TAG, "chat export failed", t);
            Toast.makeText(this, R.string.chat_export_error, Toast.LENGTH_SHORT).show();
        }
    }

    private static String safeFileName(String value) {
        if (value == null) {
            return "chat";
        }
        String safe = value.replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        if (safe.length() > 32) {
            safe = safe.substring(0, 32);
        }
        return safe.isEmpty() ? "chat" : safe;
    }

    private void sendCurrent() {
        String text = etInput.getText() == null ? "" : etInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        if (TextUtils.isEmpty(peerId)) {
            Toast.makeText(this, R.string.toast_no_peer_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        String target = peerId;
        ChatMessage replyTarget = replyingTo;
        long replyToId = replyTarget == null ? 0 : replyTarget.dbId;
        String replyPreview = replyTarget == null ? "" : replyTarget.text;
        stopTyping();
        Log.i(TAG, "User sent: text=" + text + ", time=" + System.currentTimeMillis());
        long messageId = chatDatabase.saveMessage(target, ChatDatabase.SENDER_SELF, text,
                ChatDatabase.STATUS_SENDING, replyToId, replyPreview);
        ChatMessage message = new ChatMessage(messageId, target, text, true,
                System.currentTimeMillis(), ChatMessage.STATUS_SENDING,
                replyToId, replyPreview, ChatDatabase.TYPE_TEXT, "");
        adapter.add(message);
        etInput.setText("");
        clearDraft();
        clearReply();
        scrollToBottom();
        irohNode.sendMessage(target, text, replyToId, replyPreview, new IrohNode.SendCallback() {
            @Override
            public void onSent(String endpointId) {
                runOnUiThread(() -> {
                    chatDatabase.updateMessageStatus(messageId, ChatDatabase.STATUS_DELIVERED);
                    adapter.updateStatus(message, ChatMessage.STATUS_DELIVERED);
                });
            }

            @Override
            public void onError(String endpointId, String error) {
                runOnUiThread(() -> {
                    chatDatabase.updateMessageStatus(messageId, ChatDatabase.STATUS_FAILED);
                    adapter.updateStatus(message, ChatMessage.STATUS_FAILED);
                });
            }
        });
    }

    // ---------- Голосовые сообщения ----------

    private void toggleVoiceRecording() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (voiceRecorder != null) {
            stopVoiceRecordingAndSend();
            return;
        }
        if (TextUtils.isEmpty(peerId)) {
            Toast.makeText(this, R.string.toast_no_peer_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_MIC_PERMISSION);
            return;
        }
        startVoiceRecording();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MIC_PERMISSION) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording();
        } else {
            Toast.makeText(this, R.string.chat_mic_permission_denied, Toast.LENGTH_LONG)
                    .show();
        }
    }

    private void startVoiceRecording() {
        File dir = new File(getExternalFilesDir(null), "voice");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "failed to create voice directory");
            return;
        }
        File file = new File(dir, "voice_" + System.currentTimeMillis() + ".aac");
        MediaRecorder recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(24000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.setMaxDuration((int) MAX_VOICE_DURATION_MS);
            recorder.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    runOnUiThread(this::stopVoiceRecordingAndSend);
                }
            });
            recorder.prepare();
            recorder.start();
            voiceRecorder = recorder;
            voiceRecordingPath = file.getAbsolutePath();
            voiceRecordingStartedAt = SystemClock.elapsedRealtime();
            updateMicButton(true);
        } catch (Throwable t) {
            Log.w(TAG, "failed to start voice recording", t);
            releaseRecorder(recorder);
            deleteQuietly(file);
            Toast.makeText(this, R.string.chat_mic_permission_denied,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void stopVoiceRecordingAndSend() {
        MediaRecorder recorder = voiceRecorder;
        voiceRecorder = null;
        updateMicButton(false);
        String path = voiceRecordingPath;
        voiceRecordingPath = null;
        if (recorder == null) {
            return;
        }
        long duration = SystemClock.elapsedRealtime() - voiceRecordingStartedAt;
        try {
            recorder.stop();
        } catch (Throwable t) {
            Log.w(TAG, "voice recorder stop failed", t);
        }
        releaseRecorder(recorder);
        if (duration < MIN_VOICE_DURATION_MS) {
            deleteQuietly(path == null ? null : new File(path));
            Toast.makeText(this, R.string.chat_voice_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        sendVoiceMessage(path);
    }

    private void sendVoiceMessage(String path) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        if (TextUtils.isEmpty(peerId)) {
            Toast.makeText(this, R.string.toast_no_peer_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] audio;
        try {
            audio = Files.readAllBytes(new File(path).toPath());
        } catch (Throwable t) {
            Log.w(TAG, "failed to read voice recording", t);
            Toast.makeText(this, R.string.chat_voice_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        if (audio.length == 0) {
            Toast.makeText(this, R.string.chat_voice_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        final String target = peerId;
        final String base64 = Base64.encodeToString(audio, Base64.NO_WRAP);
        ChatMessage replyTarget = replyingTo;
        long replyToId = replyTarget == null ? 0 : replyTarget.dbId;
        String replyPreview = replyTarget == null ? "" : replyTarget.text;
        stopTyping();
        long messageId = chatDatabase.saveAudioMessage(target, ChatDatabase.SENDER_SELF,
                path, ChatDatabase.STATUS_SENDING, replyToId, replyPreview);
        ChatMessage message = new ChatMessage(messageId, target, "", true,
                System.currentTimeMillis(), ChatMessage.STATUS_SENDING,
                replyToId, replyPreview, ChatDatabase.TYPE_AUDIO, path);
        adapter.add(message);
        clearReply();
        scrollToBottom();
        irohNode.sendAudioMessage(target, base64, replyToId, replyPreview,
                new IrohNode.SendCallback() {
                    @Override
                    public void onSent(String endpointId) {
                        runOnUiThread(() -> {
                            chatDatabase.updateMessageStatus(messageId,
                                    ChatDatabase.STATUS_DELIVERED);
                            adapter.updateStatus(message, ChatMessage.STATUS_DELIVERED);
                        });
                    }

                    @Override
                    public void onError(String endpointId, String error) {
                        runOnUiThread(() -> {
                            chatDatabase.updateMessageStatus(messageId,
                                    ChatDatabase.STATUS_FAILED);
                            adapter.updateStatus(message, ChatMessage.STATUS_FAILED);
                        });
                    }
                });
    }

    private void releaseRecorder(MediaRecorder recorder) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.release();
        } catch (Throwable ignored) {
        }
    }

    private void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Throwable ignored) {
        }
    }

    private void updateMicButton(boolean recording) {
        if (btnMic == null) {
            return;
        }
        btnMic.setImageResource(recording ? R.drawable.ic_voice_stop : R.drawable.ic_mic);
        btnMic.setImageTintList(recording
                ? ColorStateList.valueOf(Color.parseColor("#FF5252"))
                : null);
    }

    // ---------- Воспроизведение голосовых ----------

    private void onAudioPlayClick(ChatMessage message) {
        if (message == null || isFinishing()) {
            return;
        }
        if (audioPlayer != null) {
            ChatMessage current = playingMessage;
            stopAudioPlayback();
            if (current == message) {
                return;
            }
        }
        if (TextUtils.isEmpty(message.audioPath)
                || !new File(message.audioPath).exists()) {
            return;
        }
        try {
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(message.audioPath);
            player.setOnCompletionListener(mp -> runOnUiThread(this::stopAudioPlayback));
            player.setOnPreparedListener(mp -> {
                try {
                    mp.start();
                } catch (Throwable t) {
                    Log.w(TAG, "voice playback failed", t);
                    stopAudioPlayback();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                stopAudioPlayback();
                return true;
            });
            player.prepareAsync();
            audioPlayer = player;
            playingMessage = message;
            if (adapter != null) {
                adapter.setPlaying(message.dbId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "voice playback failed", t);
            stopAudioPlayback();
        }
    }

    private void stopAudioPlayback() {
        ChatMessage message = playingMessage;
        playingMessage = null;
        MediaPlayer player = audioPlayer;
        audioPlayer = null;
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable ignored) {
            }
        }
        if (message != null && adapter != null) {
            adapter.setPlaying(-1L);
        }
    }

    // ---------- Read receipts ----------

    private void onPeerReadReceipt(String from, long upTo) {
        String sender = from == null ? "" : from;
        runOnUiThread(() -> {
            if (!active || !sender.equals(peerId)) {
                return;
            }
            reloadMessages();
        });
    }

    private void onIncomingMessage(String from, String text) {
        if (isTechnicalChatText(text)) {
            return;
        }
        String sender = from == null ? "" : from;
        runOnUiThread(() -> {
            if (!active) {
                return;
            }
            if (TextUtils.isEmpty(peerId) && !TextUtils.isEmpty(sender)) {
                peerId = sender;
                activePeerId = peerId;
                renderPeerLabel();
                setHeaderStatus(getString(R.string.chat_status_online));
                updateAvatar();
            }
            if (!TextUtils.isEmpty(sender) && sender.equals(peerId)) {
                typingHandler.removeCallbacks(clearPeerTyping);
                peerTyping = false;
                setHeaderStatus(getString(R.string.chat_status_online));
                markChatRead();
                reloadMessages();
                scrollToBottom();
            } else if (!TextUtils.isEmpty(sender)) {
                Toast.makeText(this, getString(R.string.toast_message_from_format,
                        irohNode.getPeerDisplayName(sender)), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void reloadMessages() {
        if (adapter == null) {
            return;
        }
        adapter.setMessages(loadHistory(peerId));
        maybeShowSwipeHint();
    }

    private void markChatRead() {
        if (chatDatabase != null) {
            long unreadAt = chatDatabase.getLastUnreadIncomingTimestamp(peerId);
            chatDatabase.markMessagesRead(peerId);
            // Сообщаем собеседнику, что его сообщения прочитаны.
            if (unreadAt > 0 && unreadAt > lastReadReceiptAt && !TextUtils.isEmpty(peerId)) {
                lastReadReceiptAt = unreadAt;
                irohNode.sendReadReceipt(peerId, unreadAt);
            }
        }
        try {
            UnreadCounter.getInstance(this).clear(peerId);
        } catch (Throwable ignored) {
        }
        NotificationHelper.cancelMessageNotification(this, peerId);
    }

    private void scrollToBottom() {
        if (adapter != null && adapter.getItemCount() > 0) {
            recyclerMessages.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activePeerId = peerId == null ? "" : peerId;
        active = true;
        markChatRead();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadMessages();
        scrollToBottom();
        updateTitle();
    }

    @Override
    protected void onStop() {
        stopTyping();
        stopTypingAnimation();
        String currentPeer = peerId == null ? "" : peerId;
        if (currentPeer.equals(activePeerId)) {
            active = false;
            activePeerId = "";
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopTyping();
        stopTypingAnimation();
        if (voiceRecorder != null) {
            MediaRecorder recorder = voiceRecorder;
            voiceRecorder = null;
            try {
                recorder.stop();
            } catch (Throwable ignored) {
            }
            releaseRecorder(recorder);
            deleteQuietly(voiceRecordingPath == null ? null : new File(voiceRecordingPath));
            voiceRecordingPath = null;
        }
        stopAudioPlayback();
        profileHandler.removeCallbacksAndMessages(null);
        typingHandler.removeCallbacksAndMessages(null);
        irohNode.setOnMessageListener(null);
        irohNode.setOnTypingListener(null);
        irohNode.setOnReadReceiptListener(null);
        super.onDestroy();
    }

    // ---------- Message model + adapter ----------

    static final class ChatMessage {
        static final int STATUS_SENDING = ChatDatabase.STATUS_SENDING;
        static final int STATUS_DELIVERED = ChatDatabase.STATUS_DELIVERED;
        static final int STATUS_FAILED = ChatDatabase.STATUS_FAILED;
        static final int STATUS_READ = ChatDatabase.STATUS_READ;

        final long dbId;
        final String peerId;
        final String text;
        final boolean outgoing;
        final long timestamp;
        final long replyToId;
        final String replyPreview;
        final String messageType;
        final String audioPath;
        int sendStatus;

        ChatMessage(long dbId, String peerId, String text, boolean outgoing, long timestamp,
                    int sendStatus, long replyToId, String replyPreview,
                    String messageType, String audioPath) {
            this.dbId = dbId;
            this.peerId = peerId;
            this.text = text;
            this.outgoing = outgoing;
            this.timestamp = timestamp;
            this.sendStatus = sendStatus;
            this.replyToId = replyToId;
            this.replyPreview = replyPreview == null ? "" : replyPreview;
            this.messageType = ChatDatabase.TYPE_AUDIO.equals(messageType)
                    ? ChatDatabase.TYPE_AUDIO : ChatDatabase.TYPE_TEXT;
            this.audioPath = audioPath == null ? "" : audioPath;
        }

        boolean isAudio() {
            return ChatDatabase.TYPE_AUDIO.equals(messageType);
        }
    }

    private static final class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

        private final SimpleDateFormat timeFormat =
                new SimpleDateFormat("HH:mm", Locale.getDefault());

        private final List<ChatMessage> messages = new ArrayList<>();
        private final String peerId;
        private final Bitmap ownAvatar;
        private final AddressClickListener addressClickListener;
        private final QuoteClickListener quoteClickListener;
        private final MessageLongClickListener messageLongClickListener;
        private final AudioPlayClickListener audioPlayClickListener;
        private long playingDbId = -1L;

        MessageAdapter(String peerId, Bitmap ownAvatar, List<ChatMessage> initialMessages,
                       AddressClickListener addressClickListener,
                       QuoteClickListener quoteClickListener,
                       MessageLongClickListener messageLongClickListener,
                       AudioPlayClickListener audioPlayClickListener) {
            this.peerId = peerId;
            this.ownAvatar = ownAvatar;
            this.addressClickListener = addressClickListener;
            this.quoteClickListener = quoteClickListener;
            this.messageLongClickListener = messageLongClickListener;
            this.audioPlayClickListener = audioPlayClickListener;
            if (initialMessages != null) {
                messages.addAll(initialMessages);
            }
        }

        ChatMessage getItem(int position) {
            if (position < 0 || position >= messages.size()) {
                return null;
            }
            return messages.get(position);
        }

        int findPositionByDbId(long dbId) {
            if (dbId <= 0) {
                return -1;
            }
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).dbId == dbId) {
                    return i;
                }
            }
            return -1;
        }

        int findPositionByText(String text) {
            if (TextUtils.isEmpty(text)) {
                return -1;
            }
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (text.equals(messages.get(i).text)) {
                    return i;
                }
            }
            return -1;
        }

        void add(ChatMessage msg) {
            messages.add(msg);
            notifyItemInserted(messages.size() - 1);
        }

        void updateStatus(ChatMessage msg, int status) {
            msg.sendStatus = status;
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage current = messages.get(i);
                if (current == msg || (msg.dbId >= 0 && current.dbId == msg.dbId)) {
                    current.sendStatus = status;
                    notifyItemChanged(i);
                    return;
                }
            }
        }

        void setMessages(List<ChatMessage> newMessages) {
            messages.clear();
            if (newMessages != null) {
                messages.addAll(newMessages);
            }
            notifyDataSetChanged();
        }

        void setPlaying(long dbId) {
            int oldPosition = findPositionByDbId(playingDbId);
            playingDbId = dbId;
            if (oldPosition >= 0) {
                notifyItemChanged(oldPosition);
            }
            int newPosition = findPositionByDbId(dbId);
            if (newPosition >= 0) {
                notifyItemChanged(newPosition);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_message, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ChatMessage msg = messages.get(position);
            bindReply(holder, msg);
            bindContent(holder, msg);
            String meta = (msg.outgoing
                    ? holder.itemView.getContext().getString(R.string.chat_self)
                    : holder.itemView.getContext().getString(R.string.chat_peer)) + " · "
                    + timeFormat.format(new Date(msg.timestamp));
            holder.meta.setText(meta);
            holder.bubble.setBackgroundResource(msg.outgoing
                    ? R.drawable.bg_chat_bubble_out
                    : R.drawable.bg_chat_bubble_in);
            LinearLayout row = holder.row;
            row.setGravity(msg.outgoing ? Gravity.END : Gravity.START);
            bindStatus(holder, msg);
            if (msg.outgoing && ownAvatar != null) {
                holder.avatar.setVisibility(View.VISIBLE);
                holder.avatar.setImageBitmap(ownAvatar);
            } else {
                holder.avatar.setVisibility(View.GONE);
            }
            holder.itemView.setOnLongClickListener(v -> {
                if (messageLongClickListener != null) {
                    messageLongClickListener.onMessageLongClick(msg);
                }
                return true;
            });
        }

        private void bindContent(VH holder, ChatMessage msg) {
            if (msg.isAudio()) {
                holder.text.setVisibility(View.GONE);
                holder.audioContainer.setVisibility(View.VISIBLE);
                holder.audioPlay.setImageResource(
                        msg.dbId == playingDbId ? R.drawable.ic_voice_stop
                                : R.drawable.ic_audio_play);
                holder.audioPlay.setOnClickListener(v -> {
                    if (audioPlayClickListener != null) {
                        audioPlayClickListener.onAudioPlayClick(msg);
                    }
                });
                return;
            }
            holder.text.setVisibility(View.VISIBLE);
            holder.audioContainer.setVisibility(View.GONE);
            holder.audioPlay.setOnClickListener(null);
            holder.text.setText(linkifyAddresses(holder.itemView.getContext(), msg.text,
                    msg.outgoing));
            holder.text.setMovementMethod(LinkMovementMethod.getInstance());
        }

        private void bindReply(VH holder, ChatMessage msg) {
            boolean hasReply = msg.replyToId > 0 || !TextUtils.isEmpty(msg.replyPreview);
            holder.replyContainer.setVisibility(hasReply ? View.VISIBLE : View.GONE);
            if (!hasReply) {
                holder.replyText.setText("");
                holder.replyContainer.setOnClickListener(null);
                return;
            }
            ChatMessage target = findReplyTarget(msg);
            String preview = target != null && !TextUtils.isEmpty(target.text)
                    ? target.text
                    : msg.replyPreview;
            if (TextUtils.isEmpty(preview)) {
                preview = holder.itemView.getContext().getString(R.string.chat_reply_empty);
            }
            holder.replyText.setText(preview.trim().replace('\n', ' '));
            final long targetId = target == null ? 0 : target.dbId;
            final String targetPreview = preview;
            holder.replyContainer.setOnClickListener(v -> {
                if (quoteClickListener != null) {
                    quoteClickListener.onQuoteClick(targetId, targetPreview);
                }
            });
        }

        private ChatMessage findReplyTarget(ChatMessage msg) {
            ChatMessage byId = findById(msg.replyToId);
            if (byId != null && (TextUtils.isEmpty(msg.replyPreview)
                    || msg.replyPreview.equals(byId.text))) {
                return byId;
            }
            return findByText(msg.replyPreview);
        }

        private ChatMessage findById(long dbId) {
            int position = findPositionByDbId(dbId);
            return position < 0 ? null : messages.get(position);
        }

        private ChatMessage findByText(String text) {
            int position = findPositionByText(text);
            return position < 0 ? null : messages.get(position);
        }

        private CharSequence linkifyAddresses(Context context, String text, boolean outgoing) {
            if (TextUtils.isEmpty(text)) {
                return "";
            }
            SpannableString spannable = new SpannableString(text);
            Matcher matcher = WALLET_ADDRESS_PATTERN.matcher(text);
            final int linkColor = outgoing
                    ? Color.WHITE
                    : ContextCompat.getColor(context, R.color.accent_purple);
            while (matcher.find()) {
                final String address = matcher.group();
                int start = matcher.start();
                int end = matcher.end();
                spannable.setSpan(new ForegroundColorSpan(linkColor), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new UnderlineSpan(), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        if (addressClickListener != null) {
                            addressClickListener.onAddressClick(address);
                        }
                    }

                    @Override
                    public void updateDrawState(TextPaint ds) {
                        ds.setColor(linkColor);
                        ds.setUnderlineText(true);
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return spannable;
        }

        private void bindStatus(VH holder, ChatMessage msg) {
            if (!msg.outgoing) {
                holder.status.setVisibility(View.GONE);
                return;
            }
            holder.status.setVisibility(View.VISIBLE);
            int color;
            int icon;
            int description;
            if (msg.sendStatus == ChatMessage.STATUS_FAILED) {
                icon = R.drawable.ic_error;
                color = Color.parseColor("#FCA5A5");
                description = R.string.chat_send_failed;
            } else if (msg.sendStatus == ChatMessage.STATUS_READ) {
                icon = R.drawable.ic_done_all;
                color = ContextCompat.getColor(
                        holder.itemView.getContext(), R.color.accent_cyan);
                description = R.string.chat_status_read;
            } else if (msg.sendStatus == ChatMessage.STATUS_DELIVERED) {
                icon = R.drawable.ic_check;
                color = Color.WHITE;
                description = R.string.chat_delivered;
            } else {
                icon = R.drawable.ic_timer;
                color = Color.parseColor("#B3FFFFFF");
                description = R.string.chat_sending;
            }
            holder.status.setImageResource(icon);
            holder.status.setImageTintList(ColorStateList.valueOf(color));
            holder.status.setContentDescription(
                    holder.itemView.getContext().getString(description));
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final LinearLayout row;
            final LinearLayout replyContainer;
            final LinearLayout bubble;
            final TextView replyText;
            final TextView text;
            final LinearLayout audioContainer;
            final ImageView audioPlay;
            final TextView audioLabel;
            final TextView meta;
            final ImageView avatar;
            final ImageView status;

            VH(@NonNull View itemView) {
                super(itemView);
                row = (LinearLayout) itemView;
                replyContainer = itemView.findViewById(R.id.ll_msg_reply);
                bubble = itemView.findViewById(R.id.ll_msg_bubble);
                replyText = itemView.findViewById(R.id.tv_msg_reply_text);
                text = itemView.findViewById(R.id.tv_msg_text);
                audioContainer = itemView.findViewById(R.id.ll_msg_audio);
                audioPlay = itemView.findViewById(R.id.btn_msg_audio_play);
                audioLabel = itemView.findViewById(R.id.tv_msg_audio_label);
                meta = itemView.findViewById(R.id.tv_msg_meta);
                avatar = itemView.findViewById(R.id.iv_msg_avatar);
                status = itemView.findViewById(R.id.iv_msg_status);
            }
        }
    }
}
