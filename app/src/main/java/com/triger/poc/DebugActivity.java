package com.triger.poc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DebugActivity extends AppCompatActivity {

    private static final int MAX_LOG_LINES = 200;
    private static final int LOGCAT_LINES = 4000;
    private static final Pattern LOGCAT_TAG =
            Pattern.compile("(?:\\s[VDIWEF]/|\\s[VDIWEF]\\s+)([^\\s:]+)\\s*[:(]");

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            refreshHandler.postDelayed(this, 2000L);
        }
    };

    private TextView tvEndpoint;
    private TextView tvRelay;
    private TextView tvPeers;
    private TextView tvRealPeers;
    private TextView tvPoaTurn;
    private TextView tvPoaIndex;
    private TextView tvStrongBox;
    private TextView tvLogPath;
    private Button btnCopyLogs;
    private Button btnExportChatDb;
    private Button btnCheckChain;
    private Button btnResetBlockHeight;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageActivity.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvEndpoint = findViewById(R.id.tv_debug_endpoint);
        tvRelay = findViewById(R.id.tv_debug_relay);
        tvPeers = findViewById(R.id.tv_debug_peers);
        tvRealPeers = findViewById(R.id.tv_debug_real_peers);
        tvPoaTurn = findViewById(R.id.tv_debug_poa_turn);
        tvPoaIndex = findViewById(R.id.tv_debug_poa_index);
        tvStrongBox = findViewById(R.id.tv_debug_strongbox);
        tvLogPath = findViewById(R.id.tv_debug_log_path);
        btnCopyLogs = findViewById(R.id.btn_copy_logs);
        btnExportChatDb = findViewById(R.id.btn_export_chat_db);
        btnCheckChain = findViewById(R.id.btn_check_chain);
        btnResetBlockHeight = findViewById(R.id.btn_reset_block_height);

        btnCopyLogs.setOnClickListener(v -> copyLogs());
        btnExportChatDb.setOnClickListener(v -> exportChatDatabase());
        btnCheckChain.setOnClickListener(v -> checkChain());
        btnResetBlockHeight.setOnClickListener(v -> confirmResetBlockHeight());

        NetworkFileLogger.init(getApplicationContext());
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.postDelayed(refreshRunnable, 2000L);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void confirmResetBlockHeight() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.debug_reset_confirm_title)
                .setMessage(R.string.debug_reset_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.debug_reset_continue,
                        (dialog, which) -> confirmResetBlockHeightFinal())
                .show();
    }

    private void confirmResetBlockHeightFinal() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.debug_reset_final_title)
                .setMessage(R.string.debug_reset_final_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_reset, (dialog, which) -> {
                    LocalBroadcastManager.getInstance(this)
                            .sendBroadcast(new Intent(MainActivity.ACTION_RESET_COUNTER));
                    Toast.makeText(this, R.string.settings_counter_reset_toast,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void checkChain() {
        btnCheckChain.setEnabled(false);
        ioExecutor.execute(() -> {
            ChainCheckResult result;
            try {
                result = verifyChain();
            } catch (Throwable t) {
                result = ChainCheckResult.error(getString(
                        R.string.debug_chain_error_parse, t.getMessage()));
            }
            final ChainCheckResult checked = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                btnCheckChain.setEnabled(true);
                showChainCheckResult(checked);
            });
        });
    }

    private ChainCheckResult verifyChain() {
        BlockStorage storage = new BlockStorage(this);
        List<String> blocks = storage.getAllBlocks();
        boolean spv = storage.isSpvMode();
        String expectedPrev = "0x0";
        for (int i = 0; i < blocks.size(); i++) {
            JSONObject block;
            try {
                block = new JSONObject(blocks.get(i));
            } catch (Throwable t) {
                return ChainCheckResult.error(getString(
                        R.string.debug_chain_error_parse, String.valueOf(i)));
            }
            int index = block.optInt("index", -1);
            if (index != i) {
                return ChainCheckResult.error(getString(
                        R.string.debug_chain_error_index, index, i));
            }
            String prevHash = block.optString("prevHash", "");
            if (!prevHash.equals(expectedPrev)) {
                return ChainCheckResult.error(getString(
                        R.string.debug_chain_error_link, i));
            }
            if (spv) {
                if (!BlockStorage.verifyHeader(block)) {
                    return ChainCheckResult.error(getString(
                            R.string.debug_chain_error_header, i));
                }
            } else {
                if (!BlockStorage.verifyBlockHash(block)) {
                    return ChainCheckResult.error(getString(
                            R.string.debug_chain_error_hash, i));
                }
                if (!BlockStorage.verifyBlockSignature(block)) {
                    return ChainCheckResult.error(getString(
                            R.string.debug_chain_error_signature, i));
                }
            }
            expectedPrev = block.optString("hash", "");
        }
        return ChainCheckResult.valid(blocks.size());
    }

    private void showChainCheckResult(ChainCheckResult result) {
        if (result.error == null) {
            Toast.makeText(this,
                    getString(R.string.debug_chain_valid, result.blockCount),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.debug_chain_invalid_title)
                .setMessage(getString(R.string.debug_chain_invalid_message, result.error))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.debug_chain_resync, (dialog, which) -> resyncChain())
                .show();
    }

    private void resyncChain() {
        try {
            IrohNode node = IrohNode.getInstance();
            node.requestAllBlocksFromPeers();
            node.requestChainFromAllPeers();
            Toast.makeText(this, R.string.debug_chain_resync_started,
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, R.string.debug_chain_resync_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static final class ChainCheckResult {
        final int blockCount;
        final String error;

        private ChainCheckResult(int blockCount, String error) {
            this.blockCount = blockCount;
            this.error = error;
        }

        static ChainCheckResult valid(int blockCount) {
            return new ChainCheckResult(blockCount, null);
        }

        static ChainCheckResult error(String error) {
            return new ChainCheckResult(0, error);
        }
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refreshStatus() {
        IrohNode node = IrohNode.getInstance();
        String endpointId = node.getEndpointId();
        if (endpointId == null || endpointId.isEmpty()) {
            endpointId = node.getSelfEndpointId();
        }
        if (endpointId == null || endpointId.isEmpty()) {
            endpointId = getString(R.string.placeholder_dash);
        }
        tvEndpoint.setText(endpointId);

        boolean relayConnected = node.isRelayConnected();
        String relayUrl = node.getRelayUrl();
        String relay = getString(relayConnected
                ? R.string.debug_relay_connected
                : R.string.debug_relay_disconnected);
        if (relayUrl != null && !relayUrl.isEmpty()) {
            relay += "\n" + relayUrl;
        }
        tvRelay.setText(relay);

        List<String> irohConnected = node.getConnectedPeers();
        int p2pPeers = P2PNode.getInstance().getConnectedPeers();
        tvPeers.setText(getString(R.string.debug_peers_format,
                irohConnected.size(), p2pPeers));
        refreshRealPeers(node, irohConnected);

        BlockStorage storage = new BlockStorage(this);
        int nextBlockIndex = storage.getLastBlockIndex() + 1;
        int validatorCount = ValidatorManager.getValidatorCount(this);
        int myIndex = ValidatorManager.getLocalValidatorIndex(this);
        boolean myTurn = myIndex >= 0 && validatorCount > 0
                && (nextBlockIndex % validatorCount == myIndex);
        tvPoaTurn.setText(getString(R.string.debug_poa_turn,
                getString(myTurn ? R.string.debug_poa_yes : R.string.debug_poa_no)));
        tvPoaIndex.setText(getString(R.string.debug_poa_index, myIndex, validatorCount));

        tvStrongBox.setText(DeviceIdentity.isStrongBoxBacked(this)
                ? R.string.device_strongbox_available
                : R.string.device_strongbox_unavailable);

        tvLogPath.setText(NetworkFileLogger.getLogFile(this).getAbsolutePath());
    }

    private void refreshRealPeers(IrohNode node, List<String> irohConnected) {
        List<P2PNode.PeerInfo> peers = P2PNode.getInstance().getRealDiscoveredPeers();
        if (peers.isEmpty()) {
            tvRealPeers.setText(R.string.debug_real_peers_none);
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (P2PNode.PeerInfo peer : peers) {
            String deviceId = peer.deviceId;
            if (deviceId.isEmpty() && !peer.endpointId.isEmpty()) {
                deviceId = node.getPeerDeviceId(peer.endpointId);
            }
            String deviceLabel = deviceId.isEmpty() ? "unknown" : deviceId;
            String status;
            if (peer.connected) {
                status = getString(R.string.debug_peer_status_connected);
            } else if (!peer.endpointId.isEmpty()
                    && irohConnected.contains(peer.endpointId)) {
                status = getString(R.string.debug_peer_status_connected_iroh);
            } else {
                status = getString(R.string.debug_peer_status_found);
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(getString(R.string.debug_real_peer_format,
                    peer.address, deviceLabel, status));
        }
        tvRealPeers.setText(builder.toString());
    }

    private void copyLogs() {
        btnCopyLogs.setEnabled(false);
        ioExecutor.execute(() -> {
            String logs = collectLogs();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                btnCopyLogs.setEnabled(true);
                if (logs.isEmpty()) {
                    Toast.makeText(this, R.string.debug_logs_empty,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard == null) {
                    Toast.makeText(this, R.string.debug_logs_empty,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                clipboard.setPrimaryClip(ClipData.newPlainText("Trigger logs", logs));
                Toast.makeText(this, R.string.debug_logs_copied, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String collectLogs() {
        ArrayDeque<String> lines = new ArrayDeque<>();
        Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-d", "-v", "threadtime",
                    "-t", String.valueOf(LOGCAT_LINES))
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!isNetworkLogLine(line)) {
                        continue;
                    }
                    lines.addLast(line);
                    while (lines.size() > MAX_LOG_LINES) {
                        lines.removeFirst();
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        if (lines.isEmpty()) {
            return readNetworkLogTail();
        }
        return joinLines(lines);
    }

    private boolean isNetworkLogLine(String line) {
        Matcher matcher = LOGCAT_TAG.matcher(line);
        if (!matcher.find()) {
            return false;
        }
        String tag = matcher.group(1);
        return tag.contains("Iroh")
                || tag.contains("P2P")
                || tag.contains("Chat")
                || tag.contains("Trigger");
    }

    private String readNetworkLogTail() {
        File file = NetworkFileLogger.getLogFile(this);
        if (!file.exists()) {
            return "";
        }
        ArrayDeque<String> lines = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                while (lines.size() > MAX_LOG_LINES) {
                    lines.removeFirst();
                }
            }
        } catch (Throwable ignored) {
        }
        return joinLines(lines);
    }

    private String joinLines(ArrayDeque<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private void exportChatDatabase() {
        btnExportChatDb.setEnabled(false);
        ioExecutor.execute(() -> {
            File exported = null;
            try {
                exported = createChatDatabaseExport();
            } catch (Throwable ignored) {
            }
            final File result = exported;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                btnExportChatDb.setEnabled(true);
                if (result == null) {
                    Toast.makeText(this, R.string.debug_db_export_error,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                shareChatDatabase(result);
            });
        });
    }

    private File createChatDatabaseExport() throws Exception {
        ChatDatabase helper = ChatDatabase.getInstance(this);
        SQLiteDatabase database = helper.getWritableDatabase();
        try (Cursor cursor = database.rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
            cursor.moveToFirst();
        } catch (Throwable ignored) {
        }

        File source = new File(database.getPath());
        File directory = getExternalFilesDir("exports");
        if (directory == null) {
            directory = new File(getCacheDir(), "exports");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("export directory unavailable");
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File destination = new File(directory, "chat_history_" + timestamp + ".db");

        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        return destination;
    }

    private void shareChatDatabase(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setClipData(ClipData.newRawUri("chat_db", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent,
                    getString(R.string.debug_share_chat_db)));
        } catch (Throwable t) {
            Toast.makeText(this, R.string.debug_db_export_error,
                    Toast.LENGTH_SHORT).show();
        }
    }
}
