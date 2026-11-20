package com.triger.poc;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class ChatFragment extends Fragment {

    private final IrohNode irohNode = IrohNode.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvMyPeerId;
    private TextView tvStatus;
    private TextView tvEmpty;
    private LinearLayout peersContainer;
    private EditText etSearchUsername;
    private TextView tvSearchResult;
    private MaterialButton btnSearchConnect;
    private SwipeRefreshLayout swipeRefresh;
    private AddressBook.Contact searchContact;

    private final IrohNode.OnPeerSearchListener searchListener =
            (query, endpointId, username) -> handler.post(
                    () -> applySearchResult(query, endpointId, username));

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
        etSearchUsername = view.findViewById(R.id.et_search_username);
        tvSearchResult = view.findViewById(R.id.tv_search_result);
        btnSearchConnect = view.findViewById(R.id.btn_search_connect);
        MaterialButton btnRefresh = view.findViewById(R.id.btn_refresh_peers);
        MaterialButton btnSearch = view.findViewById(R.id.btn_search_username);
        swipeRefresh = view.findViewById(R.id.swipe_refresh_chat);
        swipeRefresh.setOnRefreshListener(() -> {
            irohNode.start(requireContext().getApplicationContext());
            irohNode.startDiscovery();
            renderMyEndpointId();
            renderPeerList();
            if (etSearchUsername != null && !TextUtils.isEmpty(etSearchUsername.getText())) {
                searchByUsername();
            }
            swipeRefresh.setRefreshing(false);
        });

        btnRefresh.setOnClickListener(v -> renderPeerList());
        btnSearch.setOnClickListener(v -> searchByUsername());
        btnSearchConnect.setOnClickListener(v -> {
            AddressBook.Contact contact = searchContact;
            if (contact != null) {
                openChatWith(contact.endpointId);
            }
        });
        etSearchUsername.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchByUsername();
                return true;
            }
            return false;
        });
        tvMyPeerId.setOnClickListener(v -> copyMyEndpointId());

        irohNode.addOnPeerSearchListener(searchListener);
        irohNode.start(requireContext().getApplicationContext());
        irohNode.startDiscovery();
        renderMyEndpointId();
        renderPeerList();
        handler.post(endpointRefresh);
        handler.post(peersRefresh);
    }

    private void openChatWith(String peerEndpointId) {
        String target = peerEndpointId == null ? "" : peerEndpointId.trim();
        if (TextUtils.isEmpty(target)) {
            Toast.makeText(requireContext(), R.string.profile_endpoint_invalid,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final IrohNode node = irohNode;
        node.start(requireContext().getApplicationContext());
        node.startDiscovery();
        new Thread(() -> {
            try {
                node.resolvePeerByEndpointId(target);
                requireActivity().runOnUiThread(() -> {
                    Intent intent = new Intent(requireContext(), ChatActivity.class);
                    intent.putExtra(ChatActivity.EXTRA_PEER_ID, target);
                    startActivity(intent);
                });
            } catch (IrohNode.PeerNotFoundException e) {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), getString(
                                R.string.error_resolve_dns_format,
                                t.getMessage() == null ? t.toString() : t.getMessage()),
                                Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void searchByUsername() {
        if (etSearchUsername == null || tvSearchResult == null || btnSearchConnect == null) {
            return;
        }
        String query = etSearchUsername.getText() == null
                ? "" : etSearchUsername.getText().toString().trim();
        if (TextUtils.isEmpty(query)) {
            searchContact = null;
            tvSearchResult.setVisibility(View.VISIBLE);
            tvSearchResult.setText(R.string.profile_enter_nickname);
            btnSearchConnect.setVisibility(View.GONE);
            return;
        }

        AddressBook.Contact contact = AddressBook.findByUsername(requireContext(), query);
        tvSearchResult.setVisibility(View.VISIBLE);
        if (contact == null) {
            searchContact = null;
            int sent = irohNode.requestPeerByUsername(query);
            tvSearchResult.setText(sent > 0
                    ? getString(R.string.chat_searching_format, query)
                    : getString(R.string.profile_not_found));
            btnSearchConnect.setVisibility(View.GONE);
            return;
        }

        searchContact = contact;
        tvSearchResult.setText(getString(R.string.chat_found_format,
                contact.username, contact.endpointId));
        btnSearchConnect.setVisibility(View.VISIBLE);
    }

    /** Показывает контакт, найденный через Peer Exchange. */
    private void applySearchResult(String query, String endpointId, String username) {
        if (etSearchUsername == null || tvSearchResult == null || btnSearchConnect == null) {
            return;
        }
        String current = etSearchUsername.getText() == null
                ? "" : etSearchUsername.getText().toString().trim();
        if (TextUtils.isEmpty(current) || !current.equalsIgnoreCase(query)) {
            return;
        }
        searchContact = new AddressBook.Contact(username, endpointId);
        tvSearchResult.setVisibility(View.VISIBLE);
        tvSearchResult.setText(getString(R.string.chat_found_network_format,
                username, endpointId));
        btnSearchConnect.setVisibility(View.VISIBLE);
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
            Toast.makeText(requireContext(), R.string.profile_endpoint_copied,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void renderMyEndpointId() {
        if (tvMyPeerId == null) {
            return;
        }
        String id = irohNode.getSelfEndpointId();
        tvMyPeerId.setText(TextUtils.isEmpty(id)
                ? getString(R.string.chat_my_endpoint_starting)
                : getString(R.string.chat_my_endpoint_format, id));
        if (tvStatus != null) {
            tvStatus.setText(TextUtils.isEmpty(id)
                    ? R.string.chat_iroh_starting
                    : R.string.chat_ready);
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
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_peer, peersContainer, false);
        TextView nameView = row.findViewById(R.id.tv_peer_id);
        TextView infoView = row.findViewById(R.id.tv_peer_info);
        View dotView = row.findViewById(R.id.view_peer_dot);
        MaterialButton openButton = row.findViewById(R.id.btn_peer_open);

        nameView.setText(irohNode.getPeerDisplayName(peer));
        infoView.setText(PeerNameGenerator.shortId(peer) + " · "
                + (connected ? getString(R.string.chat_peer_connected)
                : getString(R.string.chat_peer_found_on_send)));
        dotView.setBackgroundResource(connected
                ? R.drawable.bg_status_dot
                : R.drawable.bg_status_dot_red);

        row.setOnClickListener(v -> openChatWith(peer));
        openButton.setOnClickListener(v -> openChatWith(peer));
        return row;
    }

    @Override
    public void onResume() {
        super.onResume();
        renderMyEndpointId();
        renderPeerList();
    }

    @Override
    public void onDestroyView() {
        irohNode.removeOnPeerSearchListener(searchListener);
        handler.removeCallbacksAndMessages(null);
        tvMyPeerId = null;
        tvStatus = null;
        tvEmpty = null;
        peersContainer = null;
        etSearchUsername = null;
        tvSearchResult = null;
        btnSearchConnect = null;
        swipeRefresh = null;
        searchContact = null;
        super.onDestroyView();
    }
}
