package com.triger.poc;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class OrderCreateDialog {

    private OrderCreateDialog() {
    }

    static void show(Fragment fragment, boolean initialSell, Runnable onPlaced) {
        if (fragment == null || !fragment.isAdded()) {
            return;
        }
        Context context = fragment.requireContext();
        View content = LayoutInflater.from(context)
                .inflate(R.layout.dialog_create_order, null, false);
        Spinner typeSpinner = content.findViewById(R.id.spinner_order_type);
        Spinner currencySpinner = content.findViewById(R.id.spinner_order_currency);
        Spinner methodSpinner = content.findViewById(R.id.spinner_order_method);
        EditText amountInput = content.findViewById(R.id.et_order_amount);
        EditText priceInput = content.findViewById(R.id.et_order_price);
        EditText detailsInput = content.findViewById(R.id.et_order_details);

        List<String> types = new ArrayList<>();
        types.add(context.getString(R.string.exchange_buy));
        types.add(context.getString(R.string.exchange_sell));
        typeSpinner.setAdapter(spinnerAdapter(context, types));
        typeSpinner.setSelection(initialSell ? 1 : 0);

        bindCurrencySpinner(context, currencySpinner, priceInput);
        bindMethodSpinner(context, methodSpinner, detailsInput);

        P2PExchangeManager exchangeManager = new P2PExchangeManager(context);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.exchange_new_order)
                .setView(content)
                .setPositiveButton(R.string.exchange_new_order, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    boolean sell = typeSpinner.getSelectedItemPosition() == 1;
                    double amount = parseAmount(amountInput.getText().toString());
                    double price = parseAmount(priceInput.getText().toString());
                    String details = detailsInput.getText().toString().trim();
                    if (amount <= 0.0 || price <= 0.0) {
                        toast(context, context.getString(R.string.exchange_invalid_amount));
                        return;
                    }
                    if (sell && TextUtils.isEmpty(details)) {
                        toast(context, context.getString(R.string.exchange_order_payment_required));
                        return;
                    }
                    String currency = currencyCodeAt(currencySpinner.getSelectedItemPosition());
                    String method = methodCodeAt(methodSpinner.getSelectedItemPosition());
                    String wallet = TransferManager.normalizeAddress(
                            KeyManager.getOrCreateAddress(context));
                    String nickname = IrohNode.getInstance().getLocalUsername();
                    if (TextUtils.isEmpty(nickname) || nickname.startsWith("peer_")) {
                        nickname = shortAddress(wallet);
                    }
                    double totalPrice = BigDecimal.valueOf(price)
                            .multiply(BigDecimal.valueOf(amount))
                            .setScale(8, java.math.RoundingMode.HALF_UP)
                            .doubleValue();
                    P2PExchangeManager.Order order = new P2PExchangeManager.Order(
                            "", wallet, nickname,
                            sell ? P2PExchangeManager.Order.TYPE_SELL
                                    : P2PExchangeManager.Order.TYPE_BUY,
                            amount, amount, totalPrice, currency, method, details, "", 0L,
                            P2PExchangeManager.Order.STATUS_OPEN);
                    P2PExchangeManager.Result result = exchangeManager.placeOrder(order);
                    if (!result.success) {
                        toast(context, result.message);
                        return;
                    }
                    toast(context, result.message);
                    dialog.dismiss();
                    if (onPlaced != null) {
                        onPlaced.run();
                    }
                }));
        dialog.show();
    }

    private static void bindCurrencySpinner(Context context, Spinner currencySpinner,
                                             EditText priceInput) {
        List<String> currencies = new ArrayList<>();
        for (String code : P2PExchangeManager.SUPPORTED_CURRENCIES) {
            currencies.add(context.getString(currencyLabelRes(code)));
        }
        currencySpinner.setAdapter(spinnerAdapter(context, currencies));
        currencySpinner.setSelection(0);
        currencySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updatePriceHint(context, priceInput, currencyCodeAt(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updatePriceHint(context, priceInput, currencyCodeAt(0));
    }

    private static void bindMethodSpinner(Context context, Spinner methodSpinner,
                                           EditText detailsInput) {
        List<String> methods = new ArrayList<>();
        for (String code : P2PExchangeManager.Order.SUPPORTED_PAYMENT_METHODS) {
            methods.add(paymentMethodName(context, code));
        }
        methodSpinner.setAdapter(spinnerAdapter(context, methods));
        methodSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateDetailsHint(context, detailsInput, methodCodeAt(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateDetailsHint(context, detailsInput, P2PExchangeManager.Order.PAYMENT_CARD);
    }

    private static void updatePriceHint(Context context, EditText priceInput, String currency) {
        priceInput.setHint(context.getString(R.string.exchange_order_price_hint)
                + " (" + P2PExchangeManager.currencySymbol(currency) + ")");
    }

    private static void updateDetailsHint(Context context, EditText detailsInput, String method) {
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

    private static ArrayAdapter<String> spinnerAdapter(Context context, List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private static String currencyCodeAt(int position) {
        List<String> codes = P2PExchangeManager.SUPPORTED_CURRENCIES;
        return position >= 0 && position < codes.size() ? codes.get(position) : codes.get(0);
    }

    private static String methodCodeAt(int position) {
        List<String> codes = P2PExchangeManager.Order.SUPPORTED_PAYMENT_METHODS;
        return position >= 0 && position < codes.size()
                ? codes.get(position) : P2PExchangeManager.Order.PAYMENT_OTHER;
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

    private static void toast(Context context, String message) {
        if (!TextUtils.isEmpty(message)) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
    }

    private static String shortAddress(String address) {
        String value = TransferManager.normalizeAddress(address);
        if (value.length() <= 12) {
            return value;
        }
        return value.substring(0, 6) + "…" + value.substring(value.length() - 4);
    }

}
