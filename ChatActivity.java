package com.triger.poc;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_PEER_ID = "peer_id";

    private String peerId = "";
    private RecyclerView recyclerMessages;
    private EditText etInput;
    private MessageAdapter adapter;
    private TextView peerLabelView;
    private final IrohNode irohNode = IrohNode.getInstance();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        peerId = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_PEER_ID);
        if (peerId == null) {
            peerId = "";
        }

        TextView title = findViewById(R.id.tv_chat_title);
        peerLabelView = findViewById(R.id.tv_chat_peer);
        title.setText("Чат Iroh");
        renderPeerLabel("");

        recyclerMessages = findViewById(R.id.recycler_messages);
        etInput = findViewById(R.id.et_chat_input);
        MaterialButton btnSend = findViewById(R.id.btn_chat_send);

        adapter = new MessageAdapter(peerId);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recyclerMessages.setLayoutManager(lm);
        recyclerMessages.setAdapter(adapter);

        btnSend.setOnClickListener(v -> sendCurrent());

        irohNode.setOnMessageListener(this::onIncomingMessage);
        if (!TextUtils.isEmpty(peerId)) {
            connectToPeer();
        }
    }

    private void connectToPeer() {
        renderPeerLabel(" · подключение…");
        irohNode.connectToPeer(peerId, new IrohNode.ConnectCallback() {
            @Override
            public void onSuccess(String endpointId) {
                runOnUiThread(() -> renderPeerLabel(" · подключено"));
            }

            @Override
            public void onError(String endpointId, String message) {
                runOnUiThread(() -> renderPeerLabel(" · ошибка: " + message));
            }
        });
    }

    private void renderPeerLabel(String status) {
        if (peerLabelView == null) {
            return;
        }
        String shortId = peerId.length() > 16 ? peerId.substring(0, 16) + "…" : peerId;
        if (shortId.isEmpty()) {
            shortId = "не выбран";
        }
        peerLabelView.setText("Собеседник: " + shortId + status);
    }

    private void sendCurrent() {
        String text = etInput.getText() == null ? "" : etInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        if (TextUtils.isEmpty(peerId)) {
            Toast.makeText(this, "Не выбран собеседник", Toast.LENGTH_SHORT).show();
            return;
        }
        irohNode.sendMessage(peerId, text);
        adapter.add(new ChatMessage(peerId, text, true, System.currentTimeMillis()));
        etInput.setText("");
        scrollToBottom();
    }

    private void onIncomingMessage(String from, String text) {
        runOnUiThread(() -> {
            if (TextUtils.isEmpty(peerId)) {
                peerId = from == null ? "" : from;
                adapter = new MessageAdapter(peerId);
                recyclerMessages.setAdapter(adapter);
                renderPeerLabel(" · подключено");
            }
            if (from == null || from.isEmpty() || from.equals(peerId)) {
                adapter.add(new ChatMessage(from, text, false, System.currentTimeMillis()));
                scrollToBottom();
            } else {
                Toast.makeText(this, "Сообщение от " + shorten(from),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String shorten(String s) {
        return s != null && s.length() > 12 ? s.substring(0, 12) + "…" : s;
    }

    private void scrollToBottom() {
        if (adapter.getItemCount() > 0) {
            recyclerMessages.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    @Override
    protected void onDestroy() {
        irohNode.setOnMessageListener(null);
        super.onDestroy();
    }

    // ---------- Message model + adapter ----------

    static final class ChatMessage {
        final String peerId;
        final String text;
        final boolean outgoing;
        final long timestamp;

        ChatMessage(String peerId, String text, boolean outgoing, long timestamp) {
            this.peerId = peerId;
            this.text = text;
            this.outgoing = outgoing;
            this.timestamp = timestamp;
        }
    }

    private static final class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

        private static final SimpleDateFormat FMT =
                new SimpleDateFormat("HH:mm", Locale.getDefault());

        private final List<ChatMessage> messages = new ArrayList<>();
        private final String peerId;

        MessageAdapter(String peerId) {
            this.peerId = peerId;
        }

        void add(ChatMessage msg) {
            messages.add(msg);
            notifyItemInserted(messages.size() - 1);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_message, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ChatMessage msg = messages.get(position);
            holder.text.setText(msg.text);
            holder.meta.setText((msg.outgoing ? "Вы" : "Собеседник") + " · "
                    + FMT.format(new Date(msg.timestamp)));
            holder.text.setBackgroundResource(msg.outgoing
                    ? R.drawable.bg_chat_bubble_out
                    : R.drawable.bg_chat_bubble_in);
            LinearLayout row = holder.row;
            row.setGravity(msg.outgoing ? Gravity.END : Gravity.START);
            holder.meta.setGravity(msg.outgoing ? Gravity.END : Gravity.START);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final LinearLayout row;
            final TextView text;
            final TextView meta;

            VH(@NonNull View itemView) {
                super(itemView);
                row = (LinearLayout) itemView;
                text = itemView.findViewById(R.id.tv_msg_text);
                meta = itemView.findViewById(R.id.tv_msg_meta);
            }
        }
    }
}
