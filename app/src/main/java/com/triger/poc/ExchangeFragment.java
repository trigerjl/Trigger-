package com.triger.poc;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ExchangeFragment extends Fragment {

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Set<String> promptedDeals = new HashSet<>();

    private P2PExchangeManager exchangeManager;
    private DisputeManager disputeManager;
    private OrderAdapter adapter;
    private DisputeAdapter disputeAdapter;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private TextView tvEmpty;
    private View exchangeEmptyState;

    private final P2PExchangeManager.Listener exchangeListener = () -> uiHandler.post(this::reload);
    private final DisputeManager.Listener disputeListener = () -> uiHandler.post(this::reload);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_exchange, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        exchangeManager = new P2PExchangeManager(requireContext());
        disputeManager = new DisputeManager(requireContext());

        recyclerView = view.findViewById(R.id.recycler_exchange);
        swipeRefresh = view.findViewById(R.id.swipe_refresh_exchange);
        tvEmpty = view.findViewById(R.id.tv_exchange_empty);
        exchangeEmptyState = view.findViewById(R.id.exchange_empty_state);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new OrderAdapter();
        disputeAdapter = new DisputeAdapter();
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(() -> {
            EscrowManager.processDueDealsAsync(requireContext());
            reload();
            uiHandler.postDelayed(() -> {
                if (isAdded() && swipeRefresh != null) {
                    swipeRefresh.setRefreshing(false);
                }
            }, 600L);
        });

        P2PExchangeManager.addListener(exchangeListener);
        DisputeManager.addListener(disputeListener);
        EscrowManager.processDueDealsAsync(requireContext());
        reload();
    }

    @Override
    public void onDestroyView() {
        P2PExchangeManager.removeListener(exchangeListener);
        DisputeManager.removeListener(disputeListener);
        uiHandler.removeCallbacksAndMessages(null);
        recyclerView = null;
        swipeRefresh = null;
        tvEmpty = null;
        exchangeEmptyState = null;
        adapter = null;
        disputeAdapter = null;
        disputeManager = null;
        super.onDestroyView();
    }

    private String localAddress() {
        Context context = getContext();
        if (context == null) {
            return "";
        }
        return TransferManager.normalizeAddress(KeyManager.getOrCreateAddress(context));
    }

    private void reload() {
        if (adapter == null || exchangeManager == null) {
            return;
        }
        String local = localAddress();
        List<P2PExchangeManager.Order> all = exchangeManager.getOrders();
        List<P2PExchangeManager.Order> visible = new ArrayList<>();
        for (P2PExchangeManager.Order order : all) {
            if (order == null) {
                continue;
            }
            boolean creator = local.equals(TransferManager.normalizeAddress(order.getCreatorAddress()));
            boolean counterparty = !TextUtils.isEmpty(order.getCounterpartyAddress())
                    && local.equals(TransferManager.normalizeAddress(order.getCounterpartyAddress()));
            if (order.isOpen() || creator || counterparty) {
                visible.add(order);
            }
        }
        visible.sort((first, second) -> {
            int firstOpen = first.isOpen() ? 0 : 1;
            int secondOpen = second.isOpen() ? 0 : 1;
            if (firstOpen != secondOpen) {
                return firstOpen - secondOpen;
            }
            return Long.compare(second.getTimestamp(), first.getTimestamp());
        });
        adapter.setOrders(visible, local);
        if (tvEmpty != null) {
            tvEmpty.setText(R.string.empty_exchange_title);
        }
        showEmptyState(visible.isEmpty(), true);
        maybePromptPayment(visible, local);
    }

    private void reloadDisputes() {
        if (disputeAdapter == null || disputeManager == null) {
            return;
        }
        String local = localAddress();
        List<DisputeManager.Dispute> visible = disputeManager.getVisibleDisputes(local);
        disputeAdapter.setDisputes(visible, local);
        if (tvEmpty != null) {
            tvEmpty.setText(R.string.dispute_no_disputes);
        }
        showEmptyState(visible.isEmpty(), false);
    }

    private void showEmptyState(boolean visible, boolean showAction) {
        if (exchangeEmptyState != null) {
            exchangeEmptyState.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void maybePromptPayment(List<P2PExchangeManager.Order> orders, String local) {
        if (!isAdded()) {
            return;
        }
        for (P2PExchangeManager.Order order : orders) {
            if (!order.isBuy() || !order.isMatched() || TextUtils.isEmpty(order.getDealId())) {
                continue;
            }
            if (!local.equals(TransferManager.normalizeAddress(order.getCreatorAddress()))) {
                continue;
            }
            if (promptedDeals.contains(order.getDealId())) {
                continue;
            }
            promptedDeals.add(order.getDealId());
            showPaymentDialog(order);
            return;
        }
    }

    private void showPaymentDialog(P2PExchangeManager.Order buyOrder) {
        if (!isAdded() || exchangeManager == null) {
            return;
        }
        P2PExchangeManager.Order sellOrder =
                exchangeManager.getOrderByDealId(buyOrder.getDealId(), true);
        String fiat = TextUtils.isEmpty(buyOrder.getDealPriceText())
                ? formatFiat(buyOrder.getRemainingTRG() * buyOrder.unitPrice())
                : buyOrder.getDealPriceText();
        String amount = TextUtils.isEmpty(buyOrder.getDealAmountText())
                ? formatAmount(buyOrder.getRemainingTRG()) : buyOrder.getDealAmountText();
        String currency = currencySymbol(buyOrder.getCurrency());
        String rawMethod = sellOrder != null
                ? sellOrder.getPaymentMethod() : buyOrder.getPaymentMethod();
        String method = TextUtils.isEmpty(rawMethod)
                ? getString(R.string.exchange_order_show_details)
                : paymentMethodName(requireContext(), rawMethod);
        String details = sellOrder != null ? sellOrder.getPaymentDetails() : buyOrder.getPaymentDetails();
        if (TextUtils.isEmpty(details)) {
            details = getString(R.string.exchange_order_payment_details_missing);
        }
        String message = getString(R.string.exchange_order_payment_message,
                amount, currency, method, details, getString(R.string.exchange_i_paid));

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.exchange_order_payment_title)
                .setMessage(message)
                .setPositiveButton(R.string.exchange_i_paid, (dialog, which) -> {
                    P2PExchangeManager.Result result = exchangeManager.markOrderPaid(buyOrder.getId());
                    toast(result.message);
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCreateOrderDialog() {
        showCreateOrderDialog(false);
    }

    public void startNewOrder(boolean sell) {
        if (!isAdded()) {
            return;
        }
        showCreateOrderDialog(sell);
    }

    private void showCreateOrderDialog(boolean initialSell) {
        OrderCreateDialog.show(this, initialSell, this::reload);
    }

    private void handleOrderAction(P2PExchangeManager.Order order) {
        if (!isAdded() || exchangeManager == null || order == null) {
            return;
        }
        String local = localAddress();
        boolean creator = local.equals(TransferManager.normalizeAddress(order.getCreatorAddress()));
        boolean viewerIsSeller = order.isSell()
                ? creator
                : local.equals(TransferManager.normalizeAddress(order.getCounterpartyAddress()));

        if (creator && order.isOpen()) {
            showCancelDialog(order);
            return;
        }
        if (!creator && order.isOpen()) {
            if (order.isSell()) {
                showBuyFromSellerDialog(order);
            } else {
                showSellToBuyerDialog(order);
            }
            return;
        }
        if (order.isMatched()) {
            if (order.isBuy() && creator) {
                showPaymentDialog(order);
            } else if (viewerIsSeller) {
                toast(getString(R.string.exchange_order_waiting_payment));
            }
            return;
        }
        if (order.isPaid()) {
            if (viewerIsSeller) {
                showConfirmReceiptDialog(order);
            } else {
                toast(getString(R.string.exchange_order_paid_wait));
            }
            return;
        }
        if (order.isSettled()) {
            toast(getString(R.string.exchange_order_completed));
        } else if (order.isRefunded()) {
            toast(getString(R.string.exchange_order_refunded));
        } else if (order.isCancelled()) {
            toast(getString(R.string.exchange_status_cancelled));
        }
    }

    private void showCancelDialog(P2PExchangeManager.Order order) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.exchange_order_cancel)
                .setMessage(R.string.exchange_order_cancel_confirm)
                .setPositiveButton(R.string.exchange_order_cancel, (dialog, which) -> {
                    P2PExchangeManager.Result result = exchangeManager.cancelOrder(order.getId());
                    toast(result.message);
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showConfirmReceiptDialog(P2PExchangeManager.Order order) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.exchange_confirm_receipt)
                .setMessage(R.string.exchange_confirm_receipt)
                .setPositiveButton(R.string.exchange_confirm_receipt, (dialog, which) -> {
                    P2PExchangeManager.Result result =
                            exchangeManager.confirmOrderPayment(order.getId());
                    toast(result.message);
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showOpenDisputeDialog(P2PExchangeManager.Order order) {
        if (!isAdded() || disputeManager == null || order == null) {
            return;
        }
        String dealId = order.getDealId();
        if (TextUtils.isEmpty(dealId)) {
            toast(getString(R.string.dispute_error));
            return;
        }
        DisputeManager.Dispute existing = disputeManager.getDisputeForDeal(dealId);
        if (existing != null && existing.isOpen()) {
            toast(getString(R.string.dispute_already_open));
            return;
        }
        if (!isDebuggable() && !disputeWindowElapsed(order)) {
            toast(getString(R.string.dispute_open_wait));
            return;
        }
        String buyer = order.isBuy()
                ? order.getCreatorAddress() : order.getCounterpartyAddress();
        String seller = order.isBuy()
                ? order.getCounterpartyAddress() : order.getCreatorAddress();
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dispute_title)
                .setMessage(R.string.dispute_open_confirm)
                .setPositiveButton(R.string.dispute_open, (dialog, which) -> {
                    DisputeManager.Dispute dispute =
                            disputeManager.createDispute(dealId, buyer, seller);
                    if (dispute == null) {
                        toast(getString(R.string.dispute_error));
                        return;
                    }
                    List<String> arbiters = disputeManager.assignArbiters(
                            dealId, DisputeManager.DEFAULT_ARBITER_COUNT);
                    toast(arbiters.isEmpty()
                            ? getString(R.string.dispute_error)
                            : getString(R.string.dispute_open_success));
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean disputeWindowElapsed(P2PExchangeManager.Order order) {
        return System.currentTimeMillis() - order.getPaidAtOrTimestamp()
                >= DisputeManager.DISPUTE_WINDOW_MS;
    }

    private boolean isDebuggable() {
        Context context = getContext();
        return context != null
                && (context.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void castDisputeVote(DisputeManager.Dispute dispute, boolean releaseToBuyer) {
        if (!isAdded() || disputeManager == null || dispute == null) {
            return;
        }
        String local = localAddress();
        boolean voted = disputeManager.vote(dispute.getDealId(), local, releaseToBuyer);
        toast(getString(voted ? R.string.dispute_vote_recorded
                : R.string.dispute_not_arbiter));
        if (!voted) {
            return;
        }
        DisputeManager.Dispute fresh =
                disputeManager.getDisputeForDeal(dispute.getDealId());
        if (fresh != null && fresh.allVotesCast()
                && disputeManager.resolveDispute(dispute.getDealId())) {
            DisputeManager.Dispute resolved =
                    disputeManager.getDisputeForDeal(dispute.getDealId());
            if (resolved != null) {
                toast(DisputeManager.resultMessage(requireContext(),
                        resolved.isReleaseToBuyer()));
            }
        }
        reload();
    }

    private void showBuyFromSellerDialog(P2PExchangeManager.Order sellOrder) {
        double amount = sellOrder.getRemainingTRG();
        double total = multiply(amount, sellOrder.unitPrice());
        String message = getString(R.string.exchange_order_match_confirm_message,
                formatAmount(amount), formatFiat(total),
                currencySymbol(sellOrder.getCurrency()));
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.exchange_order_match_confirm_title)
                .setMessage(message)
                .setPositiveButton(R.string.exchange_order_match, (dialog, which) -> {
                    P2PExchangeManager.Order buyOrder = new P2PExchangeManager.Order(
                            "", localAddress(), localNickname(),
                            P2PExchangeManager.Order.TYPE_BUY,
                            amount, amount, sellOrder.unitPrice(), sellOrder.getCurrency(),
                            sellOrder.getPaymentMethod(), "", "", 0L,
                            P2PExchangeManager.Order.STATUS_OPEN);
                    P2PExchangeManager.Result result = exchangeManager.placeOrder(buyOrder);
                    toast(result.message);
                    if (result.success && result.order != null
                            && !TextUtils.isEmpty(result.order.getDealId())) {
                        showPaymentDialog(result.order);
                    }
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSellToBuyerDialog(P2PExchangeManager.Order buyOrder) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_create_order, null, false);
        Spinner typeSpinner = content.findViewById(R.id.spinner_order_type);
        Spinner currencySpinner = content.findViewById(R.id.spinner_order_currency);
        Spinner methodSpinner = content.findViewById(R.id.spinner_order_method);
        EditText amountInput = content.findViewById(R.id.et_order_amount);
        EditText priceInput = content.findViewById(R.id.et_order_price);
        EditText detailsInput = content.findViewById(R.id.et_order_details);
        typeSpinner.setVisibility(View.GONE);
        currencySpinner.setVisibility(View.GONE);
        priceInput.setVisibility(View.GONE);
        amountInput.setText(formatAmount(buyOrder.getRemainingTRG()));
        amountInput.setEnabled(false);

        bindMethodSpinner(methodSpinner, detailsInput);

        String message = getString(R.string.exchange_order_sell_confirm_message,
                formatAmount(buyOrder.getRemainingTRG()),
                formatFiat(multiply(buyOrder.getRemainingTRG(), buyOrder.unitPrice())),
                currencySymbol(buyOrder.getCurrency()));
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.exchange_sell)
                .setMessage(message)
                .setView(content)
                .setPositiveButton(R.string.exchange_order_match, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String details = detailsInput.getText().toString().trim();
                    if (TextUtils.isEmpty(details)) {
                        toast(getString(R.string.exchange_order_payment_required));
                        return;
                    }
                    double amount = buyOrder.getRemainingTRG();
                    String method = methodCodeAt(methodSpinner,
                            methodSpinner.getSelectedItemPosition());
                    P2PExchangeManager.Order sellOrder = new P2PExchangeManager.Order(
                            "", localAddress(), localNickname(),
                            P2PExchangeManager.Order.TYPE_SELL,
                            amount, amount, buyOrder.unitPrice(), buyOrder.getCurrency(),
                            method, details, "", 0L,
                            P2PExchangeManager.Order.STATUS_OPEN);
                    P2PExchangeManager.Result result = exchangeManager.placeOrder(sellOrder);
                    toast(result.message);
                    if (result.success) {
                        dialog.dismiss();
                    }
                    reload();
                }));
        dialog.show();
    }

    private void showOrderDetails(P2PExchangeManager.Order order) {
        if (!isAdded()) {
            return;
        }
        double shownAmount = order.getRemainingTRG() > 0.00000001
                ? order.getRemainingTRG() : order.getAmountTRG();
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.exchange_order_creator_format,
                displayName(order.getCreatorNickname(), order.getCreatorAddress())));
        builder.append('\n').append(getString(R.string.exchange_order_amount_format,
                formatAmount(shownAmount)));
        builder.append('\n').append(getString(R.string.exchange_order_unit_price_format,
                formatFiat(order.unitPrice()), currencySymbol(order.getCurrency())));
        builder.append('\n').append(getString(R.string.exchange_order_total_format,
                formatFiat(multiply(shownAmount, order.unitPrice())),
                currencySymbol(order.getCurrency())));
        if (order.isSell()) {
            builder.append("\n\n").append(getString(R.string.exchange_order_details_title));
            builder.append("\n").append(TextUtils.isEmpty(order.getPaymentMethod())
                    ? "-" : paymentMethodName(requireContext(), order.getPaymentMethod()));
            builder.append("\n").append(TextUtils.isEmpty(order.getPaymentDetails())
                    ? getString(R.string.exchange_order_payment_details_missing)
                    : order.getPaymentDetails());
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.exchange_order_details_title)
                .setMessage(builder.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String localNickname() {
        String nickname = IrohNode.getInstance().getLocalUsername();
        if (TextUtils.isEmpty(nickname) || nickname.startsWith("peer_")) {
            return shortAddress(localAddress());
        }
        return nickname;
    }

    private String displayName(String nickname, String address) {
        if (!TextUtils.isEmpty(nickname)) {
            return nickname;
        }
        return shortAddress(address);
    }

    private String shortAddress(String address) {
        String clean = TransferManager.normalizeAddress(address);
        if (clean.length() <= 12) {
            return clean;
        }
        return clean.substring(0, 6) + "…" + clean.substring(clean.length() - 4);
    }

    private String currencySymbol(String currency) {
        return P2PExchangeManager.currencySymbol(currency);
    }

    private String currencyLabel(String currency) {
        return getString(currencyLabelRes(currency));
    }

    private static int currencyLabelRes(String currency) {
        String value = currency == null ? "" : currency.trim().toUpperCase(Locale.US);
        switch (value) {
            case ExchangeOffer.CURRENCY_USD:
                return R.string.currency_usd;
            case ExchangeOffer.CURRENCY_EUR:
                return R.string.currency_eur;
            case ExchangeOffer.CURRENCY_KZT:
                return R.string.currency_kzt;
            case ExchangeOffer.CURRENCY_UAH:
                return R.string.currency_uah;
            case ExchangeOffer.CURRENCY_BYN:
                return R.string.currency_byn;
            case ExchangeOffer.CURRENCY_TRY:
                return R.string.currency_try;
            case ExchangeOffer.CURRENCY_CNY:
                return R.string.currency_cny;
            case ExchangeOffer.CURRENCY_INR:
                return R.string.currency_inr;
            default:
                return R.string.currency_rub;
        }
    }

    private static String paymentMethodName(Context context, String method) {
        String value = method == null ? "" : method.trim();
        switch (value.toUpperCase(Locale.US)) {
            case P2PExchangeManager.Order.PAYMENT_CARD:
                return context.getString(R.string.payment_card);
            case P2PExchangeManager.Order.PAYMENT_SBP:
                return context.getString(R.string.payment_sbp);
            case P2PExchangeManager.Order.PAYMENT_PHONE:
                return context.getString(R.string.payment_phone);
            case P2PExchangeManager.Order.PAYMENT_PAYPAL:
                return context.getString(R.string.payment_paypal);
            case P2PExchangeManager.Order.PAYMENT_WISE:
                return context.getString(R.string.payment_wise);
            case P2PExchangeManager.Order.PAYMENT_OTHER:
                return context.getString(R.string.payment_other);
            default:
                return value;
        }
    }

    private static String currencyCodeAt(int position) {
        List<String> codes = P2PExchangeManager.SUPPORTED_CURRENCIES;
        return position >= 0 && position < codes.size() ? codes.get(position) : codes.get(0);
    }

    private static String methodCodeAt(Spinner spinner, int position) {
        List<String> codes = P2PExchangeManager.Order.SUPPORTED_PAYMENT_METHODS;
        return position >= 0 && position < codes.size()
                ? codes.get(position) : P2PExchangeManager.Order.PAYMENT_OTHER;
    }

    private void updatePriceHint(EditText priceInput, String currency) {
        priceInput.setHint(getString(R.string.exchange_order_price_hint)
                + " (" + currencySymbol(currency) + ")");
    }

    private void updateDetailsHint(EditText detailsInput, String method) {
        String value = method == null ? "" : method.trim().toUpperCase(Locale.US);
        int hintRes;
        int inputType;
        switch (value) {
            case P2PExchangeManager.Order.PAYMENT_CARD:
                hintRes = R.string.order_payment_details_card;
                inputType = EditorInfo.TYPE_CLASS_TEXT;
                break;
            case P2PExchangeManager.Order.PAYMENT_SBP:
            case P2PExchangeManager.Order.PAYMENT_PHONE:
                hintRes = R.string.order_payment_details_phone;
                inputType = EditorInfo.TYPE_CLASS_PHONE;
                break;
            case P2PExchangeManager.Order.PAYMENT_PAYPAL:
            case P2PExchangeManager.Order.PAYMENT_WISE:
                hintRes = R.string.order_payment_details_email;
                inputType = EditorInfo.TYPE_CLASS_TEXT
                        | EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
                break;
            default:
                hintRes = R.string.exchange_order_details_hint;
                inputType = EditorInfo.TYPE_CLASS_TEXT;
                break;
        }
        detailsInput.setHint(hintRes);
        detailsInput.setInputType(inputType);
    }

    private void bindCurrencySpinner(Spinner currencySpinner, EditText priceInput) {
        List<String> currencies = new ArrayList<>();
        for (String code : P2PExchangeManager.SUPPORTED_CURRENCIES) {
            currencies.add(currencyLabel(code));
        }
        currencySpinner.setAdapter(spinnerAdapter(currencies));
        currencySpinner.setSelection(0);
        currencySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updatePriceHint(priceInput, currencyCodeAt(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updatePriceHint(priceInput, currencyCodeAt(0));
    }

    private void bindMethodSpinner(Spinner methodSpinner, EditText detailsInput) {
        List<String> methods = new ArrayList<>();
        for (String code : P2PExchangeManager.Order.SUPPORTED_PAYMENT_METHODS) {
            methods.add(paymentMethodName(requireContext(), code));
        }
        methodSpinner.setAdapter(spinnerAdapter(methods));
        methodSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateDetailsHint(detailsInput, methodCodeAt(methodSpinner, position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateDetailsHint(detailsInput, P2PExchangeManager.Order.PAYMENT_CARD);
    }

    private static String formatAmount(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String formatFiat(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static double multiply(double first, double second) {
        return BigDecimal.valueOf(first).multiply(BigDecimal.valueOf(second))
                .setScale(8, RoundingMode.HALF_UP).doubleValue();
    }

    private static double parseAmount(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return 0.0;
        }
        try {
            return new BigDecimal(raw.trim().replace(',', '.')).doubleValue();
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private ArrayAdapter<String> spinnerAdapter(List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void toast(String message) {
        if (isAdded() && !TextUtils.isEmpty(message)) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    private class OrderAdapter extends RecyclerView.Adapter<OrderHolder> {

        private final List<P2PExchangeManager.Order> orders = new ArrayList<>();
        private String local = "";

        void setOrders(List<P2PExchangeManager.Order> values, String localAddress) {
            orders.clear();
            if (values != null) {
                orders.addAll(values);
            }
            local = localAddress;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public OrderHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_order, parent, false);
            return new OrderHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull OrderHolder holder, int position) {
            holder.bind(orders.get(position), local);
        }

        @Override
        public int getItemCount() {
            return orders.size();
        }
    }

    private class OrderHolder extends RecyclerView.ViewHolder {

        private final TextView type;
        private final TextView price;
        private final TextView status;
        private final TextView amount;
        private final TextView total;
        private final TextView creator;
        private final TextView marketMaker;
        private final TextView deal;
        private final Button dispute;
        private final Button details;
        private final Button action;

        OrderHolder(@NonNull View itemView) {
            super(itemView);
            type = itemView.findViewById(R.id.tv_order_type);
            price = itemView.findViewById(R.id.tv_order_price);
            status = itemView.findViewById(R.id.tv_order_status);
            amount = itemView.findViewById(R.id.tv_order_amount);
            total = itemView.findViewById(R.id.tv_order_total);
            creator = itemView.findViewById(R.id.tv_order_creator);
            marketMaker = itemView.findViewById(R.id.tv_order_market_maker);
            deal = itemView.findViewById(R.id.tv_order_deal);
            dispute = itemView.findViewById(R.id.btn_order_dispute);
            details = itemView.findViewById(R.id.btn_order_details);
            action = itemView.findViewById(R.id.btn_order_action);
        }

        void bind(P2PExchangeManager.Order order, String local) {
            Context context = itemView.getContext();
            boolean isBuy = order.isBuy();
            type.setText(isBuy ? R.string.exchange_buy : R.string.exchange_sell);
            type.setTextColor(context.getColor(isBuy
                    ? R.color.success_green : R.color.accent_blue));
            double shownAmount = order.getRemainingTRG() > 0.00000001
                    ? order.getRemainingTRG() : order.getAmountTRG();
            price.setText(context.getString(R.string.exchange_order_unit_price_format,
                    formatFiat(order.unitPrice()), currencySymbol(order.getCurrency())));
            String totalFiat = formatFiat(multiply(shownAmount, order.unitPrice()));
            String symbol = currencySymbol(order.getCurrency());
            String methodLabel = paymentMethodName(context, order.getPaymentMethod());
            amount.setText(TextUtils.isEmpty(methodLabel)
                    ? context.getString(isBuy ? R.string.exchange_buy_format
                    : R.string.exchange_sell_format,
                    formatAmount(shownAmount), totalFiat, symbol)
                    : context.getString(isBuy ? R.string.exchange_order_summary_buy
                    : R.string.exchange_order_summary_sell,
                    formatAmount(shownAmount), totalFiat, symbol, methodLabel));
            total.setText(context.getString(R.string.exchange_order_total_format,
                    totalFiat, symbol));
            creator.setText(context.getString(R.string.exchange_order_creator_format,
                    displayName(order.getCreatorNickname(), order.getCreatorAddress())));
            marketMaker.setVisibility(order.isMarketMaker() ? View.VISIBLE : View.GONE);

            boolean creatorMatches = local.equals(
                    TransferManager.normalizeAddress(order.getCreatorAddress()));
            boolean counterpartyMatches = !TextUtils.isEmpty(order.getCounterpartyAddress())
                    && local.equals(TransferManager.normalizeAddress(order.getCounterpartyAddress()));
            boolean viewerIsSeller = order.isSell() ? creatorMatches : counterpartyMatches;
            boolean viewerIsBuyer = order.isBuy() ? creatorMatches : counterpartyMatches;

            status.setText(statusText(context, order, viewerIsSeller));
            status.setTextColor(context.getColor(statusColor(order)));

            if (TextUtils.isEmpty(order.getDealId())) {
                deal.setVisibility(View.GONE);
            } else {
                deal.setVisibility(View.VISIBLE);
                deal.setText(context.getString(R.string.exchange_order_deal_format,
                        TextUtils.isEmpty(order.getDealAmountText())
                                ? formatAmount(order.getRemainingTRG())
                                : order.getDealAmountText(),
                        TextUtils.isEmpty(order.getDealPriceText())
                                ? formatFiat(multiply(order.getRemainingTRG(), order.unitPrice()))
                                : order.getDealPriceText(),
                        currencySymbol(order.getCurrency())));
            }

            details.setVisibility(order.isSell() ? View.VISIBLE : View.GONE);
            details.setOnClickListener(v -> showOrderDetails(order));

            bindDispute(dispute, order, viewerIsBuyer);
            bindAction(action, order, creatorMatches, viewerIsSeller);
        }

        private void bindDispute(Button button, P2PExchangeManager.Order order,
                                 boolean viewerIsBuyer) {
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
            if (!viewerIsBuyer || !order.isPaid()
                    || TextUtils.isEmpty(order.getDealId())) {
                return;
            }
            DisputeManager manager = disputeManager;
            if (manager == null) {
                return;
            }
            DisputeManager.Dispute existing =
                    manager.getDisputeForDeal(order.getDealId());
            if (existing != null && existing.isOpen()) {
                return;
            }
            if (!isDebuggable() && !disputeWindowElapsed(order)) {
                return;
            }
            button.setVisibility(View.VISIBLE);
            button.setOnClickListener(v -> showOpenDisputeDialog(order));
        }

        private void bindAction(Button button, P2PExchangeManager.Order order,
                                boolean creator, boolean viewerIsSeller) {
            button.setVisibility(View.VISIBLE);
            button.setEnabled(true);
            if (order.isOpen()) {
                if (creator) {
                    button.setText(R.string.exchange_order_cancel);
                    button.setOnClickListener(v -> handleOrderAction(order));
                } else if (order.isSell()) {
                    button.setText(R.string.exchange_order_execute);
                    button.setOnClickListener(v -> handleOrderAction(order));
                } else {
                    button.setText(R.string.exchange_order_execute);
                    button.setOnClickListener(v -> handleOrderAction(order));
                }
            } else if (order.isMatched() && order.isBuy() && creator) {
                button.setText(R.string.exchange_i_paid);
                button.setOnClickListener(v -> handleOrderAction(order));
            } else if (order.isPaid() && viewerIsSeller) {
                button.setText(R.string.exchange_confirm_receipt);
                button.setOnClickListener(v -> handleOrderAction(order));
            } else if (order.isMatched()) {
                button.setText(R.string.exchange_order_waiting_payment);
                button.setEnabled(false);
                button.setOnClickListener(null);
            } else if (order.isPaid()) {
                button.setText(R.string.exchange_order_paid_wait);
                button.setEnabled(false);
                button.setOnClickListener(null);
            } else {
                button.setVisibility(View.GONE);
                button.setOnClickListener(null);
            }
        }

        private String statusText(Context context, P2PExchangeManager.Order order,
                                  boolean viewerIsSeller) {
            if (order.isOpen()) {
                return context.getString(R.string.exchange_order_open);
            }
            if (order.isMatched()) {
                return context.getString(R.string.exchange_order_waiting_payment);
            }
            if (order.isPaid()) {
                return context.getString(viewerIsSeller
                        ? R.string.exchange_order_waiting_confirm
                        : R.string.exchange_order_paid_wait);
            }
            if (order.isSettled() || P2PExchangeManager.Order.STATUS_FILLED.equals(order.getStatus())) {
                return context.getString(R.string.exchange_order_completed);
            }
            if (order.isRefunded()) {
                return context.getString(R.string.exchange_order_refunded);
            }
            if (order.isCancelled()) {
                return context.getString(R.string.exchange_status_cancelled);
            }
            return order.getStatus();
        }

        private int statusColor(P2PExchangeManager.Order order) {
            if (order.isOpen()) {
                return R.color.success_green;
            }
            if (order.isMatched() || order.isPaid()) {
                return R.color.accent_orange;
            }
            if (order.isSettled()) {
                return R.color.accent_blue;
            }
            if (order.isCancelled() || order.isRefunded()) {
                return R.color.danger_red;
            }
            return R.color.accent_blue;
        }
    }

    private class DisputeAdapter extends RecyclerView.Adapter<DisputeHolder> {

        private final List<DisputeManager.Dispute> disputes = new ArrayList<>();
        private String local = "";

        void setDisputes(List<DisputeManager.Dispute> values, String localAddress) {
            disputes.clear();
            if (values != null) {
                disputes.addAll(values);
            }
            local = localAddress;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public DisputeHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_dispute, parent, false);
            return new DisputeHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull DisputeHolder holder, int position) {
            holder.bind(disputes.get(position), local);
        }

        @Override
        public int getItemCount() {
            return disputes.size();
        }
    }

    private class DisputeHolder extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TextView status;
        private final TextView deal;
        private final TextView parties;
        private final TextView arbiters;
        private final TextView votes;
        private final TextView myVote;
        private final Button voteBuyer;
        private final Button voteSeller;

        DisputeHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tv_dispute_title);
            status = itemView.findViewById(R.id.tv_dispute_status);
            deal = itemView.findViewById(R.id.tv_dispute_deal);
            parties = itemView.findViewById(R.id.tv_dispute_parties);
            arbiters = itemView.findViewById(R.id.tv_dispute_arbiters);
            votes = itemView.findViewById(R.id.tv_dispute_votes);
            myVote = itemView.findViewById(R.id.tv_dispute_vote);
            voteBuyer = itemView.findViewById(R.id.btn_dispute_buyer);
            voteSeller = itemView.findViewById(R.id.btn_dispute_seller);
        }

        void bind(DisputeManager.Dispute dispute, String local) {
            Context context = itemView.getContext();
            title.setText(context.getString(R.string.dispute_title)
                    + " #" + dispute.getId());
            status.setText(context.getString(dispute.isOpen()
                    ? R.string.dispute_status_open : R.string.dispute_status_resolved));
            status.setTextColor(context.getColor(dispute.isOpen()
                    ? R.color.accent_orange : R.color.success_green));

            String amount = TextUtils.isEmpty(dispute.getAmountText())
                    ? "" : dispute.getAmountText() + " TRG";
            String dealText = TextUtils.isEmpty(dispute.getDealTitle())
                    ? amount : dispute.getDealTitle();
            if (!TextUtils.isEmpty(dispute.getDealTitle())
                    && !TextUtils.isEmpty(amount)) {
                dealText = dispute.getDealTitle() + " · " + amount;
            }
            deal.setText(context.getString(R.string.dispute_deal_format,
                    TextUtils.isEmpty(dealText) ? dispute.getDealId() : dealText));
            parties.setText(context.getString(R.string.exchange_buyer_format,
                    shortAddress(dispute.getBuyerAddress()))
                    + " · " + context.getString(R.string.exchange_seller_format,
                    shortAddress(dispute.getSellerAddress())));
            StringBuilder arbiterNames = new StringBuilder();
            for (String arbiter : dispute.getArbiters()) {
                if (arbiterNames.length() > 0) {
                    arbiterNames.append(", ");
                }
                arbiterNames.append(IrohNode.getInstance().getPeerDisplayName(arbiter));
            }
            arbiters.setText(arbiterNames.length() == 0
                    ? context.getString(R.string.dispute_arbiters_format,
                    dispute.getArbiterCount())
                    : context.getString(R.string.dispute_arbiters_names_format,
                    arbiterNames.toString()));
            votes.setText(context.getString(R.string.dispute_votes_format,
                    dispute.getVoteCount(), dispute.getArbiterCount()));

            boolean isArbiter = dispute.isArbiter(local);
            Boolean myChoice = dispute.getVote(local);
            if (myChoice != null) {
                myVote.setVisibility(View.VISIBLE);
                myVote.setText(context.getString(myChoice
                        ? R.string.dispute_my_vote_buyer
                        : R.string.dispute_my_vote_seller));
            } else if (dispute.isOpen() && isArbiter) {
                myVote.setVisibility(View.GONE);
            } else if (dispute.isResolved()) {
                myVote.setVisibility(View.VISIBLE);
                myVote.setText(context.getString(dispute.isReleaseToBuyer()
                        ? R.string.dispute_resolved_buyer
                        : R.string.dispute_resolved_seller));
            } else {
                myVote.setVisibility(View.VISIBLE);
                myVote.setText(R.string.dispute_waiting_votes);
            }

            boolean canVote = dispute.isOpen() && isArbiter && myChoice == null;
            voteBuyer.setVisibility(canVote ? View.VISIBLE : View.GONE);
            voteSeller.setVisibility(canVote ? View.VISIBLE : View.GONE);
            voteBuyer.setOnClickListener(canVote
                    ? v -> castDisputeVote(dispute, true) : null);
            voteSeller.setOnClickListener(canVote
                    ? v -> castDisputeVote(dispute, false) : null);
        }
    }
}
