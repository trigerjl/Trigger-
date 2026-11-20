package com.triger.poc;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.List;
import java.util.Locale;

public class MiningFragment extends Fragment implements MainActivity.Listener {

    private static final String KEY_UPTIME_PAUSED_AT = "mining_uptime_paused_at";
    private static final String KEY_UPTIME_OFFLINE_TOTAL = "mining_uptime_offline_total";
    private static final int REQUEST_PHONE_STATE = 202;

    private TextView tvStatus;
    private View viewDot;
    private View btnStartService;
    private View miningButtonSurface;
    private View pulseHalo;
    private TextView tvMiningButtonLabel;
    private TextView tvMiningHint;
    private TextView tvNextBlock;
    private TextView tvStatLastHash;
    private TextView tvStatTotalBlocks;
    private TextView tvStatAvgReward;
    private TextView tvStatUptime;
    private SwipeRefreshLayout swipeRefresh;
    private MainActivity activity;
    private boolean serviceRunning;
    private boolean observerMode;
    private boolean networkStateInitialized;
    private boolean lastNetworkOnline = true;
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            updateExtendedStats();
            statsHandler.postDelayed(this, 1000L);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mining, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvStatus = view.findViewById(R.id.tv_mining_status);
        viewDot = view.findViewById(R.id.view_status_dot);
        btnStartService = view.findViewById(R.id.btn_start_service);
        miningButtonSurface = view.findViewById(R.id.view_mining_button_surface);
        pulseHalo = view.findViewById(R.id.view_pulse_halo);
        tvMiningButtonLabel = view.findViewById(R.id.tv_mining_button_label);
        tvMiningHint = view.findViewById(R.id.tv_mining_hint);
        tvNextBlock = view.findViewById(R.id.tv_next_block);
        tvStatLastHash = view.findViewById(R.id.tv_stat_last_hash);
        tvStatTotalBlocks = view.findViewById(R.id.tv_stat_total_blocks);
        tvStatAvgReward = view.findViewById(R.id.tv_stat_avg_reward);
        tvStatUptime = view.findViewById(R.id.tv_stat_uptime);
        swipeRefresh = view.findViewById(R.id.swipe_refresh_mining);
        swipeRefresh.setOnRefreshListener(() -> {
            if (activity != null) {
                activity.refreshMiningStats();
            }
            updateExtendedStats();
            swipeRefresh.setRefreshing(false);
        });

        observerMode = !SimIdentity.hasRealSim(requireContext());

        btnStartService.setOnClickListener(v -> {
            Log.d("Mining", "Start button clicked");
            animateButtonPress(v);
            if (observerMode) {
                Log.d("Mining", "Start blocked: observer mode (no real SIM)");
                Toast.makeText(requireContext(), R.string.mining_requires_sim,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (activity == null) {
                Log.d("Mining", "Start aborted: activity is null");
                return;
            }
            if (activity.isServiceRunning()) {
                Log.d("Mining", "Start button: service running, stopping");
                activity.toggleMiningService();
                return;
            }
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireContext(), R.string.permission_phone_state_rationale,
                        Toast.LENGTH_LONG).show();
                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{Manifest.permission.READ_PHONE_STATE},
                        REQUEST_PHONE_STATE);
                return;
            }
            startMiningIfSimAvailable();
        });

        if (getActivity() instanceof MainActivity) {
            activity = (MainActivity) getActivity();
            activity.addListener(this);
        }
        applyObserverMode();
        statsHandler.removeCallbacks(statsTick);
        statsHandler.post(statsTick);
    }

    private void applyObserverMode() {
        if (btnStartService == null) {
            return;
        }
        if (!observerMode) {
            btnStartService.setEnabled(true);
            btnStartService.setClickable(true);
            if (miningButtonSurface != null && !serviceRunning) {
                miningButtonSurface.setBackgroundResource(R.drawable.bg_mining_button_start);
            }
            updateStatusUi();
            return;
        }
        btnStartService.setEnabled(false);
        btnStartService.setClickable(false);
        if (miningButtonSurface != null) {
            miningButtonSurface.setBackgroundResource(R.drawable.bg_mining_button_disabled);
        }
        if (tvMiningButtonLabel != null) {
            tvMiningButtonLabel.setText(R.string.mining_start);
        }
        if (tvMiningHint != null) {
            tvMiningHint.setText(R.string.mining_requires_sim);
        }
        updateStatusUi();
        updateMiningAnimation(false);
    }

    private void startMiningIfSimAvailable() {
        if (activity == null) {
            Log.d("Mining", "startMiningIfSimAvailable: activity is null");
            return;
        }
        if (!SimIdentity.hasRealSim(requireContext())) {
            Log.d("Mining", "startMiningIfSimAvailable: no real SIM");
            Toast.makeText(requireContext(), R.string.sim_missing_mining,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("Mining", "startMiningIfSimAvailable: calling toggleMiningService");
        activity.toggleMiningService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PHONE_STATE) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMiningIfSimAvailable();
        } else {
            Toast.makeText(requireContext(), R.string.permission_phone_state_denied,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() == null) {
            return;
        }
        boolean observer = !SimIdentity.hasRealSim(requireContext());
        if (observer != observerMode) {
            observerMode = observer;
            applyObserverMode();
        }
    }

    @Override
    public void onDestroyView() {
        statsHandler.removeCallbacks(statsTick);
        if (activity != null) {
            activity.removeListener(this);
            activity = null;
        }
        swipeRefresh = null;
        super.onDestroyView();
    }

    @Override
    public void onMiningStats(int count, String lastHash) {
        if (tvStatTotalBlocks != null) {
            tvStatTotalBlocks.setText(String.valueOf(count));
        }
        if (tvStatLastHash != null) {
            tvStatLastHash.setText(lastHash == null
                    ? getString(R.string.placeholder_dash) : lastHash);
        }
        updateExtendedStats();
    }

    @Override
    public void onServiceState(boolean running) {
        serviceRunning = running;
        if (observerMode) {
            applyObserverMode();
            updateExtendedStats();
            return;
        }
        refreshNetworkState();
        if (miningButtonSurface != null) {
            miningButtonSurface.setBackgroundResource(running
                    ? R.drawable.bg_mining_button_stop
                    : R.drawable.bg_mining_button_start);
        }
        if (tvMiningButtonLabel != null) {
            tvMiningButtonLabel.setText(running ? R.string.mining_stop : R.string.mining_start);
        }
        if (tvMiningHint != null) {
            tvMiningHint.setText(running
                    ? R.string.mining_hint_stop
                    : R.string.mining_hint_start);
        }
        updateMiningAnimation(running);
        updateExtendedStats();
    }

    private void updateExtendedStats() {
        if (getContext() == null || tvStatAvgReward == null) {
            return;
        }
        refreshNetworkState();
        BlockStorage storage = new BlockStorage(requireContext());

        // Средняя награда: сумма наград из TransactionHistory / количество наград
        List<TransactionHistory.Transaction> txs =
                TransactionHistory.getTransactions(requireContext());
        double rewardSum = 0;
        int rewardCount = 0;
        for (TransactionHistory.Transaction tx : txs) {
            if (TransactionHistory.TYPE_MINING_REWARD.equals(tx.type)) {
                rewardSum += tx.amount;
                rewardCount++;
            }
        }
        double avg = rewardCount > 0 ? rewardSum / rewardCount : 0;
        tvStatAvgReward.setText(getString(R.string.amount_format,
                String.format(Locale.getDefault(), "%.1f", avg)));

        SharedPreferences prefs = requireContext()
                .getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        boolean miningActive = prefs.getBoolean(SignerService.KEY_MINING_ACTIVE, false);
        updateNextBlockTimer(prefs, miningActive);
        if (!miningActive) {
            // Майнинг остановлен — таймер не должен продолжать считать.
            prefs.edit()
                    .remove(KEY_UPTIME_PAUSED_AT)
                    .remove(KEY_UPTIME_OFFLINE_TOTAL)
                    .apply();
            networkStateInitialized = false;
            tvStatUptime.setText(R.string.placeholder_dash);
            return;
        }

        // Время в сети: от старта майнинга (или от первого блока/транзакции).
        long firstEvent = prefs.getLong(SignerService.KEY_MINING_START_TIME, 0L);
        if (firstEvent <= 0L) {
            for (TransactionHistory.Transaction tx : txs) {
                if (firstEvent == 0 || tx.timestamp < firstEvent) {
                    firstEvent = tx.timestamp;
                }
            }
        }
        if (firstEvent <= 0L) {
            // Если истории ещё нет — берём первый блок
            String b0 = storage.getBlock(0);
            if (b0 != null) {
                try {
                    org.json.JSONObject obj = new org.json.JSONObject(b0);
                    firstEvent = obj.optLong("timestamp", 0);
                } catch (Exception ignored) {
                }
            }
        }
        if (firstEvent > 0) {
            long now = System.currentTimeMillis();
            long pausedAt = prefs.getLong(KEY_UPTIME_PAUSED_AT, 0L);
            long offlineTotal = prefs.getLong(KEY_UPTIME_OFFLINE_TOTAL, 0L);
            long effectiveNow = pausedAt > 0L ? pausedAt : now;
            long elapsedMs = Math.max(0L, effectiveNow - firstEvent - offlineTotal);
            tvStatUptime.setText(formatDuration(elapsedMs));
        } else {
            prefs.edit()
                    .remove(KEY_UPTIME_PAUSED_AT)
                    .remove(KEY_UPTIME_OFFLINE_TOTAL)
                    .apply();
            tvStatUptime.setText(R.string.placeholder_dash);
        }
    }

    private void updateNextBlockTimer(SharedPreferences prefs, boolean miningActive) {
        if (tvNextBlock == null) {
            return;
        }
        if (!miningActive || observerMode) {
            tvNextBlock.setText(R.string.placeholder_dash);
            return;
        }
        long nextAt = prefs.getLong(SignerService.KEY_NEXT_BLOCK_AT, 0L);
        if (nextAt <= 0L) {
            tvNextBlock.setText(R.string.placeholder_dash);
            return;
        }
        long remainingMs = Math.max(0L, nextAt - System.currentTimeMillis());
        long totalSeconds = (remainingMs + 999L) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        tvNextBlock.setText(getString(R.string.mining_next_block_in,
                String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)));
    }

    private void refreshNetworkState() {
        if (getContext() == null) {
            return;
        }
        boolean online = isNetworkOnline();
        long now = System.currentTimeMillis();
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        if (!networkStateInitialized) {
            lastNetworkOnline = prefs.getLong(KEY_UPTIME_PAUSED_AT, 0L) <= 0L;
            networkStateInitialized = true;
        }
        if (online != lastNetworkOnline) {
            if (!online) {
                prefs.edit().putLong(KEY_UPTIME_PAUSED_AT, now).apply();
            } else {
                long pausedAt = prefs.getLong(KEY_UPTIME_PAUSED_AT, 0L);
                if (pausedAt > 0L) {
                    long offlineTotal = prefs.getLong(KEY_UPTIME_OFFLINE_TOTAL, 0L)
                            + Math.max(0L, now - pausedAt);
                    prefs.edit()
                            .putLong(KEY_UPTIME_OFFLINE_TOTAL, offlineTotal)
                            .remove(KEY_UPTIME_PAUSED_AT)
                            .apply();
                }
            }
            lastNetworkOnline = online;
        }
        updateStatusUi();
    }

    private boolean isNetworkOnline() {
        try {
            if (!IrohNode.getInstance().getConnectedPeers().isEmpty()) {
                return true;
            }
            if (P2PNode.getInstance().getConnectedPeers() > 0) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) requireContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return false;
            }
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(active);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) {
            return false;
        }
    }

    private void updateStatusUi() {
        if (tvStatus == null) {
            return;
        }
        if (observerMode) {
            tvStatus.setText(R.string.observer_status);
            if (viewDot != null) {
                viewDot.setBackgroundResource(R.drawable.bg_status_dot_orange);
            }
            return;
        }
        if (serviceRunning) {
            tvStatus.setText(hasConnectedPeers()
                    ? R.string.mining_network_mode : R.string.mining_local_mode);
        } else {
            tvStatus.setText(R.string.mining_stopped);
        }
        if (viewDot != null) {
            viewDot.setBackgroundResource(serviceRunning
                    ? R.drawable.bg_status_dot
                    : R.drawable.bg_status_dot_orange);
        }
    }

    private boolean hasConnectedPeers() {
        try {
            if (!IrohNode.getInstance().getConnectedPeers().isEmpty()) {
                return true;
            }
            if (P2PNode.getInstance().getConnectedPeers() > 0) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String formatDuration(long ms) {
        long sec = ms / 1000;
        long min = sec / 60;
        long hr = min / 60;
        long day = hr / 24;
        if (day > 0) {
            return getString(R.string.mining_duration_days_hours, day, hr % 24);
        }
        if (hr > 0) {
            return getString(R.string.mining_duration_hours_minutes, hr, min % 60);
        }
        if (min > 0) {
            return getString(R.string.mining_duration_minutes_seconds, min, sec % 60);
        }
        return getString(R.string.mining_duration_seconds, sec);
    }

    private void updateMiningAnimation(boolean running) {
        if (pulseHalo == null || getContext() == null) {
            return;
        }
        if (observerMode) {
            running = false;
        }
        pulseHalo.clearAnimation();
        pulseHalo.setScaleX(1f);
        pulseHalo.setScaleY(1f);
        pulseHalo.setAlpha(running ? 1f : 0.22f);
        if (running) {
            pulseHalo.startAnimation(
                    AnimationUtils.loadAnimation(getContext(), R.anim.mining_pulse));
        }
    }

    private void animateButtonPress(View view) {
        view.animate().cancel();
        view.animate()
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(90)
                .withEndAction(() -> view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(140)
                        .start())
                .start();
    }
}
