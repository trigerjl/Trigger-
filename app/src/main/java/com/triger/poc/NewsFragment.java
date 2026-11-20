package com.triger.poc;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NewsFragment extends Fragment implements NewsManager.Listener {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NewsManager newsManager;
    private RecyclerView recycler;
    private NewsAdapter adapter;
    private TextView emptyView;
    private EditText input;
    private MaterialButton publishButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_news, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        newsManager = new NewsManager(requireContext());
        recycler = view.findViewById(R.id.recycler_news);
        adapter = new NewsAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);
        emptyView = view.findViewById(R.id.news_empty);
        input = view.findViewById(R.id.et_news_text);
        publishButton = view.findViewById(R.id.btn_news_publish);
        publishButton.setOnClickListener(v -> publish());
        NewsManager.addListener(this);
        reload();
    }

    @Override
    public void onNewsChanged() {
        handler.post(this::reload);
    }

    private void reload() {
        if (newsManager == null || adapter == null) {
            return;
        }
        List<NewsItem> items = newsManager.getNews();
        adapter.submit(items);
        boolean empty = items.isEmpty();
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (recycler != null) {
            recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void publish() {
        String text = input == null || input.getText() == null
                ? "" : input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!newsManager.hasPublishFee()) {
            Toast.makeText(requireContext(), R.string.news_spam_protection,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        publishButton.setEnabled(false);
        new Thread(() -> {
            NewsItem item = newsManager.publish(text);
            handler.post(() -> {
                publishButton.setEnabled(true);
                if (item != null) {
                    if (input != null) {
                        input.setText("");
                    }
                    reload();
                    Toast.makeText(requireContext(), R.string.news_published,
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), R.string.news_spam_protection,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "news-publish").start();
    }

    @Override
    public void onDestroyView() {
        NewsManager.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        if (recycler != null) {
            recycler.setAdapter(null);
        }
        recycler = null;
        adapter = null;
        super.onDestroyView();
    }

    private final class NewsAdapter extends RecyclerView.Adapter<NewsViewHolder> {
        private final List<NewsItem> items = new ArrayList<>();
        private final SimpleDateFormat timeFormat =
                new SimpleDateFormat("d MMM · HH:mm", Locale.getDefault());

        void submit(List<NewsItem> news) {
            items.clear();
            if (news != null) {
                items.addAll(news);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public NewsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_news, parent, false);
            return new NewsViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull NewsViewHolder holder, int position) {
            NewsItem item = items.get(position);
            String nickname = item.getAuthorNickname();
            if (nickname == null || nickname.trim().isEmpty()) {
                String address = item.getAuthorAddress();
                nickname = address == null || address.length() < 12
                        ? "?" : address.substring(0, 10) + "…" + address.substring(address.length() - 6);
            }
            holder.author.setText(nickname);
            holder.text.setText(item.getText());
            holder.time.setText(timeFormat.format(new Date(item.getTimestamp())));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class NewsViewHolder extends RecyclerView.ViewHolder {
        final TextView author;
        final TextView text;
        final TextView time;

        NewsViewHolder(@NonNull View itemView) {
            super(itemView);
            author = itemView.findViewById(R.id.tv_news_author);
            text = itemView.findViewById(R.id.tv_news_text);
            time = itemView.findViewById(R.id.tv_news_time);
        }
    }
}
