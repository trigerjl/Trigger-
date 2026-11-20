package com.triger.poc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class WalletQrSheet extends BottomSheetDialogFragment {

    private static final String ARG_ADDRESS = "arg_address";

    public static WalletQrSheet newInstance(String address) {
        WalletQrSheet sheet = new WalletQrSheet();
        Bundle args = new Bundle();
        args.putString(ARG_ADDRESS, address);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_wallet_qr, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String address = getArguments() == null ? "" : getArguments().getString(ARG_ADDRESS, "");

        ImageView qrView = view.findViewById(R.id.iv_wallet_qr);
        TextView addressView = view.findViewById(R.id.tv_wallet_qr_address);
        addressView.setText(address);

        Bitmap qr = QrCodeUtils.generate(address, 720);
        if (qr == null) {
            Toast.makeText(requireContext(), R.string.error_qr_generate,
                    Toast.LENGTH_SHORT).show();
            dismiss();
            return;
        }
        qrView.setImageBitmap(qr);

        view.findViewById(R.id.btn_qr_sheet_close).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btn_qr_close).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btn_qr_copy).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("wallet_address", address));
                Toast.makeText(requireContext(), R.string.toast_address_copied,
                        Toast.LENGTH_SHORT).show();
            }
        });
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
