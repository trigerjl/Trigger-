package com.triger.poc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MarketFragment extends Fragment {

    private P2PExchangeManager exchangeManager;
    private ReviewManager reviewManager;
    private PriceOracle priceOracle;
    private ListingAdapter adapter;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private TextView tvRate;
    private TextView tvEmpty;
    private View emptyState;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final P2PExchangeManager.Listener exchangeListener =
            () -> uiHandler.post(this::reload);
    private final PriceOracle.Listener priceListener =
            () -> uiHandler.post(this::reloadRate);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_market, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        exchangeManager = new P2PExchangeManager(requireContext());
        reviewManager = new ReviewManager(requireContext());
        priceOracle = new PriceOracle(requireContext());

        recyclerView = view.findViewById(R.id.recycler_market);
        swipeRefresh = view.findViewById(R.id.swipe_refresh_market);
        tvRate = view.findViewById(R.id.tv_market_rate);
        tvEmpty = view.findViewById(R.id.tv_market_empty);
        emptyState = view.findViewById(R.id.market_empty_state);

        adapter = new ListingAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        View btnAdd = view.findViewById(R.id.btn_market_add);
        if (btnAdd != null) {
            btnAdd.setOnClickListener(v ->
                    OrderCreateDialog.show(this, true, this::reload));
        }
        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(() -> {
                reload();
                swipeRefresh.setRefreshing(false);
            });
        }

        P2PExchangeManager.addListener(exchangeListener);
        PriceOracle.addListener(priceListener);
        reload();
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onDestroyView() {
        P2PExchangeManager.removeListener(exchangeListener);
        PriceOracle.removeListener(priceListener);
        uiHandler.removeCallbacksAndMessages(null);
        recyclerView = null;
        swipeRefresh = null;
        tvRate = null;
        tvEmpty = null;
        emptyState = null;
        adapter = null;
        exchangeManager = null;
        reviewManager = null;
        priceOracle = null;
        super.onDestroyView();
    }

    private void reload() {
        if (!isAdded()) {
            return;
        }
        reloadRate();
        if (exchangeManager == null || adapter == null) {
            return;
        }
        String local = localAddress();
        List<P2PExchangeManager.Order> visible = new ArrayList<>();
        for (P2PExchangeManager.Order order : exchangeManager.getOpenOrders()) {
            if (order != null) {
                visible.add(order);
            }
        }
        visible.sort((first, second) -> {
            if (first.isSell() != second.isSell()) {
                return first.isSell() ? -1 : 1;
            }
            int price = Double.compare(first.unitPrice(), second.unitPrice());
            if (price != 0) {
                return price;
            }
            return Long.compare(second.getTimestamp(), first.getTimestamp());
        });
        adapter.submit(visible, local);
        if (emptyState != null) {
            emptyState.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void reloadRate() {
        if (tvRate == null || priceOracle == null) {
            return;
        }
        Double price = priceOracle.getPrice();
        if (price == null || price <= 0.0 || Double.isNaN(price) || Double.isInfinite(price)) {
            tvRate.setText(R.string.market_rate_forming);
            return;
        }
        String value = getString(R.string.market_price_value_format,
                formatPrice(price),
                P2PExchangeManager.currencySymbol(ExchangeOffer.CURRENCY_RUB));
        tvRate.setText(getString(R.string.market_rate, value));
    }

    private String localAddress() {
        return TransferManager.normalizeAddress(
                KeyManager.getOrCreateAddress(requireContext()));
    }

    private String localNickname() {
        String nickname = IrohNode.getInstance().getLocalUsername();
        if (TextUtils.isEmpty(nickname) || nickname.startsWith("peer_")) {
            return shortAddress(localAddress());
        }
        return nickname;
    }

    private void handleCancel(P2PExchangeManager.Order order) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.exchange_order_cancel)
                .setMessage(R.string.exchange_order_cancel_confirm)
                .setPositiveButton(R.string.exchange_order_cancel, (dialog, which) -> {
                    P2PExchangeManager.Result result =
                            exchangeManager.cancelOrder(order.getId());
                    toast(result.message);
                    reload();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void handleBuy(P2PExchangeManager.Order sellOrder) {
        double amount = shownAmount(sellOrder);
        double unitPrice = sellOrder.unitPrice();
        double total = multiply(amount, unitPrice);
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
                            amount, amount, total, sellOrder.getCurrency(),
                            sellOrder.getPaymentMethod(), "", "", 0L,
                            P2PExchangeManager.Order.STATUS_OPEN);
                    P2PExchangeManager.Result result =
                            exchangeManager.placeOrder(buyOrder);
                    toast(result.message);
                    if (result.success) {
                        exchangeManager.matchOrders();
                        reload();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void handleSell(P2PExchangeManager.Order buyOrder) {
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
        amountInput.setText(formatAmount(shownAmount(buyOrder)));
        amountInput.setEnabled(false);
        bindMethodSpinner(methodSpinner, detailsInput);

        double amount = shownAmount(buyOrder);
        double unitPrice = buyOrder.unitPrice();
        double total = multiply(amount, unitPrice);
        String message = getString(R.string.exchange_order_sell_confirm_message,
                formatAmount(amount), formatFiat(total),
                currencySymbol(buyOrder.getCurrency()));
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.exchange_sell)
                .setMessage(message)
                .setView(content)
                .setPositiveButton(R.string.exchange_order_match, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String details = detailsInput.getText().toString().trim();
                    if (TextUtils.isEmpty(details)) {
                        toast(getString(R.string.exchange_order_payment_required));
                        return;
                    }
                    String method = methodCodeAt(methodSpinner.getSelectedItemPosition());
                    P2PExchangeManager.Order sellOrder = new P2PExchangeManager.Order(
                            "", localAddress(), localNickname(),
                            P2PExchangeManager.Order.TYPE_SELL,
                            amount, amount, total, buyOrder.getCurrency(),
                            method, details, "", 0L,
                            P2PExchangeManager.Order.STATUS_OPEN);
                    P2PExchangeManager.Result result =
                            exchangeManager.placeOrder(sellOrder);
                    toast(result.message);
                    if (result.success) {
                        exchangeManager.matchOrders();
                        dialog.dismiss();
                        reload();
                    }
                }));
        dialog.show();
    }

    private void showOrderDetails(P2PExchangeManager.Order order) {
        if (!isAdded()) {
            return;
        }
        String method = TextUtils.isEmpty(order.getPaymentMethod())
                ? "-" : paymentMethodName(requireContext(), order.getPaymentMethod());
        String details = TextUtils.isEmpty(order.getPaymentDetails())
                ? getString(R.string.exchange_order_payment_details_missing)
                : order.getPaymentDetails();
        String message = method + "\n" + details;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.exchange_order_details_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    private void bindMethodSpinner(Spinner methodSpinner, EditText detailsInput) {
        List<String> methods = new ArrayList<>();
        for (String code : P2PExchangeManager.Order.SUPPORTED_PAYMENT_METHODS) {
            methods.add(paymentMethodName(requireContext(), code));
        }
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, methods);
        spinnerAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        methodSpinner.setAdapter(spinnerAdapter);
        methodSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateDetailsHint(detailsInput, methodCodeAt(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateDetailsHint(detailsInput, P2PExchangeManager.Order.PAYMENT_CARD);
    }

    private void updateDetailsHint(EditText detailsInput, String method) {
        String value = method == null ? "" : method.trim().toUpperCase(Locale.US);
        int hintRes;
        switch (value) {
            case P2PExchangeManager.Order.PAYMENT_CARD:
                hintRes = R.string.order_payment_details_card;
                break;
            case P2PExchangeManager.Order.PAYMENT_SBP:
            case P2PExchangeManager.Order.PAYMENT_PHONE:
                hintRes = R.string.order_payment_details_phone;
                break;
            case P2PExchangeManager.Order.PAYMENT_PAYPAL:
            case P2PExchangeManager.Order.PAYMENT_WISE:
                hintRes = R.string.order_payment_details_email;
                break;
            default:
                hintRes = R.string.exchange_order_details_hint;
                break;
        }
        detailsInput.setHint(hintRes);
    }

    private String ratingText(String sellerAddress) {
        if (reviewManager == null) {
            return "";
        }
        int count = reviewManager.getReviewCount(sellerAddress);
        if (count <= 0) {
            return getString(R.string.review_no_reviews);
        }
        return getString(R.string.market_seller_rating,
                reviewManager.getAverageRating(sellerAddress), count);
    }

    private Bitmap avatarBitmap(Context context, String address, String name) {
        String normalized = TransferManager.normalizeAddress(address);
        if (AvatarStore.hasAvatar(context) && normalized.equals(localAddress())) {
            Bitmap custom = AvatarStore.load(context);
            if (custom != null) {
                return custom;
            }
        }
        String seed = TextUtils.isEmpty(name) ? normalized : name;
        int size = 96;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(colorFromSeed(seed));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, background);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(size * 0.36f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(initials(seed), size / 2f,
                size / 2f + text.getTextSize() / 3f, text);
        return bitmap;
    }

    private static int colorFromSeed(String seed) {
        int hash = seed == null ? 0 : seed.hashCode();
        int r = ((hash) & 0xFF) % 160 + 70;
        int g = ((hash >> 8) & 0xFF) % 160 + 70;
        int b = ((hash >> 16) & 0xFF) % 160 + 70;
        return Color.rgb(r, g, b);
    }

    private static String initials(String seed) {
        String value = seed == null ? "" : seed.replaceAll("[^A-Za-zА-Яа-я0-9]", " ").trim();
        if (value.isEmpty()) {
            return "TR";
        }
        String[] parts = value.split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1))
                    .toUpperCase(Locale.ROOT);
        }
        if (parts[0].length() >= 2) {
            return parts[0].substring(0, 2).toUpperCase(Locale.ROOT);
        }
        return (parts[0].substring(0, 1) + "X").toUpperCase(Locale.ROOT);
    }

    private static String displayName(String nickname, String address) {
        if (!TextUtils.isEmpty(nickname)) {
            return nickname;
        }
        return shortAddress(address);
    }

    private static String shortAddress(String address) {
        String clean = TransferManager.normalizeAddress(address);
        if (clean.length() <= 12) {
            return clean;
        }
        return clean.substring(0, 6) + "…" + clean.substring(clean.length() - 4);
    }

    private static double shownAmount(P2PExchangeManager.Order order) {
        return order.getRemainingTRG() > 0.00000001
                ? order.getRemainingTRG() : order.getAmountTRG();
    }

    private static String currencySymbol(String currency) {
        return P2PExchangeManager.currencySymbol(currency);
    }

    private static String methodCodeAt(int position) {
        List<String> codes = P2PExchangeManager.Order.SUPPORTED_PAYMENT_METHODS;
        return position >= 0 && position < codes.size()
                ? codes.get(position) : P2PExchangeManager.Order.PAYMENT_OTHER;
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

    private static String formatPrice(double value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setGroupingUsed(true);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return format.format(value);
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

    private void toast(String message) {
        if (isAdded() && !TextUtils.isEmpty(message)) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    private class ListingAdapter extends RecyclerView.Adapter<ListingHolder> {

        private final List<P2PExchangeManager.Order> orders = new ArrayList<>();
        private String local = "";

        void submit(List<P2PExchangeManager.Order> values, String localAddress) {
            orders.clear();
            if (values != null) {
                orders.addAll(values);
            }
            local = localAddress == null ? "" : localAddress;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ListingHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_order, parent, false);
            return new ListingHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ListingHolder holder, int position) {
            holder.bind(orders.get(position), local);
        }

        @Override
        public int getItemCount() {
            return orders.size();
        }
    }

    private class ListingHolder extends RecyclerView.ViewHolder {

        private final ImageView avatar;
        private final TextView creator;
        private final TextView rating;
        private final TextView payment;
        private final TextView type;
        private final TextView price;
        private final TextView status;
        private final TextView amount;
        private final TextView total;
        private final TextView marketMaker;
        private final TextView deal;
        private final MaterialButton dispute;
        private final MaterialButton details;
        private final MaterialButton action;

        ListingHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.iv_order_avatar);
            creator = itemView.findViewById(R.id.tv_order_creator);
            rating = itemView.findViewById(R.id.tv_order_rating);
            payment = itemView.findViewById(R.id.tv_order_payment);
            type = itemView.findViewById(R.id.tv_order_type);
            price = itemView.findViewById(R.id.tv_order_price);
            status = itemView.findViewById(R.id.tv_order_status);
            amount = itemView.findViewById(R.id.tv_order_amount);
            total = itemView.findViewById(R.id.tv_order_total);
            marketMaker = itemView.findViewById(R.id.tv_order_market_maker);
            deal = itemView.findViewById(R.id.tv_order_deal);
            dispute = itemView.findViewById(R.id.btn_order_dispute);
            details = itemView.findViewById(R.id.btn_order_details);
            action = itemView.findViewById(R.id.btn_order_action);
        }

        void bind(P2PExchangeManager.Order order, String localAddress) {
            Context context = itemView.getContext();
            boolean sell = order.isSell();
            boolean own = localAddress.equals(
                    TransferManager.normalizeAddress(order.getCreatorAddress()));
            String name = displayName(order.getCreatorNickname(), order.getCreatorAddress());

            avatar.setImageBitmap(avatarBitmap(context, order.getCreatorAddress(), name));
            creator.setText(name);
            marketMaker.setVisibility(order.isMarketMaker() ? View.VISIBLE : View.GONE);
            rating.setText(ratingText(order.getCreatorAddress()));
            String method = paymentMethodName(context, order.getPaymentMethod());
            payment.setText(TextUtils.isEmpty(method) ? "—" : method);

            type.setText(context.getString(sell
                    ? R.string.exchange_sell : R.string.exchange_buy));
            type.setTextColor(context.getColor(sell
                    ? R.color.accent_blue : R.color.success_green));

            double shown = shownAmount(order);
            double unit = order.unitPrice();
            double fiatTotal = multiply(shown, unit);
            String symbol = currencySymbol(order.getCurrency());
            price.setText(context.getString(R.string.exchange_order_unit_price_format,
                    formatFiat(unit), symbol));
            status.setText(context.getString(own
                    ? R.string.exchange_order_your : R.string.exchange_order_open));
            status.setTextColor(context.getColor(own
                    ? R.color.accent_purple : R.color.success_green));
            amount.setText(context.getString(R.string.exchange_order_amount_format,
                    formatAmount(shown)));
            total.setText(context.getString(R.string.exchange_order_total_format,
                    formatFiat(fiatTotal), symbol));
            deal.setVisibility(View.GONE);
            dispute.setVisibility(View.GONE);

            boolean hasDetails = !TextUtils.isEmpty(order.getPaymentDetails());
            details.setVisibility(hasDetails ? View.VISIBLE : View.GONE);
            details.setOnClickListener(hasDetails
                    ? v -> showOrderDetails(order) : null);

            if (own) {
                action.setText(R.string.exchange_order_cancel);
                action.setOnClickListener(v -> handleCancel(order));
            } else if (sell) {
                action.setText(R.string.market_buy);
                action.setOnClickListener(v -> handleBuy(order));
            } else {
                action.setText(R.string.market_sell);
                action.setOnClickListener(v -> handleSell(order));
            }
        }
    }
}
