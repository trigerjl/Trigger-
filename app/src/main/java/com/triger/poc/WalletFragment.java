package com.triger.poc;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WalletFragment extends Fragment
        implements MainActivity.Listener, P2PExchangeManager.Listener,
        PriceOracle.Listener {

    private static final String PREF_BALANCE_HIDDEN = "wallet_balance_hidden";
    private static final Pattern BALANCE_NUMBER =
            Pattern.compile("[-+]?\\d+(?:[.,]\\d+)?");

    private TextView tvBalance;
    private TextView tvLocalBalance;
    private TextView tvPriceOracle;
    private ImageView balanceVisibility;
    private String balanceText = "";
    private boolean balanceHidden;
    private ValueAnimator balanceAnimator;
    private Double animatedBalanceValue;
    private TextView tvAddress;
    private EditText historySearch;
    private View historyNoResults;
    private LinearLayout historyContainer;
    private View historyEmpty;
    private View chartEmpty;
    private BalanceChartView balanceChart;
    private SwipeRefreshLayout swipeRefresh;
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
        tvLocalBalance = view.findViewById(R.id.tv_wallet_local_balance);
        tvPriceOracle = view.findViewById(R.id.tv_wallet_price_oracle);
        balanceVisibility = view.findViewById(R.id.btn_toggle_balance_visibility);
        balanceText = tvBalance.getText() == null ? "" : tvBalance.getText().toString();
        animatedBalanceValue = parseBalanceValue(balanceText);
        SharedPreferences prefs = requireContext().getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        balanceHidden = prefs.getBoolean(PREF_BALANCE_HIDDEN, false);
        balanceVisibility.setOnClickListener(v -> toggleBalanceVisibility());
        applyBalanceVisibility();
        tvAddress = view.findViewById(R.id.tv_wallet_address);
        historySearch = view.findViewById(R.id.et_history_search);
        historyNoResults = view.findViewById(R.id.tv_history_no_results);
        historyContainer = view.findViewById(R.id.history_container);
        historyEmpty = view.findViewById(R.id.history_empty);
        chartEmpty = view.findViewById(R.id.chart_empty);
        balanceChart = view.findViewById(R.id.balance_chart);
        swipeRefresh = view.findViewById(R.id.swipe_refresh_wallet);
        swipeRefresh.setOnRefreshListener(() -> {
            if (activity != null) {
                activity.updateBalance();
                activity.updateChatBadge();
            } else {
                buildHistory();
                updateChart();
            }
            updatePriceOracle();
            swipeRefresh.setRefreshing(false);
        });

        if (historySearch != null) {
            historySearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    buildHistory();
                }
            });
        }

        tvAddress.setOnClickListener(v -> showWalletQrSheet());
        tvAddress.setOnLongClickListener(v -> {
            copyAddress();
            return true;
        });
        view.findViewById(R.id.btn_wallet_receive).setOnClickListener(v -> showWalletQrSheet());
        view.findViewById(R.id.btn_wallet_send).setOnClickListener(v -> startTransfer());
        view.findViewById(R.id.btn_chart_start_mining).setOnClickListener(v -> openMiningTab());
        view.findViewById(R.id.btn_history_start_mining)
                .setOnClickListener(v -> openMiningTab());

        buildHistory();
        updateChart();
        updatePriceOracle();
        updateLocalBalance();
        P2PExchangeManager.addListener(this);
        PriceOracle.addListener(this);

        if (getActivity() instanceof MainActivity) {
            activity = (MainActivity) getActivity();
            activity.addListener(this);
            activity.updateBalance();
        }
    }

    private void openMiningTab() {
        if (activity != null) {
            activity.openMiningTab();
        } else if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openMiningTab();
        }
    }

    private void updatePriceOracle() {
        if (tvPriceOracle == null || getContext() == null) {
            return;
        }
        String currency = CurrencyConverter.resolveDisplayCurrency(requireContext());
        Double price = new PriceOracle(requireContext()).getPrice(currency);
        if (price == null || price <= 0.0) {
            tvPriceOracle.setText(R.string.price_oracle_undefined);
            return;
        }
        String formatted = BigDecimal.valueOf(price)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
        tvPriceOracle.setText(getString(R.string.price_oracle_in_market_currency,
                formatted, CurrencyConverter.currencySymbol(currency)));
    }

    private void updateLocalBalance() {
        if (tvLocalBalance == null || getContext() == null) {
            return;
        }
        if (balanceHidden) {
            tvLocalBalance.setVisibility(View.GONE);
            return;
        }
        final Double amountTRG = parseBalanceValue(balanceText);
        if (amountTRG == null) {
            tvLocalBalance.setVisibility(View.GONE);
            return;
        }
        final CurrencyConverter converter = new CurrencyConverter(requireContext());
        final String currency = CurrencyConverter.resolveDisplayCurrency(requireContext());
        converter.fetchRates(new CurrencyConverter.Callback() {
            @Override
            public void onRates(java.util.Map<String, Double> rates) {
                if (!isAdded() || tvLocalBalance == null) {
                    return;
                }
                double local = converter.convertToLocal(amountTRG, currency);
                if (Double.isNaN(local) || Double.isInfinite(local) || local < 0.0) {
                    tvLocalBalance.setVisibility(View.GONE);
                    return;
                }
                tvLocalBalance.setText(getString(R.string.wallet_local_balance,
                        formatLocalAmount(local),
                        CurrencyConverter.currencySymbol(currency)));
                tvLocalBalance.setVisibility(View.VISIBLE);
                updatePriceOracle();
            }

            @Override
            public void onError(Exception error) {
                if (!isAdded() || tvLocalBalance == null) {
                    return;
                }
                tvLocalBalance.setVisibility(View.GONE);
            }
        });
    }

    private String formatLocalAmount(double amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setGroupingUsed(true);
        format.setMaximumFractionDigits(amount >= 1000.0 ? 0 : 2);
        format.setMinimumFractionDigits(0);
        return format.format(amount);
    }

    @Override
    public void onExchangeChanged() {
        View view = getView();
        if (view == null) {
            return;
        }
        view.post(() -> {
            updatePriceOracle();
            updateLocalBalance();
        });
    }

    @Override
    public void onPriceChanged() {
        View view = getView();
        if (view == null) {
            return;
        }
        view.post(this::updatePriceOracle);
    }

    private void toggleBalanceVisibility() {
        balanceHidden = !balanceHidden;
        requireContext().getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_BALANCE_HIDDEN, balanceHidden)
                .apply();
        applyBalanceVisibility();
    }

    private void applyBalanceVisibility() {
        if (tvBalance == null || balanceVisibility == null || getContext() == null) {
            return;
        }
        if (balanceHidden) {
            cancelBalanceAnimation();
            tvBalance.setText(getString(R.string.wallet_balance_hidden));
            if (tvLocalBalance != null) {
                tvLocalBalance.setVisibility(View.GONE);
            }
        } else {
            tvBalance.setText(balanceText);
            animatedBalanceValue = parseBalanceValue(balanceText);
            updateLocalBalance();
        }
        balanceVisibility.setImageResource(balanceHidden
                ? R.drawable.ic_visibility_off
                : R.drawable.ic_visibility);
        balanceVisibility.setContentDescription(getString(balanceHidden
                ? R.string.wallet_balance_show
                : R.string.wallet_balance_hide));
    }

    private void startTransfer() {
        startActivity(new Intent(requireContext(), TransferActivity.class));
    }

    private void copyAddress() {
        String address = activity == null ? "" : activity.getAddress();
        if (TextUtils.isEmpty(address) && tvAddress != null) {
            address = tvAddress.getText() == null ? "" : tvAddress.getText().toString().trim();
        }
        if (TextUtils.isEmpty(address)) {
            Toast.makeText(requireContext(), R.string.wallet_address_not_ready,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (activity != null) {
            activity.copyText("wallet_address", address);
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("wallet_address", address));
        }
        Toast.makeText(requireContext(), R.string.toast_address_copied,
                Toast.LENGTH_SHORT).show();
    }

    private void showWalletQrSheet() {
        String address = activity == null ? "" : activity.getAddress();
        if (TextUtils.isEmpty(address) && tvAddress != null) {
            address = tvAddress.getText() == null ? "" : tvAddress.getText().toString().trim();
        }
        if (TextUtils.isEmpty(address)) {
            Toast.makeText(requireContext(), R.string.wallet_address_not_ready,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        WalletQrSheet.newInstance(address)
                .show(requireActivity().getSupportFragmentManager(), "wallet_qr");
    }

    private void updateChart() {
        if (balanceChart == null || getContext() == null) {
            return;
        }
        List<TransactionHistory.Transaction> txs =
                TransactionHistory.getTransactions(requireContext());
        if (txs.isEmpty()) {
            balanceChart.setData(Collections.emptyList(), Collections.emptyList());
            balanceChart.setVisibility(View.GONE);
            if (chartEmpty != null) {
                chartEmpty.setVisibility(View.VISIBLE);
            }
            return;
        }
        balanceChart.setVisibility(View.VISIBLE);
        if (chartEmpty != null) {
            chartEmpty.setVisibility(View.GONE);
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
        List<Long> times = new ArrayList<>();
        double balance = 0;
        for (TransactionHistory.Transaction tx : sorted) {
            balance += TransactionHistory.signedAmount(tx.type, tx.amount);
            points.add((float) balance);
            times.add(tx.timestamp);
        }
        balanceChart.setData(points, times);
    }

    private void buildHistory() {
        if (historyContainer == null) {
            return;
        }
        historyContainer.removeAllViews();
        List<TransactionHistory.Transaction> allTransactions =
                TransactionHistory.getTransactions(requireContext());
        boolean hasAny = !allTransactions.isEmpty();
        if (historySearch != null) {
            historySearch.setVisibility(hasAny ? View.VISIBLE : View.GONE);
        }
        if (historyEmpty != null) {
            historyEmpty.setVisibility(hasAny ? View.GONE : View.VISIBLE);
        }

        String query = historySearch == null || historySearch.getText() == null
                ? ""
                : historySearch.getText().toString().trim().toLowerCase(Locale.getDefault());
        List<TransactionHistory.Transaction> transactions = new ArrayList<>();
        for (TransactionHistory.Transaction tx : allTransactions) {
            if (query.isEmpty() || matchesQuery(tx, query)) {
                transactions.add(tx);
            }
        }

        boolean noResults = hasAny && transactions.isEmpty();
        if (historyNoResults != null) {
            historyNoResults.setVisibility(noResults ? View.VISIBLE : View.GONE);
        }
        historyContainer.setVisibility(transactions.isEmpty() ? View.GONE : View.VISIBLE);
        if (transactions.isEmpty()) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(getContext());
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        for (TransactionHistory.Transaction tx : transactions) {
            View item = inflater.inflate(R.layout.item_transaction, historyContainer, false);
            ((TextView) item.findViewById(R.id.tv_tx_title)).setText(titleFor(tx.type));
            String subtitle = dateFormat.format(new Date(tx.timestamp));
            String memo = tx.memo == null ? "" : tx.memo.trim().replace('\n', ' ');
            if (!memo.isEmpty()) {
                subtitle = subtitle + " — " + memo;
            }
            ((TextView) item.findViewById(R.id.tv_tx_subtitle)).setText(subtitle);
            ((ImageView) item.findViewById(R.id.iv_tx_icon)).setImageResource(iconFor(tx.type));

            boolean outgoing = TransactionHistory.TYPE_SEND.equals(tx.type)
                    || TransactionHistory.TYPE_BURN.equals(tx.type);
            TextView tvAmount = item.findViewById(R.id.tv_tx_amount);
            tvAmount.setText(getString(R.string.amount_signed_format,
                    outgoing ? "-" : "+",
                    Tokenomics.formatAmount(Math.abs(tx.amount))));
            tvAmount.setTextColor(ContextCompat.getColor(requireContext(),
                    outgoing ? R.color.danger_red : R.color.success_green));
            item.setOnClickListener(v -> TransactionDetailSheet.newInstance(
                    tx.type, tx.amount, tx.timestamp, tx.hash, tx.memo)
                    .show(requireActivity().getSupportFragmentManager(), "transaction_detail"));

            historyContainer.addView(item);
        }
    }

    private boolean matchesQuery(TransactionHistory.Transaction tx, String query) {
        String lowerQuery = query.toLowerCase(Locale.getDefault());
        String title = titleFor(tx.type).toLowerCase(Locale.getDefault());
        String type = tx.type == null ? "" : tx.type.toLowerCase(Locale.getDefault());
        String hash = tx.hash == null ? "" : tx.hash.toLowerCase(Locale.getDefault());
        String memo = tx.memo == null ? "" : tx.memo.toLowerCase(Locale.getDefault());
        String amount = Tokenomics.formatAmount(tx.amount).toLowerCase(Locale.getDefault());
        String amountWithUnit = amount + " trg " + amount + " трг";
        String signedAmount = ("+" + amount + " -" + amount);
        return title.contains(lowerQuery)
                || type.contains(lowerQuery)
                || hash.contains(lowerQuery)
                || memo.contains(lowerQuery)
                || amount.contains(lowerQuery)
                || amountWithUnit.contains(lowerQuery)
                || signedAmount.contains(lowerQuery);
    }

    private String titleFor(String type) {
        if (TransactionHistory.TYPE_SEND.equals(type)) {
            return getString(R.string.tx_sent);
        }
        if (TransactionHistory.TYPE_RECEIVE.equals(type)) {
            return getString(R.string.tx_received);
        }
        if (TransactionHistory.TYPE_BURN.equals(type)) {
            return getString(R.string.tx_fee_burn);
        }
        return getString(R.string.tx_mining_reward);
    }

    private int iconFor(String type) {
        if (TransactionHistory.TYPE_SEND.equals(type)) {
            return R.drawable.ic_arrow_up;
        }
        if (TransactionHistory.TYPE_RECEIVE.equals(type)) {
            return R.drawable.ic_arrow_down;
        }
        if (TransactionHistory.TYPE_BURN.equals(type)) {
            return R.drawable.ic_arrow_up;
        }
        return R.drawable.ic_mining;
    }

    @Override
    public void onDestroyView() {
        P2PExchangeManager.removeListener(this);
        PriceOracle.removeListener(this);
        if (activity != null) {
            activity.removeListener(this);
            activity = null;
        }
        cancelBalanceAnimation();
        swipeRefresh = null;
        tvPriceOracle = null;
        tvLocalBalance = null;
        tvBalance = null;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateLocalBalance();
    }

    @Override
    public void onBalance(String text) {
        balanceText = text == null ? "" : text;
        if (balanceHidden) {
            if (tvBalance != null) {
                tvBalance.setText(getString(R.string.wallet_balance_hidden));
            }
        } else {
            animateBalanceTo(balanceText);
        }
        buildHistory();
        updateChart();
        updatePriceOracle();
        updateLocalBalance();
    }

    private void animateBalanceTo(String text) {
        if (tvBalance == null) {
            return;
        }
        Double target = parseBalanceValue(text);
        if (target == null) {
            cancelBalanceAnimation();
            tvBalance.setText(text);
            return;
        }
        String current = tvBalance.getText() == null ? "" : tvBalance.getText().toString();
        if (animatedBalanceValue != null
                && Math.abs(animatedBalanceValue - target) < 0.0001d
                && text.equals(current)) {
            return;
        }
        cancelBalanceAnimation();
        double start = animatedBalanceValue == null ? 0d : animatedBalanceValue;
        final ValueAnimator animator =
                ValueAnimator.ofFloat((float) start, target.floatValue());
        animator.setDuration(500L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float value = (float) a.getAnimatedValue();
            animatedBalanceValue = (double) value;
            if (tvBalance != null && !balanceHidden && isAdded()) {
                tvBalance.setText(formatBalanceValue(value));
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                animatedBalanceValue = target;
                if (tvBalance != null && !balanceHidden && isAdded()) {
                    tvBalance.setText(text);
                }
            }
        });
        balanceAnimator = animator;
        animator.start();
    }

    private void cancelBalanceAnimation() {
        if (balanceAnimator != null) {
            balanceAnimator.removeAllListeners();
            balanceAnimator.cancel();
            balanceAnimator = null;
        }
    }

    private String formatBalanceValue(float value) {
        String number = BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
        return getString(R.string.amount_format, number);
    }

    private Double parseBalanceValue(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = BALANCE_NUMBER.matcher(text.replace('\u00A0', ' '));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
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
