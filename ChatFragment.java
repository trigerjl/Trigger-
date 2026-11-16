package com.triger.poc;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class ChatFragment extends Fragment {

    private final IrohNode irohNode = IrohNode.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvMyPeerId;
    private TextView tvStatus;
    private TextView tvEmpty;
    private LinearLayout peersContainer;

    private final Runnable endpointRefresh = new Runnable() {
        @Override
        public void run() {
            renderMyEndpointId();
            if (TextUtils.isEmpty(irohNode.getSelfEndpointId())) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable peersRefresh = new Runnable() {
        @Override
        public void run() {
            renderPeerList();
            handler.postDelayed(this, 2000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvMyPeerId = view.findViewById(R.id.tv_chat_my_peer_id);
        tvStatus = view.findViewById(R.id.tv_chat_status);
        tvEmpty = view.findViewById(R.id.tv_chat_empty);
        peersContainer = view.findViewById(R.id.ll_chat_peers);
        MaterialButton btnRefresh = view.findViewById(R.id.btn_refresh_peers);

        btnRefresh.setOnClickListener(v -> renderPeerList());
        tvMyPeerId.setOnClickListener(v -> copyMyEndpointId());

        irohNode.start(requireContext().getApplicationContext());
        irohNode.startDiscovery();
        renderMyEndpointId();
        renderPeerList();
        handler.post(endpointRefresh);
        handler.post(peersRefresh);
    }

    private void openChatWith(String peerEndpointId) {
        Intent intent = new Intent(requireContext(), ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_PEER_ID, peerEndpointId);
        startActivity(intent);
    }

    private void copyMyEndpointId() {
        String id = irohNode.getSelfEndpointId();
        if (TextUtils.isEmpty(id)) {
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) requireContext()
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Iroh EndpointId", id));
            Toast.makeText(requireContext(), "Endpoint ID скопирован", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderMyEndpointId() {
        if (tvMyPeerId == null) {
            return;
        }
        String id = irohNode.getSelfEndpointId();
        tvMyPeerId.setText(TextUtils.isEmpty(id)
                ? "Мой Endpoint ID: запускается..."
                : "Мой Endpoint ID: " + id + "\n(нажмите, чтобы скопировать)");
        if (tvStatus != null) {
            tvStatus.setText(TextUtils.isEmpty(id)
                    ? "Запуск Iroh..."
                    : "Готово: выберите собеседника из списка");
        }
    }

    private void renderPeerList() {
        if (peersContainer == null || getContext() == null) {
            return;
        }
        peersContainer.removeAllViews();

        String self = irohNode.getSelfEndpointId();
        List<String> connected = irohNode.getConnectedPeers();
        List<String> peers = irohNode.getKnownPeers();
        int shown = 0;
        for (String peer : peers) {
            if (TextUtils.isEmpty(peer) || peer.equals(self)) {
                continue;
            }
            peersContainer.addView(createPeerRow(peer, connected.contains(peer)));
            shown++;
        }

        if (tvEmpty != null) {
            tvEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        }
    }

    private View createPeerRow(String peer, boolean connected) {
        MaterialButton row = new MaterialButton(requireContext());
        row.setText(peer + (connected ? "\n● подключён" : "\n○ найден, подключение при отправке"));
        row.setTextSize(12);
        row.setTypeface(Typeface.MONOSPACE);
        row.setAllCaps(false);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setSingleLine(false);
        row.setMaxLines(3);
        row.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setOnClickListener(v -> openChatWith(peer));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onResume() {
        super.onResume();
        renderMyEndpointId();
        renderPeerList();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        tvMyPeerId = null;
        tvStatus = null;
        tvEmpty = null;
        peersContainer = null;
        super.onDestroyView();
    }
}
