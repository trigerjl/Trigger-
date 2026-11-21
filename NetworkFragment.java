package com.triger.poc;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

public class NetworkFragment extends Fragment implements MainActivity.Listener {

    private TextView tvNetworkNodes;
    private TextView tvMyPeerId;
    private TextView tvIrohStatus;
    private LinearLayout nodesContainer;
    private MainActivity activity;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            renderIrohStatus();
            statusHandler.postDelayed(this, 2000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_network, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvNetworkNodes = view.findViewById(R.id.tv_network_nodes);
        tvMyPeerId = view.findViewById(R.id.tv_my_peer_id);
        tvIrohStatus = view.findViewById(R.id.tv_libp2p_peers);
        nodesContainer = view.findViewById(R.id.nodes_container);

        if (getActivity() instanceof MainActivity) {
            activity = (MainActivity) getActivity();
        }

        view.findViewById(R.id.btn_scan_network).setOnClickListener(v -> {
            if (activity != null) {
                activity.scanForPeers();
            }
        });
        view.findViewById(R.id.btn_add_node).setOnClickListener(v -> showAddNodeDialog());

        if (activity != null) {
            activity.addListener(this);
        }
        renderIrohStatus();
    }

    private void showAddNodeDialog() {
        if (activity == null) {
            return;
        }
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Peer ID");
        new AlertDialog.Builder(requireContext())
                .setTitle("Добавить узел")
                .setView(input)
                .setPositiveButton("Подключить", (dialog, which) -> {
                    String peerId = input.getText().toString().trim();
                    if (!peerId.isEmpty()) {
                        activity.connectToPeerById(peerId);
                        addNodeRow(peerId, "Подключение...", "—", "—");
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void addNodeRow(String peerId, String status, String ip, String ping) {
        if (nodesContainer == null) {
            return;
        }
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_node, nodesContainer, false);
        ((TextView) row.findViewById(R.id.tv_node_id)).setText(peerId);
        ((TextView) row.findViewById(R.id.tv_node_info)).setText(status + " · " + ip + " · " + ping);
        row.findViewById(R.id.btn_node_disconnect).setOnClickListener(v -> {
            if (activity != null) {
                activity.showToast("Узел отключён (демо)");
            }
        });
        nodesContainer.addView(row);
    }

    @Override
    public void onDestroyView() {
        statusHandler.removeCallbacks(statusRefresh);
        if (activity != null) {
            activity.removeListener(this);
            activity = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onNetworkStatus(String text) {
        if (tvNetworkNodes != null) {
            tvNetworkNodes.setText(text);
        }
    }

    @Override
    public void onNetworkError(String message) {
        // Внешний сигнальный сервер больше не используется — ошибок сети не показываем.
    }

    @Override
    public void onPeerId(String peerId) {
        if (tvMyPeerId != null) {
            tvMyPeerId.setText("Мой Endpoint ID: " + peerId);
        }
        renderIrohStatus();
    }

    /** В блоке журнала показываем только состояние Iroh, без логов libp2p. */
    private void renderIrohStatus() {
        if (tvIrohStatus == null) {
            return;
        }
        tvIrohStatus.setText(activity != null
                ? activity.getIrohStatusText()
                : "Iroh: недоступен");
    }

    @Override
    public void onResume() {
        super.onResume();
        renderIrohStatus();
        statusHandler.removeCallbacks(statusRefresh);
        statusHandler.post(statusRefresh);
    }

    @Override
    public void onPause() {
        statusHandler.removeCallbacks(statusRefresh);
        super.onPause();
    }
}
