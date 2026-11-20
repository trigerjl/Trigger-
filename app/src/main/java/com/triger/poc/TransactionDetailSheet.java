package com.triger.poc;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TransactionDetailSheet extends BottomSheetDialogFragment {

    private static final String ARG_TYPE = "arg_type";
    private static final String ARG_AMOUNT = "arg_amount";
    private static final String ARG_TIMESTAMP = "arg_timestamp";
    private static final String ARG_HASH = "arg_hash";
    private static final String ARG_MEMO = "arg_memo";

    public static TransactionDetailSheet newInstance(String type, double amount,
                                                     long timestamp, String hash) {
        return newInstance(type, amount, timestamp, hash, "");
    }

    public static TransactionDetailSheet newInstance(String type, double amount,
                                                     long timestamp, String hash,
                                                     String memo) {
        TransactionDetailSheet sheet = new TransactionDetailSheet();
        Bundle args = new Bundle();
        args.putString(ARG_TYPE, type);
        args.putDouble(ARG_AMOUNT, amount);
        args.putLong(ARG_TIMESTAMP, timestamp);
        args.putString(ARG_HASH, hash);
        args.putString(ARG_MEMO, memo);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_transaction_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments();
        String type = args == null ? "" : args.getString(ARG_TYPE, "");
        double amount = args == null ? 0 : args.getDouble(ARG_AMOUNT, 0);
        long timestamp = args == null ? 0 : args.getLong(ARG_TIMESTAMP, 0);
        String rawMemo = args == null ? "" : args.getString(ARG_MEMO, "");
        final String memo = rawMemo == null ? "" : rawMemo.trim();

        TextView amountView = view.findViewById(R.id.tv_tx_detail_amount);
        TextView typeView = view.findViewById(R.id.tv_tx_detail_type);
        TextView typeValueView = view.findViewById(R.id.tv_tx_detail_type_value);
        TextView dateView = view.findViewById(R.id.tv_tx_detail_date);
        TextView statusView = view.findViewById(R.id.tv_tx_detail_status);
        ImageView iconView = view.findViewById(R.id.iv_tx_detail_icon);

        boolean outgoing = isOutgoing(type);
        String title = titleFor(type);
        amountView.setText(getString(R.string.amount_signed_format,
                outgoing ? "-" : "+",
                Tokenomics.formatAmount(Math.abs(amount))));
        amountView.setTextColor(ContextCompat.getColor(requireContext(), amountColorFor(type)));
        typeView.setText(title);
        typeValueView.setText(title);
        dateView.setText(new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                .format(new Date(timestamp)));
        statusView.setText(R.string.status_confirmed);
        iconView.setImageResource(iconFor(type));
        iconView.setColorFilter(ContextCompat.getColor(requireContext(), iconColorFor(type)));

        // Хеш транзакции — техническая информация, доступная только в DebugActivity.
        View hashSection = view.findViewById(R.id.ll_tx_detail_hash);
        if (hashSection != null) {
            hashSection.setVisibility(View.GONE);
        }

        View memoSection = view.findViewById(R.id.ll_tx_detail_memo);
        TextView memoView = view.findViewById(R.id.tv_tx_detail_memo);
        if (memo.isEmpty()) {
            memoSection.setVisibility(View.GONE);
        } else {
            memoView.setText(memo);
        }

        view.findViewById(R.id.btn_tx_detail_close).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btn_tx_detail_done).setOnClickListener(v -> dismiss());
    }

    private static boolean isOutgoing(String type) {
        return TransactionHistory.TYPE_SEND.equals(type)
                || TransactionHistory.TYPE_BURN.equals(type);
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

    private static int iconFor(String type) {
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

    private static int amountColorFor(String type) {
        if (TransactionHistory.TYPE_SEND.equals(type)) {
            return R.color.danger_red;
        }
        if (TransactionHistory.TYPE_BURN.equals(type)) {
            return R.color.accent_orange;
        }
        return R.color.success_green;
    }

    private static int iconColorFor(String type) {
        if (TransactionHistory.TYPE_SEND.equals(type)) {
            return R.color.danger_red;
        }
        if (TransactionHistory.TYPE_RECEIVE.equals(type)) {
            return R.color.success_green;
        }
        if (TransactionHistory.TYPE_BURN.equals(type)) {
            return R.color.accent_orange;
        }
        return R.color.accent_purple;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        }
    }
}
