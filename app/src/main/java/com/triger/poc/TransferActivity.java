package com.triger.poc;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class TransferActivity extends AppCompatActivity {

    public static final String EXTRA_RECIPIENT_ADDRESS = "recipient_address";
    public static final String EXTRA_AMOUNT = "amount";

    private static final int REQUEST_SCAN_QR = 4401;

    private EditText etAddress;
    private EditText etAmount;
    private EditText etMemo;
    private TextView tvBalance;
    private TextView tvFee;
    private TextView tvTotal;
    private TextView tvPrice;
    private MaterialButton btnSend;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);

        etAddress = findViewById(R.id.et_transfer_address);
        etAmount = findViewById(R.id.et_transfer_amount);
        etMemo = findViewById(R.id.et_transfer_memo);
        tvBalance = findViewById(R.id.tv_transfer_balance);
        tvFee = findViewById(R.id.tv_transfer_fee);
        tvTotal = findViewById(R.id.tv_transfer_total);
        tvPrice = findViewById(R.id.tv_transfer_price);
        btnSend = findViewById(R.id.btn_transfer_send);

        String initialAddress = getIntent() == null ? ""
                : getIntent().getStringExtra(EXTRA_RECIPIENT_ADDRESS);
        if (!TextUtils.isEmpty(initialAddress)
                && TransferManager.isValidAddress(initialAddress)) {
            etAddress.setText(TransferManager.normalizeAddress(initialAddress));
            etAddress.setSelection(etAddress.getText().length());
        }
        String initialAmount = getIntent() == null ? ""
                : getIntent().getStringExtra(EXTRA_AMOUNT);
        if (!TextUtils.isEmpty(initialAmount)) {
            etAmount.setText(initialAmount);
            etAmount.setSelection(etAmount.getText().length());
        }

        findViewById(R.id.btn_transfer_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_transfer_search).setOnClickListener(v -> showContactPicker());
        findViewById(R.id.btn_transfer_scan).setOnClickListener(v ->
                startActivityForResult(new Intent(this, QrScannerActivity.class), REQUEST_SCAN_QR));
        btnSend.setOnClickListener(v -> submitTransfer());

        etAmount.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateFeePreview();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        updateBalance();
        updateFeePreview();
        updatePriceOracle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBalance();
        updatePriceOracle();
    }

    private void updatePriceOracle() {
        if (tvPrice == null) {
            return;
        }
        Double price = new PriceOracle(this).getPrice();
        if (price == null || price <= 0.0) {
            tvPrice.setText(R.string.price_oracle_hint);
        } else {
            String formatted = BigDecimal.valueOf(price)
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString();
            tvPrice.setText(getString(R.string.price_oracle_in_market, formatted));
        }
        tvPrice.setVisibility(TextView.VISIBLE);
    }

    private void updateBalance() {
        if (tvBalance != null) {
            tvBalance.setText(getString(R.string.amount_format,
                    Tokenomics.formatAmount(KeyManager.getLocalBalance(this))));
        }
    }

    private void showContactPicker() {
        List<String> peers = ChatDatabase.getInstance(this).getConversationPeerIds();
        if (peers.isEmpty()) {
            Toast.makeText(this, R.string.transfer_no_contacts, Toast.LENGTH_SHORT).show();
            return;
        }

        IrohNode node = IrohNode.getInstance();
        List<String> labels = new ArrayList<>();
        List<String> wallets = new ArrayList<>();
        for (String peer : peers) {
            String wallet = node.getPeerWalletAddress(peer);
            wallets.add(wallet);
            String name = node.getPeerDisplayName(peer);
            String address = TextUtils.isEmpty(wallet)
                    ? getString(R.string.transfer_contact_no_wallet)
                    : wallet;
            labels.add(name + "\n" + address);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.transfer_choose_recipient)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String wallet = wallets.get(which);
                    if (TextUtils.isEmpty(wallet)) {
                        Toast.makeText(this, R.string.transfer_contact_no_wallet,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    etAddress.setText(wallet);
                    etAddress.setSelection(etAddress.getText().length());
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void updateFeePreview() {
        if (tvFee == null || tvTotal == null) {
            return;
        }
        BigDecimal amount = parseAmount();
        if (amount == null || amount.signum() <= 0) {
            tvFee.setText(R.string.amount_zero_aura);
            tvTotal.setText(R.string.amount_zero_aura);
            return;
        }
        BigDecimal fee = TransferManager.calculateFee(amount);
        BigDecimal total = amount.add(fee);
        tvFee.setText(getString(R.string.amount_format,
                Tokenomics.formatAmount(fee.doubleValue())));
        tvTotal.setText(getString(R.string.amount_format,
                Tokenomics.formatAmount(total.doubleValue())));
    }

    private BigDecimal parseAmount() {
        if (etAmount == null || etAmount.getText() == null) {
            return null;
        }
        String raw = etAmount.getText().toString().trim().replace(',', '.');
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw).setScale(8, RoundingMode.HALF_UP);
        } catch (Throwable t) {
            return null;
        }
    }

    private void submitTransfer() {
        String address = etAddress == null || etAddress.getText() == null
                ? "" : etAddress.getText().toString().trim();
        String amountText = etAmount == null || etAmount.getText() == null
                ? "" : etAmount.getText().toString().trim();
        String memo = etMemo == null || etMemo.getText() == null
                ? "" : etMemo.getText().toString().trim();
        if (TextUtils.isEmpty(address)) {
            Toast.makeText(this, R.string.transfer_enter_recipient, Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(amountText)) {
            Toast.makeText(this, R.string.transfer_enter_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        if (memo.length() > TransferManager.MAX_MEMO_LENGTH) {
            Toast.makeText(this, R.string.transfer_memo_limit, Toast.LENGTH_SHORT).show();
            return;
        }

        btnSend.setEnabled(false);
        TransferManager.Result result =
                TransferManager.sendTransfer(this, address, amountText, memo);
        if (result.success) {
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
            setResult(RESULT_OK);
            finish();
            return;
        }
        btnSend.setEnabled(true);
        updateBalance();
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_SCAN_QR) {
            if (resultCode == RESULT_OK && data != null) {
                String contents = data.getStringExtra(QrScannerActivity.EXTRA_RESULT);
                String address = TransferManager.extractAddress(contents);
                if (TextUtils.isEmpty(address) || !TransferManager.isValidAddress(address)) {
                    Toast.makeText(this, R.string.transfer_qr_no_wallet,
                            Toast.LENGTH_LONG).show();
                } else {
                    etAddress.setText(TransferManager.normalizeAddress(address));
                    etAddress.setSelection(etAddress.getText().length());
                }
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
