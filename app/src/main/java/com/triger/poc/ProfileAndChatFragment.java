package com.triger.poc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ProfileAndChatFragment extends Fragment {

    private static final int REQUEST_PICK_AVATAR = 1101;
    private static final int REQUEST_PREVIEW_COSMETIC = 1102;
    private static final long ENDPOINT_SEARCH_TIMEOUT_MS = 15000L;
    private static final long ENDPOINT_SEARCH_RETRY_DELAY_MS = 2000L;
    private static final int ENDPOINT_SEARCH_MAX_ATTEMPTS = 2;
    private static final long CONNECTING_STATUS_WINDOW_MS = 5000L;

    private final IrohNode irohNode = IrohNode.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long statusStartedAt = SystemClock.elapsedRealtime();

    private EditText etUsername;
    private ImageView ivAvatar;
    private ImageView ivAvatarFrame;
    private TextView tvReputation;
    private String endpointId = "";
    private View cosmeticsDialogContent;
    private ChatDatabase chatDatabase;

    private TabLayout tabChats;
    private TabLayoutMediator tabMediator;
    private TextView tvPeerStatus;
    private TextView tvRelayStatus;
    private ViewPager2 pagerChats;

    private View pageMyChats;
    private View pageOnline;
    private View emptyState;
    private TextView tvChatEmpty;
    private TextView tvChatEmptyHint;
    private RecyclerView peersRecycler;
    private PeerAdapter peersAdapter;
    private RecyclerView onlineRecycler;
    private PeerAdapter onlineAdapter;
    private TextView tvOnlineEmpty;

    private EditText etSearchUsername;
    private TextView tvSearchResult;
    private String pendingEndpointSearchId;
    private Runnable endpointSearchTimeout;

    private final IrohNode.OnPeerSearchListener searchListener =
            (query, endpointId, username) -> handler.post(
                    () -> applySearchResult(query, endpointId, username));
    private final IrohNode.OnPeerDiscoveredListener peerDiscoveredListener =
            peer -> handler.post(this::renderPeerList);



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
        return inflater.inflate(R.layout.fragment_profile_and_chat, container, false);
    }

    private void refreshReputation() {
        if (tvReputation == null || getContext() == null) {
            return;
        }
        String simHash = SimIdentity.getOrCreateSimHash(requireContext());
        int points = ReputationManager.getReputation(requireContext(), simHash);
        if (!SimIdentity.hasRealSim(requireContext())) {
            tvReputation.setText(getString(R.string.profile_reputation_format, points,
                    getString(R.string.observer_status_no_sim)));
        } else {
            tvReputation.setText(getString(R.string.profile_reputation_points_format, points));
        }
    }

    private String buildAchievementsSummary(Context context) {
        return getString(DeviceIdentity.isStrongBoxBacked(context)
                ? R.string.device_strongbox_available
                : R.string.device_strongbox_unavailable);
    }

    private void showReputationInfo() {
        if (getContext() == null) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.reputation_info_title)
                .setMessage(R.string.reputation_info_message)
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    private void showProfileSettings() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        String[] items = new String[]{
                getString(R.string.profile_change_avatar),
                getString(R.string.profile_show_achievements)
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.profile_settings)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        pickAvatar();
                    } else {
                        showCosmeticsAchievements();
                    }
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void saveProfileName(boolean notifyUser) {
        if (etUsername == null || !isAdded() || getContext() == null) {
            return;
        }
        String newName = etUsername.getText() == null
                ? "" : etUsername.getText().toString().trim();
        if (newName.isEmpty()) {
            newName = PeerNameGenerator.generateName(requireContext(), endpointId);
            etUsername.setText(newName);
        }
        SharedPreferences sp = requireContext().getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String savedName = sp.getString(SettingsActivity.KEY_USERNAME, "");
        if (newName.equals(savedName)) {
            return;
        }
        sp.edit().putString(SettingsActivity.KEY_USERNAME, newName).apply();
        IrohNode.getInstance().broadcastProfile();
        if (notifyUser) {
            Toast.makeText(requireContext(), R.string.profile_saved,
                    Toast.LENGTH_SHORT).show();
        }
        updateAvatar(newName, endpointId);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etUsername = view.findViewById(R.id.et_username);
        ivAvatar = view.findViewById(R.id.iv_avatar);
        ivAvatarFrame = view.findViewById(R.id.iv_avatar_frame);
        tvReputation = view.findViewById(R.id.tv_reputation);
        refreshReputation();
        if (tvReputation != null) {
            tvReputation.setOnClickListener(v -> showReputationInfo());
        }

        tabChats = view.findViewById(R.id.tab_chats);
        tvPeerStatus = view.findViewById(R.id.tv_peer_status);
        tvRelayStatus = view.findViewById(R.id.tv_relay_status);
        pagerChats = view.findViewById(R.id.pager_chats);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        pageMyChats = inflater.inflate(R.layout.page_profile_my_chats, pagerChats, false);
        pageOnline = inflater.inflate(R.layout.page_profile_online, pagerChats, false);

        emptyState = pageMyChats.findViewById(R.id.empty_state);
        tvChatEmpty = pageMyChats.findViewById(R.id.tv_chat_empty);
        tvChatEmptyHint = pageMyChats.findViewById(R.id.tv_chat_empty_hint);
        peersRecycler = pageMyChats.findViewById(R.id.rv_chat_peers);
        peersRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        onlineRecycler = pageOnline.findViewById(R.id.rv_online_peers);
        onlineRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        tvOnlineEmpty = pageOnline.findViewById(R.id.tv_online_empty);
        chatDatabase = ChatDatabase.getInstance(requireContext());
        peersAdapter = new PeerAdapter();
        peersRecycler.setAdapter(peersAdapter);
        onlineAdapter = new PeerAdapter();
        onlineRecycler.setAdapter(onlineAdapter);

        pagerChats.setAdapter(new ProfilePagerAdapter(pageMyChats, pageOnline));
        if (tabChats != null) {
            tabMediator = new TabLayoutMediator(tabChats, pagerChats,
                    (tab, position) -> tab.setText(position == 1
                            ? R.string.profile_tab_online
                            : R.string.profile_tab_my_chats));
            tabMediator.attach();
        }

        etSearchUsername = view.findViewById(R.id.et_search_username);
        tvSearchResult = view.findViewById(R.id.tv_search_result);
        MaterialButton btnRefresh = view.findViewById(R.id.btn_refresh_peers);
        MaterialButton btnSearch = view.findViewById(R.id.btn_search_username);

        endpointId = IrohNode.getInstance().getEndpointId();
        if (TextUtils.isEmpty(endpointId)) {
            endpointId = IrohNode.getInstance().getSelfEndpointId();
        }
        if (TextUtils.isEmpty(endpointId)) {
            endpointId = "";
        }

        SharedPreferences sp = requireContext().getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String savedUsername = sp.getString(SettingsActivity.KEY_USERNAME, "");
        if (TextUtils.isEmpty(savedUsername)) {
            savedUsername = PeerNameGenerator.generateName(requireContext(), endpointId);
        }
        etUsername.setText(savedUsername);
        updateAvatar(savedUsername, endpointId);
        etUsername.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveProfileName(true);
                etUsername.clearFocus();
                return true;
            }
            return false;
        });
        etUsername.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveProfileName(false);
            }
        });

        View.OnClickListener profileSettingsClick = v -> showProfileSettings();
        View avatarContainer = view.findViewById(R.id.fl_avatar);
        if (avatarContainer != null) {
            avatarContainer.setOnClickListener(profileSettingsClick);
        } else {
            ivAvatar.setOnClickListener(profileSettingsClick);
        }
        View btnProfileSettings = view.findViewById(R.id.btn_profile_settings);
        if (btnProfileSettings != null) {
            btnProfileSettings.setOnClickListener(profileSettingsClick);
        }
        View btnOpenNews = view.findViewById(R.id.btn_open_news);
        if (btnOpenNews != null) {
            btnOpenNews.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), NewsActivity.class)));
        }

        btnRefresh.setOnClickListener(v -> {
            v.setEnabled(false);
            renderPeerList();
            irohNode.refreshKnownPeersViaPex(() -> handler.post(() -> {
                renderPeerList();
                v.setEnabled(true);
            }));
        });
        btnSearch.setOnClickListener(v -> searchByUsername());
        View btnCopyMyId = view.findViewById(R.id.btn_copy_my_id);
        if (btnCopyMyId != null) {
            btnCopyMyId.setOnClickListener(v -> copyMyEndpointId());
        }
        etSearchUsername.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchByUsername();
                return true;
            }
            return false;
        });

        irohNode.addOnPeerSearchListener(searchListener);
        irohNode.addOnPeerDiscoveredListener(peerDiscoveredListener);
        irohNode.start(requireContext().getApplicationContext());
        irohNode.startDiscovery();
        statusStartedAt = SystemClock.elapsedRealtime();
        renderPeerList();
        handler.post(peersRefresh);
    }



    private void openChatWith(String peerEndpointId) {
        String target = peerEndpointId == null ? "" : peerEndpointId.trim();
        if (TextUtils.isEmpty(target)) {
            Toast.makeText(requireContext(), R.string.profile_endpoint_invalid,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final android.app.Activity host = getActivity();
        if (host == null) {
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        final IrohNode node = irohNode;
        node.start(appContext);
        node.startDiscovery();
        new Thread(() -> {
            try {
                node.resolvePeerByEndpointId(target);
                host.runOnUiThread(() -> {
                    if (host.isFinishing()) {
                        return;
                    }
                    Intent intent = new Intent(host, ChatActivity.class);
                    intent.putExtra(ChatActivity.EXTRA_PEER_ID, target);
                    host.startActivity(intent);
                });
            } catch (IrohNode.PeerNotFoundException e) {
                host.runOnUiThread(() ->
                        Toast.makeText(appContext, e.getMessage(), Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                host.runOnUiThread(() ->
                        Toast.makeText(appContext, appContext.getString(
                                        R.string.profile_resolve_error,
                                        t.getMessage() == null ? t.toString() : t.getMessage()),
                                Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void copyMyEndpointId() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        refreshEndpointId();
        String id = endpointId;
        if (TextUtils.isEmpty(id)) {
            id = irohNode.getSelfEndpointId();
        }
        if (TextUtils.isEmpty(id)) {
            id = irohNode.getEndpointId();
        }
        if (TextUtils.isEmpty(id)) {
            Toast.makeText(requireContext(), R.string.profile_endpoint_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) requireContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Endpoint ID", id));
        }
        Toast.makeText(requireContext(), R.string.profile_endpoint_copied,
                Toast.LENGTH_SHORT).show();
    }

    private void searchByUsername() {
        if (etSearchUsername == null || tvSearchResult == null) {
            return;
        }
        String query = etSearchUsername.getText() == null
                ? "" : etSearchUsername.getText().toString().trim();
        if (TextUtils.isEmpty(query)) {
            cancelEndpointSearch();
            tvSearchResult.setVisibility(View.VISIBLE);
            tvSearchResult.setText(R.string.profile_enter_nickname);
            return;
        }

        if (IrohNode.isValidEndpointId(query)) {
            searchByEndpointId(query);
            return;
        }
        cancelEndpointSearch();

        AddressBook.Contact contact = AddressBook.findByUsername(requireContext(), query);
        if (contact == null) {
            for (String peer : irohNode.getKnownPeers()) {
                if (irohNode.getPeerDisplayName(peer).equalsIgnoreCase(query)) {
                    contact = new AddressBook.Contact(irohNode.getPeerDisplayName(peer), peer);
                    break;
                }
            }
        }
        tvSearchResult.setVisibility(View.VISIBLE);
        if (contact == null) {
            int sent = irohNode.requestPeerByUsername(query);
            tvSearchResult.setText(sent > 0
                    ? getString(R.string.profile_searching)
                    : getString(R.string.profile_not_found));
            return;
        }

        clearSearchAfterFound();
    }

    private void searchByEndpointId(String id) {
        if (etSearchUsername == null || tvSearchResult == null) {
            return;
        }
        final String target = id == null ? "" : id.trim();
        if (!IrohNode.isValidEndpointId(target)) {
            cancelEndpointSearch();
            tvSearchResult.setVisibility(View.VISIBLE);
            tvSearchResult.setText(R.string.profile_endpoint_invalid);
            return;
        }

        cancelEndpointSearch();
        pendingEndpointSearchId = target;
        tvSearchResult.setVisibility(View.VISIBLE);
        tvSearchResult.setText(R.string.profile_endpoint_searching);
        startEndpointSearchAttempt(target, 1);
    }

    private void startEndpointSearchAttempt(String target, int attempt) {
        if (etSearchUsername == null || tvSearchResult == null) {
            return;
        }
        if (!target.equals(pendingEndpointSearchId)) {
            return;
        }
        tvSearchResult.setVisibility(View.VISIBLE);
        tvSearchResult.setText(R.string.profile_endpoint_searching);

        final IrohNode node = irohNode;
        new Thread(() -> {
            boolean found = false;
            int pexPeers = 0;
            try {
                pexPeers = node.requestPeerByEndpointId(target);
                Log.i("ProfileAndChat", "Endpoint search attempt " + attempt
                        + ": PEX sent to " + pexPeers + " peer(s) for " + target);
            } catch (Throwable t) {
                Log.w("ProfileAndChat", "Endpoint search attempt " + attempt
                        + ": PEX failed: " + t);
            }
            try {
                node.resolvePeerByEndpointId(target);
                found = true;
            } catch (IrohNode.PeerNotFoundException e) {
                Log.i("ProfileAndChat", "Endpoint search attempt " + attempt
                        + ": not resolved by Pkarr/Relay: " + target);
            } catch (Throwable t) {
                Log.w("ProfileAndChat", "Endpoint search attempt " + attempt
                        + ": resolve failed: " + t);
            }

            final boolean resolved = found;
            final int pexCount = pexPeers;
            if (resolved) {
                handler.post(() -> {
                    if (target.equals(pendingEndpointSearchId)) {
                        showEndpointFound();
                    }
                });
                return;
            }

            if (attempt < ENDPOINT_SEARCH_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(ENDPOINT_SEARCH_RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                handler.post(() -> {
                    if (target.equals(pendingEndpointSearchId)) {
                        startEndpointSearchAttempt(target, attempt + 1);
                    }
                });
                return;
            }

            handler.post(() -> {
                if (!target.equals(pendingEndpointSearchId)) {
                    return;
                }
                if (pexCount == 0) {
                    showEndpointNotFound(target);
                } else {
                    scheduleEndpointSearchTimeout(target);
                }
            });
        }, "endpoint-search-" + attempt).start();
    }

    private void scheduleEndpointSearchTimeout(String target) {
        if (endpointSearchTimeout != null) {
            handler.removeCallbacks(endpointSearchTimeout);
            endpointSearchTimeout = null;
        }
        endpointSearchTimeout = () -> {
            if (target.equals(pendingEndpointSearchId)) {
                showEndpointNotFound(target);
            }
        };
        handler.postDelayed(endpointSearchTimeout, ENDPOINT_SEARCH_TIMEOUT_MS);
    }

    private void showEndpointFound() {
        if (etSearchUsername == null || tvSearchResult == null) {
            return;
        }
        cancelEndpointSearch();
        clearSearchAfterFound();
    }

    private void showEndpointNotFound(String id) {
        if (etSearchUsername == null || tvSearchResult == null) {
            return;
        }
        String current = etSearchUsername.getText() == null
                ? "" : etSearchUsername.getText().toString().trim();
        if (!id.equalsIgnoreCase(current)) {
            return;
        }
        cancelEndpointSearch();
        tvSearchResult.setVisibility(View.VISIBLE);
        tvSearchResult.setText(R.string.profile_endpoint_not_found);
    }

    private void cancelEndpointSearch() {
        if (endpointSearchTimeout != null) {
            handler.removeCallbacks(endpointSearchTimeout);
            endpointSearchTimeout = null;
        }
        pendingEndpointSearchId = null;
    }

    private void clearSearchAfterFound() {
        if (etSearchUsername != null) {
            etSearchUsername.setText("");
            etSearchUsername.clearFocus();
        }
        if (tvSearchResult != null) {
            tvSearchResult.setText("");
            tvSearchResult.setVisibility(View.GONE);
        }
        renderPeerList();
    }

    private void applySearchResult(String query, String endpointId, String username) {
        if (etSearchUsername == null || tvSearchResult == null) {
            return;
        }
        String current = etSearchUsername.getText() == null
                ? "" : etSearchUsername.getText().toString().trim();
        if (TextUtils.isEmpty(current) || !current.equalsIgnoreCase(query)) {
            return;
        }
        if (IrohNode.isValidEndpointId(current)) {
            if (current.equalsIgnoreCase(endpointId)) {
                showEndpointFound();
            }
            return;
        }
        clearSearchAfterFound();
    }

    private void refreshEndpointId() {
        String id = irohNode.getEndpointId();
        if (TextUtils.isEmpty(id)) {
            id = irohNode.getSelfEndpointId();
        }
        if (TextUtils.isEmpty(id)) {
            return;
        }
        if (id.equals(endpointId)) {
            return;
        }
        endpointId = id;
        String name = etUsername == null || etUsername.getText() == null
                ? "" : etUsername.getText().toString().trim();
        updateAvatar(name, id);
    }

    private void renderPeerList() {
        if (peersAdapter == null || onlineAdapter == null || getContext() == null) {
            return;
        }

        refreshEndpointId();
        String self = irohNode.getSelfEndpointId();
        List<String> connected = new ArrayList<>();
        for (String peer : irohNode.getConnectedPeers()) {
            if (IrohNode.isValidEndpointId(peer) && !peer.equals(self)) {
                connected.add(peer);
            }
        }
        List<String> chats = new ArrayList<>();
        if (chatDatabase != null) {
            for (String peer : chatDatabase.getConversationPeerIds()) {
                if (peer != null && !peer.isEmpty() && !peer.equals(self)
                        && !chats.contains(peer)) {
                    chats.add(peer);
                }
            }
        }
        List<String> online = new ArrayList<>();
        for (String peer : connected) {
            if (!chats.contains(peer)) {
                online.add(peer);
            }
        }

        peersAdapter.submit(chats, connected);
        onlineAdapter.submit(online, connected);
        boolean empty = chats.isEmpty();
        boolean onlineEmpty = online.isEmpty();
        int peerCount = chats.size() + online.size();
        boolean direct = irohNode.hasActiveDirectP2PConnection();
        boolean relayActive = irohNode.isRelayConnected();
        boolean connecting = SystemClock.elapsedRealtime() - statusStartedAt
                < CONNECTING_STATUS_WINDOW_MS;

        if (tvPeerStatus != null) {
            if (direct) {
                tvPeerStatus.setText(R.string.profile_status_direct);
            } else if (relayActive) {
                tvPeerStatus.setText(irohNode.isPublicN0Relay()
                        ? R.string.profile_status_relay_n0
                        : R.string.profile_status_relay);
            } else if (peerCount > 0) {
                tvPeerStatus.setText(
                        getString(R.string.profile_peers_found_format, peerCount));
            } else if (connecting) {
                tvPeerStatus.setText(R.string.profile_status_connecting);
            } else {
                tvPeerStatus.setText(R.string.profile_network_searching);
            }
        }
        if (tvRelayStatus != null) {
            tvRelayStatus.setVisibility(relayActive && irohNode.isPublicN0Relay()
                    ? View.VISIBLE : View.GONE);
        }
        if (empty && getContext() != null) {
            boolean cellular = NetworkType.CELLULAR.equals(
                    NetworkType.current(requireContext()));
            if (tvChatEmpty != null) {
                tvChatEmpty.setText(R.string.profile_no_contacts);
            }
            if (tvChatEmptyHint != null) {
                if (!cellular) {
                    tvChatEmptyHint.setText(R.string.profile_no_contacts_hint);
                } else if (relayActive) {
                    tvChatEmptyHint.setText(R.string.profile_no_contacts_hint);
                } else if (connecting) {
                    tvChatEmptyHint.setText(R.string.profile_bootstrap_hint);
                } else {
                    tvChatEmptyHint.setText(R.string.profile_network_searching);
                }
            }
        }
        if (emptyState != null) {
            emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (peersRecycler != null) {
            peersRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (tvOnlineEmpty != null) {
            tvOnlineEmpty.setVisibility(onlineEmpty ? View.VISIBLE : View.GONE);
        }
        if (onlineRecycler != null) {
            onlineRecycler.setVisibility(onlineEmpty ? View.GONE : View.VISIBLE);
        }
    }

    private static final class ProfilePagerAdapter extends RecyclerView.Adapter<ProfilePagerAdapter.PageHolder> {
        private final View[] pages;

        ProfilePagerAdapter(View chats, View online) {
            this.pages = new View[]{chats, online};
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PageHolder(pages[viewType]);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            // static pages
        }

        @Override
        public int getItemCount() {
            return pages.length;
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        static final class PageHolder extends RecyclerView.ViewHolder {
            PageHolder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }

    private final class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.PeerViewHolder> {

        private final SimpleDateFormat timeFormat =
                new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final List<String> items = new ArrayList<>();
        private final List<String> connectedPeers = new ArrayList<>();

        void submit(List<String> peers, List<String> connected) {
            if (items.equals(peers) && connectedPeers.equals(connected)) {
                return;
            }
            items.clear();
            items.addAll(peers);
            connectedPeers.clear();
            connectedPeers.addAll(connected);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public PeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_peer, parent, false);
            return new PeerViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PeerViewHolder holder, int position) {
            String peer = items.get(position);
            boolean online = connectedPeers.contains(peer);
            String displayName = irohNode.getPeerDisplayName(peer);

            holder.tvName.setText(displayName);
            holder.tvInfo.setText(online
                    ? R.string.profile_peer_online
                    : R.string.profile_peer_offline);

            ChatDatabase.Message last = chatDatabase == null
                    ? null : chatDatabase.getLastDisplayMessage(peer);
            if (last == null) {
                holder.tvLast.setText(R.string.profile_no_messages);
                holder.tvTime.setText("");
            } else {
                String preview;
                if (last.isAudio()) {
                    preview = getString(R.string.chat_voice_message);
                } else if (ChatActivity.isTechnicalChatText(last.text)) {
                    preview = "";
                } else {
                    preview = last.text == null ? "" : last.text.trim().replace('\n', ' ');
                }
                if (preview.isEmpty()) {
                    preview = getString(R.string.profile_no_messages);
                } else if (last.isOutgoing()) {
                    preview = getString(R.string.chat_self) + ": " + preview;
                }
                holder.tvLast.setText(preview);
                holder.tvTime.setText(timeFormat.format(new Date(last.timestamp)));
            }

            int unread = UnreadCounter.getInstance(holder.itemView.getContext())
                    .getUnread(peer);
            if (unread > 0) {
                holder.tvUnread.setVisibility(View.VISIBLE);
                holder.tvUnread.setText(unread > 99 ? "99+" : String.valueOf(unread));
            } else {
                holder.tvUnread.setVisibility(View.GONE);
            }

            boolean relay = online && irohNode.isRelayPeer(peer);
            holder.tvRelay.setVisibility(relay ? View.VISIBLE : View.GONE);
            holder.dot.setBackgroundResource(online
                    ? R.drawable.bg_status_dot
                    : R.drawable.bg_status_dot_red);
            holder.tvAvatar.setText(getInitials(displayName));
            GradientDrawable avatarBackground = new GradientDrawable();
            avatarBackground.setShape(GradientDrawable.OVAL);
            avatarBackground.setColor(colorFromSeed(peer));
            holder.tvAvatar.setBackground(avatarBackground);
            CosmeticManager.applyFrame(holder.ivPeerFrame, irohNode.getPeerFrame(peer));
            holder.itemView.setOnClickListener(v -> openChatWith(peer));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class PeerViewHolder extends RecyclerView.ViewHolder {
            final TextView tvAvatar;
            final ImageView ivPeerFrame;
            final TextView tvName;
            final TextView tvInfo;
            final TextView tvLast;
            final TextView tvTime;
            final TextView tvUnread;
            final TextView tvRelay;
            final View dot;

            PeerViewHolder(@NonNull View itemView) {
                super(itemView);
                tvAvatar = itemView.findViewById(R.id.tv_peer_avatar);
                ivPeerFrame = itemView.findViewById(R.id.iv_peer_frame);
                tvName = itemView.findViewById(R.id.tv_peer_id);
                tvInfo = itemView.findViewById(R.id.tv_peer_info);
                tvLast = itemView.findViewById(R.id.tv_peer_last);
                tvTime = itemView.findViewById(R.id.tv_peer_time);
                tvUnread = itemView.findViewById(R.id.tv_peer_unread);
                tvRelay = itemView.findViewById(R.id.tv_peer_relay);
                dot = itemView.findViewById(R.id.view_peer_dot);
            }
        }
    }

    private void pickAvatar() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        try {
            if (intent.resolveActivity(requireContext().getPackageManager()) != null) {
                startActivityForResult(intent, REQUEST_PICK_AVATAR);
                return;
            }
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("image/*");
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(
                    Intent.createChooser(fallback, getString(R.string.avatar_choose_from_gallery)),
                    REQUEST_PICK_AVATAR);
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.profile_gallery_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void saveAvatar(Uri uri) {
        final Context appContext = requireContext().getApplicationContext();
        final Activity host = getActivity();
        Toast.makeText(appContext, R.string.profile_saving_avatar, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean saved = AvatarStore.save(appContext, uri);
            if (host == null) {
                return;
            }
            host.runOnUiThread(() -> {
                if (!isAdded() || ivAvatar == null) {
                    return;
                }
                if (!saved) {
                    Toast.makeText(requireContext(), R.string.profile_avatar_save_error,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String name = etUsername == null || etUsername.getText() == null
                        ? "" : etUsername.getText().toString().trim();
                updateAvatar(name, endpointId);
                Toast.makeText(requireContext(), R.string.avatar_custom_applied,
                        Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PREVIEW_COSMETIC) {
            if (resultCode == Activity.RESULT_OK) {
                String name = etUsername == null || etUsername.getText() == null
                        ? "" : etUsername.getText().toString().trim();
                updateAvatar(name, endpointId);
                if (cosmeticsDialogContent != null) {
                    renderCosmeticAchievements(cosmeticsDialogContent);
                }
            }
            return;
        }
        if (requestCode != REQUEST_PICK_AVATAR || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        saveAvatar(data.getData());
    }

    private void updateAvatar(String name, String seed) {
        if (ivAvatar == null || !isAdded()) {
            return;
        }
        applyProfileLook(ivAvatar, ivAvatarFrame, name, seed);
    }

    private void applyProfileLook(ImageView avatarView, ImageView frameOverlay,
                                  String name, String seed) {
        if (avatarView == null || !isAdded()) {
            return;
        }
        Context context = requireContext();
        CosmeticManager.applyFrame(frameOverlay, CosmeticManager.getEquippedFrame(context));
        Bitmap custom = AvatarStore.load(context);
        if (custom != null) {
            CosmeticManager.clearAvatarDecor(avatarView);
            avatarView.setImageBitmap(custom);
            return;
        }
        String avatarId = CosmeticManager.getEquippedAvatar(context);
        if (!TextUtils.isEmpty(avatarId)) {
            CosmeticManager.applyAvatar(avatarView, avatarId);
            return;
        }
        CosmeticManager.clearAvatarDecor(avatarView);
        String s = TextUtils.isEmpty(seed) ? name : seed;
        if (TextUtils.isEmpty(s)) {
            s = "triger";
        }
        avatarView.setImageBitmap(generateAvatar(s));
    }

    private void showCosmeticsAchievements() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_cosmetics_shop, null);
        cosmeticsDialogContent = content;
        renderCosmeticAchievements(content);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cosmetics_achievements)
                .setView(content)
                .setNegativeButton(R.string.action_close, null)
                .setOnDismissListener(d -> cosmeticsDialogContent = null)
                .show();
    }

    private void renderCosmeticAchievements(View dialogContent) {
        if (!isAdded() || dialogContent == null || getContext() == null) {
            return;
        }
        Context context = requireContext();
        LinearLayout container = dialogContent.findViewById(R.id.ll_cosmetics_items);
        if (container == null) {
            return;
        }
        TextView progressView = dialogContent.findViewById(R.id.tv_cosmetics_progress);
        TextView summaryView = dialogContent.findViewById(R.id.tv_cosmetics_summary);
        container.removeAllViews();

        int minedBlocks = KeyManager.getTotalBlocksSigned(context);
        CosmeticManager.checkUnlocks(context, minedBlocks,
                CosmeticManager.getMiningTimeMs(context));
        if (progressView != null) {
            progressView.setText(buildCosmeticsProgressText(context, minedBlocks));
        }
        if (summaryView != null) {
            summaryView.setText(buildAchievementsSummary(context));
        }

        List<CosmeticManager.Item> ordered = new ArrayList<>();
        List<CosmeticManager.Item> achievements = CosmeticManager.getAchievementItems();
        for (CosmeticManager.Item item : achievements) {
            if (CosmeticManager.hasItem(context, item.id)) {
                ordered.add(item);
            }
        }
        for (CosmeticManager.Item item : achievements) {
            if (!CosmeticManager.hasItem(context, item.id)) {
                ordered.add(item);
            }
        }

        LayoutInflater inflater = LayoutInflater.from(context);
        int iconSizePx = Math.round(42f * getResources().getDisplayMetrics().density);
        long miningTimeMs = CosmeticManager.getMiningTimeMs(context);
        for (CosmeticManager.Item item : ordered) {
            View row = inflater.inflate(R.layout.item_cosmetic, container, false);
            View icon = row.findViewById(R.id.fl_cosmetic_icon);
            ImageView iconImage = row.findViewById(R.id.iv_cosmetic_icon);
            ImageView iconFrame = row.findViewById(R.id.iv_cosmetic_frame);
            TextView name = row.findViewById(R.id.tv_cosmetic_name);
            TextView status = row.findViewById(R.id.tv_cosmetic_price);
            ProgressBar progress = row.findViewById(R.id.pb_cosmetic_progress);
            TextView progressText = row.findViewById(R.id.tv_cosmetic_progress);
            MaterialButton action = row.findViewById(R.id.btn_cosmetic_action);

            String itemName = getString(item.nameRes);
            name.setText(itemName);
            icon.setContentDescription(itemName);

            boolean unlocked = CosmeticManager.hasItem(context, item.id);

            if (item.isFrame()) {
                iconImage.setImageDrawable(null);
                iconImage.setBackgroundResource(0);
                iconImage.clearColorFilter();
                iconFrame.setImageDrawable(null);
                icon.setBackgroundResource(R.drawable.bg_cosmetic_disc);
                if (unlocked) {
                    CosmeticManager.applyFrame(iconFrame, item.id);
                } else {
                    icon.setForeground(CosmeticManager.createLockedDrawable(
                            context, item.id, iconSizePx));
                }
            } else if (unlocked) {
                icon.setForeground(null);
                CosmeticManager.applyAvatar(iconImage, item.id);
            } else {
                icon.setForeground(null);
                iconImage.setImageResource(CosmeticManager.avatarIconRes(item.id));
                iconImage.setColorFilter(CosmeticManager.LOCKED_TINT);
                iconImage.setBackground(CosmeticManager.createLockedDrawable(
                        context, item.id, iconSizePx));
                CosmeticManager.applyCircleClip(iconImage);
            }

            boolean equipped = item.isFrame()
                    ? CosmeticManager.isFrameEquipped(context, item.id)
                    : CosmeticManager.isAvatarEquipped(context, item.id);
            if (unlocked) {
                status.setText(R.string.cosmetics_unlocked);
                hideCosmeticProgress(status, progress, progressText);
            } else {
                showCosmeticProgress(item, minedBlocks, miningTimeMs,
                        status, progress, progressText);
            }

            if (equipped) {
                action.setText(R.string.cosmetics_equipped);
                action.setEnabled(false);
            } else {
                action.setText(R.string.cosmetics_try_on);
                action.setEnabled(true);
                action.setOnClickListener(v -> {
                    Intent intent = new Intent(context, PreviewActivity.class);
                    intent.putExtra(PreviewActivity.EXTRA_ITEM_ID, item.id);
                    startActivityForResult(intent, REQUEST_PREVIEW_COSMETIC);
                });
            }
            container.addView(row);
        }
    }

    private String buildCosmeticsProgressText(Context context, int minedBlocks) {
        CosmeticManager.Item next = CosmeticManager.getNextBlockMilestone(minedBlocks);
        if (next == null) {
            return getString(R.string.cosmetics_progress_all_blocks, minedBlocks);
        }
        int goalRes = next.goalNameRes != 0 ? next.goalNameRes : next.nameRes;
        return getString(R.string.cosmetics_progress_blocks, minedBlocks,
                next.blocksRequired, getString(goalRes));
    }

    private void showCosmeticProgress(CosmeticManager.Item item, int minedBlocks,
                                      long miningTimeMs, TextView status,
                                      ProgressBar progress, TextView progressText) {
        long current;
        long required;
        if (item.blocksRequired > 0) {
            current = Math.max(0, minedBlocks);
            required = item.blocksRequired;
        } else if (item.miningTimeRequiredMs > 0) {
            current = Math.max(0L, miningTimeMs) / CosmeticManager.ONE_HOUR_MS;
            required = item.miningTimeRequiredMs / CosmeticManager.ONE_HOUR_MS;
        } else {
            current = 0;
            required = 0;
        }
        if (status != null) {
            status.setVisibility(View.GONE);
        }
        if (progressText != null) {
            progressText.setText(getString(R.string.achievement_progress,
                    (int) Math.min(current, Integer.MAX_VALUE),
                    (int) Math.min(required, Integer.MAX_VALUE)));
            progressText.setVisibility(View.VISIBLE);
        }
        if (progress != null) {
            if (required <= 0) {
                progress.setProgress(0);
            } else {
                int percent = (int) Math.min(100L, current * 100L / required);
                progress.setProgress(percent);
            }
            progress.setVisibility(View.VISIBLE);
        }
    }

    private void hideCosmeticProgress(TextView status, ProgressBar progress,
                                      TextView progressText) {
        if (status != null) {
            status.setVisibility(View.VISIBLE);
        }
        if (progress != null) {
            progress.setVisibility(View.GONE);
        }
        if (progressText != null) {
            progressText.setVisibility(View.GONE);
        }
    }

    private Bitmap generateAvatar(String seed) {
        int size = 256;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int color = colorFromSeed(seed);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(size * 0.35f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        String initials = getInitials(seed);
        canvas.drawText(initials, size / 2f, size / 2f + textPaint.getTextSize() / 3f, textPaint);
        return bitmap;
    }

    private int colorFromSeed(String seed) {
        byte[] hash = sha256(seed);
        int r = hash[0] & 0xFF;
        int g = hash[1] & 0xFF;
        int b = hash[2] & 0xFF;
        r = (r % 200) + 55;
        g = (g % 200) + 55;
        b = (b % 200) + 55;
        return Color.rgb(r, g, b);
    }

    private String getInitials(String seed) {
        String s = seed.replaceAll("[^A-Za-zА-Яа-я0-9]", " ").trim();
        if (s.isEmpty()) {
            return "TR";
        }
        String[] parts = s.split("\\s+");
        if (parts.length >= 2) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.ROOT);
        }
        if (parts[0].length() >= 2) {
            return parts[0].substring(0, 2).toUpperCase(Locale.ROOT);
        }
        return (parts[0].substring(0, 1) + "X").toUpperCase(Locale.ROOT);
    }

    private byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[]{(byte) 100, (byte) 150, (byte) 200};
        }
    }

    @Override
    public void onPause() {
        saveProfileName(false);
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshReputation();
        renderPeerList();
    }

    @Override
    public void onDestroyView() {
        irohNode.removeOnPeerSearchListener(searchListener);
        irohNode.removeOnPeerDiscoveredListener(peerDiscoveredListener);
        cancelEndpointSearch();
        handler.removeCallbacksAndMessages(null);
        if (tabMediator != null) {
            tabMediator.detach();
            tabMediator = null;
        }
        tabChats = null;
        if (pagerChats != null) {
            pagerChats.setAdapter(null);
        }
        pagerChats = null;
        etUsername = null;
        ivAvatar = null;
        ivAvatarFrame = null;
        cosmeticsDialogContent = null;
        tvReputation = null;
        tvPeerStatus = null;
        tvRelayStatus = null;
        pageMyChats = null;
        pageOnline = null;
        emptyState = null;
        tvChatEmpty = null;
        tvChatEmptyHint = null;
        if (peersRecycler != null) {
            peersRecycler.setAdapter(null);
        }
        peersRecycler = null;
        peersAdapter = null;
        if (onlineRecycler != null) {
            onlineRecycler.setAdapter(null);
        }
        onlineRecycler = null;
        onlineAdapter = null;
        tvOnlineEmpty = null;
        etSearchUsername = null;
        tvSearchResult = null;
        super.onDestroyView();
    }
}
