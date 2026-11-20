package com.triger.poc;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.util.Collections;

public class QrScannerActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT = "com.triger.poc.extra.QR_RESULT";

    private static final int REQUEST_CAMERA = 4301;

    private DecoratedBarcodeView barcodeView;
    private boolean permissionRequested;
    private boolean handled;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scanner);
        configureDialogWindow();

        barcodeView = findViewById(R.id.barcode_view);
        barcodeView.getBarcodeView().setUseTextureView(true);
        barcodeView.setDecoderFactory(new DefaultDecoderFactory(
                Collections.singletonList(BarcodeFormat.QR_CODE)));
        barcodeView.setStatusText("");
        TextView statusView = barcodeView.getStatusView();
        if (statusView != null) {
            statusView.setVisibility(View.GONE);
        }
        barcodeView.decodeSingle(this::onBarcodeScanned);

        findViewById(R.id.btn_scanner_close).setOnClickListener(v -> finish());
    }

    private void configureDialogWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.68f);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        boolean landscape = metrics.widthPixels > metrics.heightPixels;
        int width = (int) (metrics.widthPixels * (landscape ? 0.7f : 0.92f));
        int height = (int) (metrics.heightPixels * (landscape ? 0.9f : 0.78f));
        window.setLayout(width, height);
        window.setGravity(Gravity.CENTER);
    }

    private void onBarcodeScanned(BarcodeResult result) {
        if (handled || result == null || TextUtils.isEmpty(result.getText())) {
            return;
        }
        handled = true;
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT, result.getText());
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission()) {
            barcodeView.resume();
        } else if (!permissionRequested) {
            permissionRequested = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onPause() {
        if (barcodeView != null) {
            barcodeView.pause();
        }
        super.onPause();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, R.string.error_camera_permission,
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}
