package org.minimarex.expert;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minima Expert — native offline RAG over the Minima corpus. Boots the corpus + ONNX embedder off the
 * UI thread, then for each question embeds the query natively, runs hybrid retrieval, and renders the
 * cited source passages. (Generative answers via llama.cpp arrive in Phase 1.)
 */
public class MainActivity extends AppCompatActivity {

    private LinearLayout thread, composer, header;
    private ScrollView scroll;
    private EditText q;
    private Button ask;
    private TextView status, badge;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Corpus corpus;
    private Embedder embedder;
    private AiConfig aiConfig;
    private volatile boolean ready = false, busy = false;

    private static final String[] SUGGESTIONS = {
        "What are Winternitz one-time signatures and why does Minima use them?",
        "How does TxPoW work?",
        "What is a MiniDapp?",
        "How do I check my balance from the command line?"
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        header = findViewById(R.id.header);
        status = findViewById(R.id.status);
        badge = findViewById(R.id.badge);
        scroll = findViewById(R.id.threadScroll);
        thread = findViewById(R.id.thread);
        composer = findViewById(R.id.composer);
        q = findViewById(R.id.q);
        ask = findViewById(R.id.ask);

        aiConfig = new AiConfig(this);
        applyInsets();
        ask.setEnabled(false);
        ask.setOnClickListener(v -> submit());
        badge.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        boot();
        if (!aiConfig.setupSeen) startActivity(new Intent(this, SetupActivity.class));   // first-run wizard
    }

    @Override
    protected void onResume() {
        super.onResume();
        aiConfig = new AiConfig(this);   // pick up changes the setup wizard saved
        updateBadge();
    }

    /** Header clears the status bar; composer clears the nav bar; dark status-bar icons off. */
    private void applyInsets() {
        final View root = findViewById(R.id.main);
        final int headerTop = header.getPaddingTop();
        final int compBottom = composer.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // Composer clears the nav bar normally, and rides up above the soft keyboard when it opens.
            int bottom = Math.max(bars.bottom, ime.bottom);
            header.setPadding(header.getPaddingLeft(), headerTop + bars.top, header.getPaddingRight(), header.getPaddingBottom());
            composer.setPadding(composer.getPaddingLeft() + bars.left, composer.getPaddingTop(),
                    composer.getPaddingRight() + bars.right, compBottom + bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    // ---- boot ----

    private void boot() {
        status.setText("Loading the corpus…");
        io.execute(() -> {
            try {
                final Corpus c = Corpus.load(getAssets());
                ui.post(() -> status.setText("Loading the embedding model…"));
                final Embedder e = new Embedder(getAssets(), "minilm.onnx", "vocab.txt");
                e.embed("minima");   // warm up (first ORT run compiles kernels)
                corpus = c; embedder = e; ready = true;
                ui.post(this::onReady);
            } catch (Exception ex) {
                ui.post(() -> status.setText("Failed to load: " + ex.getMessage()));
            }
        });
    }

    private void onReady() {
        status.setText("Ready · " + corpus.count + " passages · offline");
        ask.setEnabled(true);
        updateBadge();
        renderWelcome();
    }

    /** Badge reflects the answer tier and is tappable to open AI settings. */
    private void updateBadge() {
        if (aiConfig.active()) { badge.setText("⚡ AI ⚙"); badge.setTextColor(ExpertDesign.ACCENT); }
        else { badge.setText("📚 offline ⚙"); badge.setTextColor(ExpertDesign.DIM); }
    }

    private void renderWelcome() {
        LinearLayout card = addBot();
        line(card, "Ask me anything about Minima", ExpertDesign.TEXT, 16f, true);
        line(card, "Answers are grounded in " + corpus.count + " passages from the protocol docs, "
                + "interviews and command reference — fully offline, with citations.", ExpertDesign.DIM, 13f, false);
        line(card, "Tap the badge (top-right) to turn on AI-written answers (online).", ExpertDesign.DIM_2, 12f, false);
        TextView tryT = line(card, "Try:", ExpertDesign.DIM, 12f, false);
        tryT.setPadding(0, dp(10), 0, dp(2));
        for (String s : SUGGESTIONS) card.addView(chip(s));
        scrollDown();
    }

    // ---- ask flow ----

    private void submit() {
        if (!ready || busy) return;
        String text = q.getText().toString().trim();
        if (text.isEmpty()) return;
        q.setText("");
        hideKeyboard();
        addUser(text);
        final LinearLayout card = addBot();
        final TextView searching = line(card, "Searching the corpus…", ExpertDesign.DIM, 14f, false);
        busy = true; ask.setEnabled(false);
        scrollDown();
        io.execute(() -> {
            try {
                final float[] qv = embedder.embed(text);
                final List<Retriever.Hit> hits = Retriever.retrieve(corpus, qv, text);
                ui.post(() -> {
                    card.removeAllViews();
                    if (aiConfig.active() && !hits.isEmpty()) {
                        renderWithAi(card, text, hits);   // streams the answer, then calls done()
                    } else {
                        renderAnswer(card, hits);
                        done();
                    }
                });
            } catch (Exception ex) {
                ui.post(() -> { searching.setText("Error: " + ex.getMessage()); done(); });
            }
        });
    }

    private void done() { busy = false; ask.setEnabled(true); scrollDown(); }

    /** AI tier: a streaming Answer panel on top, the cited Sources below it. The online model answers
     *  strictly from the retrieved passages; retrieval itself stayed fully offline. */
    private void renderWithAi(LinearLayout card, String query, List<Retriever.Hit> hits) {
        // Sources render first (instant), then the AI answer streams into a panel BELOW them — so the
        // reference docs stay on top and the screen is never blank while the answer generates.
        TextView srcHead = line(card, "Sources", ExpertDesign.DIM, 12f, true);
        srcHead.setPadding(0, 0, 0, dp(4));
        int n = 1;
        for (Retriever.Hit h : hits) card.addView(sourceCard(h, n++));

        TextView ansHead = line(card, "Answer", ExpertDesign.ACCENT, 13f, true);
        ansHead.setPadding(0, dp(14), 0, dp(4));
        final TextView ans = line(card, "…", ExpertDesign.TEXT, 15f, false);

        final StringBuilder acc = new StringBuilder();
        final String userPrompt = Prompt.build(query, hits);
        io.execute(() -> OnlineLlm.stream(aiConfig, Prompt.SYSTEM, userPrompt, new OnlineLlm.Cb() {
            @Override public void onToken(String delta) {
                acc.append(delta);
                ui.post(() -> { ans.setText(acc.toString()); scrollDown(); });
            }
            @Override public void onDone() {
                ui.post(() -> { if (acc.length() == 0) ans.setText("(the model returned no answer)"); done(); });
            }
            @Override public void onError(String message) {
                ui.post(() -> {
                    ans.setTextColor(ExpertDesign.RED);
                    ans.setText("AI error: " + message + "\n\nSee the cited passages below.");
                    done();
                });
            }
        }));
    }

    private void renderAnswer(LinearLayout card, List<Retriever.Hit> hits) {
        if (hits.isEmpty()) {
            line(card, "Nothing in the corpus matched that.", ExpertDesign.TEXT, 15f, false);
            return;
        }
        TextView head = line(card, "Most relevant passages", ExpertDesign.ACCENT, 13f, true);
        head.setPadding(0, 0, 0, dp(6));
        int n = 1;
        for (Retriever.Hit h : hits) card.addView(sourceCard(h, n++));
    }

    // ---- view builders ----

    private LinearLayout addBot() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ExpertDesign.SURFACE);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = rowParams();
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);
        thread.addView(card);
        return card;
    }

    private void addUser(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(ExpertDesign.TEXT);
        t.setTextSize(15f);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ExpertDesign.USER_BUBBLE);
        bg.setCornerRadius(dp(12));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = rowParams();
        lp.topMargin = dp(12);
        lp.leftMargin = dp(36);
        lp.gravity = Gravity.END;
        t.setLayoutParams(lp);
        thread.addView(t);
    }

    /** One retrieved passage: [n] title · match% · source, with an expandable snippet. */
    private View sourceCard(Retriever.Hit h, int n) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ExpertDesign.SURFACE_2);
        bg.setCornerRadius(dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);
        card.setBackground(bg);

        TextView head = new TextView(this);
        int pct = Math.max(0, Math.round(h.dense * 100));
        head.setText("[" + n + "]  " + h.title + "   ·   " + pct + "% match");
        head.setTextColor(ExpertDesign.ACCENT);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextSize(13f);
        card.addView(head);

        TextView src = new TextView(this);
        src.setText(h.source);
        src.setTextColor(ExpertDesign.DIM_2);
        src.setTextSize(11f);
        src.setPadding(0, dp(1), 0, dp(6));
        card.addView(src);

        final TextView body = new TextView(this);
        final String full = h.text.trim();
        final String snip = full.length() > 320 ? full.substring(0, 320).trim() + "…" : full;
        body.setText(snip);
        body.setTextColor(ExpertDesign.DIM);
        body.setTextSize(13f);
        body.setLineSpacing(dp(2), 1f);
        card.addView(body);

        if (full.length() > snip.length()) {
            final boolean[] expanded = {false};
            card.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                body.setText(expanded[0] ? full : snip);
            });
        }
        return card;
    }

    private TextView chip(final String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ExpertDesign.TEXT);
        t.setTextSize(13f);
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ExpertDesign.SURFACE_2);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), ExpertDesign.BORDER);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> { q.setText(s); submit(); });
        return t;
    }

    private TextView line(LinearLayout parent, String text, int color, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(2), 0, dp(2));
        parent.addView(t);
        return t;
    }

    // ---- helpers ----

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void scrollDown() { scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(q.getWindowToken(), 0);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (embedder != null) embedder.close();
        io.shutdownNow();
    }
}
