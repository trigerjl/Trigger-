package com.triger.poc;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;

public class MiningFragment extends Fragment implements MainActivity.Listener {

    private TextView tvStatus;
    private View viewDot;
    private ImageView ivMiningAnim;
    private TextView tvStatBlocks;
    private TextView tvStatLastHash;
    private TextView tvStatTotalBlocks;
    private TextView tvStatAvgReward;
    private TextView tvStatUptime;
    private MaterialButton btnStartService;
    private MainActivity activity;

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
        ivMiningAnim = view.findViewById(R.id.iv_mining_anim);
        tvStatBlocks = view.findViewById(R.id.tv_stat_blocks);
        tvStatLastHash = view.findViewById(R.id.tv_stat_last_hash);
        tvStatTotalBlocks = view.findViewById(R.id.tv_stat_total_blocks);
        tvStatAvgReward = view.findViewById(R.id.tv_stat_avg_reward);
        tvStatUptime = view.findViewById(R.id.tv_stat_uptime);
        btnStartService = view.findViewById(R.id.btn_start_service);

        btnStartService.setOnClickListener(v -> {
            if (activity != null) {
                activity.toggleMiningService();
            }
        });
        view.findViewById(R.id.btn_mining_settings).setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), SettingsActivity.class));
        });
        view.findViewById(R.id.btn_payout_history).setOnClickListener(v -> {
            if (activity != null) {
                activity.openWalletTab();
            }
        });
        view.findViewById(R.id.btn_reset_counter).setOnClickListener(v -> {
            if (activity != null) {
                activity.resetMinedBlocksCounter();
            }
        });

        if (getActivity() instanceof MainActivity) {
            activity = (MainActivity) getActivity();
            activity.addListener(this);
        }
    }

    @Override
    public void onDestroyView() {
        if (activity != null) {
            activity.removeListener(this);
            activity = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onMiningStats(int count, String lastHash) {
        if (tvStatBlocks != null) {
            tvStatBlocks.setText(String.valueOf(count));
        }
        if (tvStatLastHash != null) {
            tvStatLastHash.setText(lastHash == null ? "—" : lastHash);
        }
        updateExtendedStats();
    }

    @Override
    public void onServiceState(boolean running) {
        if (tvStatus != null) {
            tvStatus.setText(running ? "Активен" : "Остановлен");
        }
        if (viewDot != null) {
            viewDot.setBackgroundResource(running ? R.drawable.bg_status_dot : R.drawable.bg_status_dot_red);
        }
        updateMiningAnimation(running);
        if (btnStartService != null) {
            btnStartService.setText(running ? "Остановить майнинг" : "Запустить майнинг");
            int color = ContextCompat.getColor(requireContext(), running ? R.color.danger_red : R.color.colorPrimary);
            btnStartService.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        }
        updateExtendedStats();
    }

    private void updateExtendedStats() {
        if (getContext() == null || tvStatTotalBlocks == null) {
            return;
        }
        // Всего блоков за все время (из BlockStorage)
        BlockStorage storage = new BlockStorage(requireContext());
        int totalBlocks = storage.getBlockCount();
        if (totalBlocks < 0) totalBlocks = 0;
        tvStatTotalBlocks.setText(String.valueOf(totalBlocks));

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
        tvStatAvgReward.setText(String.format(Locale.getDefault(), "%.1f TRG", avg));

        // Время в сети: от первого запуска (первый блок или первая транзакция)
        long firstEvent = 0;
        for (TransactionHistory.Transaction tx : txs) {
            if (firstEvent == 0 || tx.timestamp < firstEvent) {
                firstEvent = tx.timestamp;
            }
        }
        if (firstEvent == 0) {
            // Если транзакций нет — берём первый блок
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
            long elapsedMs = System.currentTimeMillis() - firstEvent;
            tvStatUptime.setText(formatDuration(elapsedMs));
        } else {
            tvStatUptime.setText("—");
        }
    }

    private String formatDuration(long ms) {
        long sec = ms / 1000;
        long min = sec / 60;
        long hr = min / 60;
        long day = hr / 24;
        if (day > 0) {
            return String.format(Locale.getDefault(), "%dд %dч", day, hr % 24);
        }
        if (hr > 0) {
            return String.format(Locale.getDefault(), "%dч %dм", hr, min % 60);
        }
        if (min > 0) {
            return String.format(Locale.getDefault(), "%dм %dс", min, sec % 60);
        }
        return sec + "с";
    }

    private void updateMiningAnimation(boolean running) {
        if (ivMiningAnim == null || getContext() == null) {
            return;
        }
        if (running) {
            ivMiningAnim.startAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.mining_pulse));
        } else {
            ivMiningAnim.clearAnimation();
        }
    }
}
