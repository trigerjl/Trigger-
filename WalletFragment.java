package com.triger.poc;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WalletFragment extends Fragment implements MainActivity.Listener {

    private TextView tvBalance;
    private TextView tvAddress;
    private LinearLayout historyContainer;
    private TextView tvHistoryEmpty;
    private BalanceChartView balanceChart;
    private MainActivity activity;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wallet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvBalance = view.findViewById(R.id.tv_wallet_balance);
        tvAddress = view.findViewById(R.id.tv_wallet_address);
        historyContainer = view.findViewById(R.id.history_container);
        tvHistoryEmpty = view.findViewById(R.id.tv_history_empty);
        balanceChart = view.findViewById(R.id.balance_chart);

        view.findViewById(R.id.btn_copy_address).setOnClickListener(v -> {
            if (activity != null) {
                activity.copyText("validator_address", activity.getAddress());
            }
        });
        view.findViewById(R.id.btn_top_up).setOnClickListener(v -> {
            if (activity != null) {
                activity.showToast("Пополнение появится в следующих версиях");
            }
        });
        view.findViewById(R.id.btn_withdraw).setOnClickListener(v -> {
            if (activity != null) {
                activity.showToast("Вывод появится в следующих версиях");
            }
        });

        buildHistory();
        updateChart();

        if (getActivity() instanceof MainActivity) {
            activity = (MainActivity) getActivity();
            activity.addListener(this);
            activity.updateBalance();
        }
    }

    private void updateChart() {
        if (balanceChart == null || getContext() == null) {
            return;
        }
        List<TransactionHistory.Transaction> txs =
                TransactionHistory.getTransactions(requireContext());
        if (txs.isEmpty()) {
            balanceChart.setValues(Collections.emptyList());
            return;
        }
        // Сортируем по времени от старых к новым, считаем накопительный баланс
        List<TransactionHistory.Transaction> sorted = new ArrayList<>(txs);
        Collections.sort(sorted, new Comparator<TransactionHistory.Transaction>() {
            @Override
            public int compare(TransactionHistory.Transaction a, TransactionHistory.Transaction b) {
                return Long.compare(a.timestamp, b.timestamp);
            }
        });
        List<Float> points = new ArrayList<>();
        double balance = 0;
        for (TransactionHistory.Transaction tx : sorted) {
            if (TransactionHistory.TYPE_SEND.equals(tx.type)
                    || TransactionHistory.TYPE_BURN.equals(tx.type)) {
                balance -= tx.amount;
            } else {
                balance += tx.amount;
            }
            points.add((float) balance);
        }
        balanceChart.setValues(points);
    }

    private void buildHistory() {
        if (historyContainer == null) {
            return;
        }
        historyContainer.removeAllViews();
        List<TransactionHistory.Transaction> transactions =
                TransactionHistory.getTransactions(requireContext());
        if (tvHistoryEmpty != null) {
            tvHistoryEmpty.setVisibility(transactions.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (transactions.isEmpty()) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(getContext());
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        for (TransactionHistory.Transaction tx : transactions) {
            View item = inflater.inflate(R.layout.item_transaction, historyContainer, false);
            ((TextView) item.findViewById(R.id.tv_tx_title)).setText(titleFor(tx.type));
            ((TextView) item.findViewById(R.id.tv_tx_subtitle)).setText(dateFormat.format(new Date(tx.timestamp)));
            ((ImageView) item.findViewById(R.id.iv_tx_icon)).setImageResource(iconFor(tx.type));

            boolean outgoing = TransactionHistory.TYPE_SEND.equals(tx.type)
                    || TransactionHistory.TYPE_BURN.equals(tx.type);
            TextView tvAmount = item.findViewById(R.id.tv_tx_amount);
            tvAmount.setText((outgoing ? "-" : "+")
                    + Tokenomics.formatAmount(tx.amount) + " TRG");
            tvAmount.setTextColor(ContextCompat.getColor(requireContext(),
                    outgoing ? R.color.danger_red : R.color.success_green));

            historyContainer.addView(item);
        }
    }

    private String titleFor(String type) {
        if (TransactionHistory.TYPE_SEND.equals(type)) {
            return "Отправлено";
        }
        if (TransactionHistory.TYPE_RECEIVE.equals(type)) {
            return "Получено";
        }
        if (TransactionHistory.TYPE_NETWORK_BONUS.equals(type)) {
            return "Бонус за поддержку сети";
        }
        if (TransactionHistory.TYPE_BURN.equals(type)) {
            return "Сжигание комиссии";
        }
        return "Награда за майнинг";
    }

    private int iconFor(String type) {
        if (TransactionHistory.TYPE_SEND.equals(type)) {
            return R.drawable.ic_arrow_up;
        }
        if (TransactionHistory.TYPE_RECEIVE.equals(type)) {
            return R.drawable.ic_arrow_down;
        }
        if (TransactionHistory.TYPE_NETWORK_BONUS.equals(type)) {
            return R.drawable.ic_network;
        }
        if (TransactionHistory.TYPE_BURN.equals(type)) {
            return R.drawable.ic_arrow_up;
        }
        return R.drawable.ic_mining;
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
    public void onBalance(String text) {
        if (tvBalance != null) {
            tvBalance.setText(text);
        }
        buildHistory();
        updateChart();
    }

    @Override
    public void onAddress(String address) {
        if (tvAddress != null) {
            tvAddress.setText(address);
        }
    }

    @Override
    public void onTheme(boolean dark) {
        // theme handled by activity recreate
    }
}
