package com.triger.poc;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class IrohTestActivity extends AppCompatActivity {

    private final IrohNode node = IrohNode.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvMyId;
    private TextView tvStatus;
    private EditText etPeerId;
    private MaterialButton btnOpenChat;
    private String connectedPeer = "";

    private final Runnable idRefresh = new Runnable() {
        @Override
        public void run() {
            renderMyId();
            if (TextUtils.isEmpty(node.getEndpointId())) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_iroh_test);

        tvMyId = findViewById(R.id.tv_iroh_my_id);
        tvStatus = findViewById(R.id.tv_iroh_status);
        etPeerId = findViewById(R.id.et_iroh_peer_id);
        btnOpenChat = findViewById(R.id.btn_iroh_open_chat);
        MaterialButton btnConnect = findViewById(R.id.btn_iroh_connect);

        btnConnect.setOnClickListener(v -> connect());
        btnOpenChat.setOnClickListener(v -> openChat(connectedPeer));
        tvMyId.setOnClickListener(v -> {
            String id = node.getEndpointId();
            if (!TextUtils.isEmpty(id)) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Iroh EndpointId", id));
                    Toast.makeText(this, "Endpoint ID скопирован", Toast.LENGTH_SHORT).show();
                }
            }
        });

        node.start(getApplicationContext());
        renderMyId();
        handler.post(idRefresh);
    }

    private void connect() {
        String peer = etPeerId.getText() == null ? "" : etPeerId.getText().toString().trim();
        if (peer.isEmpty()) {
            tvStatus.setText("Ошибка: введите Endpoint ID собеседника");
            return;
        }
        tvStatus.setText("Подключение...");
        btnOpenChat.setVisibility(android.view.View.GONE);
        node.connectToPeer(peer, new IrohNode.ConnectCallback() {
            @Override
            public void onSuccess(String endpointId) {
                runOnUiThread(() -> {
                    connectedPeer = endpointId;
                    tvStatus.setText("Успешно: " + endpointId);
                    btnOpenChat.setVisibility(android.view.View.VISIBLE);
                });
            }

            @Override
            public void onError(String endpointId, String message) {
                runOnUiThread(() -> tvStatus.setText("Ошибка: " + message));
            }
        });
    }

    private void openChat(String peer) {
        if (TextUtils.isEmpty(peer)) {
            return;
        }
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_PEER_ID, peer);
        startActivity(intent);
    }

    private void renderMyId() {
        String id = node.getEndpointId();
        tvMyId.setText(TextUtils.isEmpty(id)
                ? "Мой Endpoint ID: запускается..."
                : "Мой Endpoint ID: " + id + "\n(нажмите, чтобы скопировать)");
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
