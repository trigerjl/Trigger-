package com.triger.poc;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class HelpActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageActivity.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.help_faq_container);
        addFaq(container, R.string.help_mining_q, R.string.help_mining_a);
        addFaq(container, R.string.help_exchange_q, R.string.help_exchange_a);
        addFaq(container, R.string.help_recovery_q, R.string.help_recovery_a);
        addFaq(container, R.string.help_vwap_q, R.string.help_vwap_a);
        addFaq(container, R.string.help_dispute_q, R.string.help_dispute_a);
    }

    private void addFaq(LinearLayout container, int questionRes, int answerRes) {
        View item = getLayoutInflater().inflate(R.layout.item_help_faq, container, false);
        TextView question = item.findViewById(R.id.tv_help_question);
        TextView answer = item.findViewById(R.id.tv_help_answer);
        ImageView arrow = item.findViewById(R.id.iv_help_arrow);

        question.setText(questionRes);
        answer.setText(answerRes);
        item.setContentDescription(getString(questionRes));
        item.setOnClickListener(v -> {
            boolean expand = answer.getVisibility() != View.VISIBLE;
            answer.setVisibility(expand ? View.VISIBLE : View.GONE);
            arrow.animate()
                    .rotation(expand ? 180f : 0f)
                    .setDuration(160L)
                    .start();
        });
        container.addView(item);
    }
}
