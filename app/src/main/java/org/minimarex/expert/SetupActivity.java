package org.minimarex.expert;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First-run (and re-openable) wizard for the online-AI tier. Walks the user to a free Groq API key —
 * a button opens the Groq console, they paste the key — then validates it with a live test request
 * before enabling AI answers. Skippable (stays in offline retrieval mode). Provider-agnostic underneath.
 */
public class SetupActivity extends AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AiConfig cfg;
    private EditText keyField, modelField, urlField;
    private TextView status, enableBtn;

    private static final String GROQ_CONSOLE = "https://console.groq.com/keys";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new AiConfig(this);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ExpertDesign.BG);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(ExpertDesign.SURFACE);
        header.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView title = new TextView(this);
        title.setText("Set up AI answers");
        title.setTextColor(ExpertDesign.ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(18f);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(ExpertDesign.DIM);
        close.setTextSize(18f);
        close.setPadding(dp(10), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> skip());
        header.addView(close);
        root.addView(header);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(24));
        sv.addView(c);
        root.addView(sv);

        para(c, "Minima Expert already searches the docs offline. To get full written answers, connect a "
                + "free AI model — about a minute.", ExpertDesign.DIM, 14f, false);

        LinearLayout s1 = card(c);
        para(s1, "1 · Get a free key", ExpertDesign.TEXT, 15f, true);
        para(s1, "Tap below and sign up (free — no card needed). Then: API Keys → Create API Key → copy it "
                + "(it starts with gsk_…).", ExpertDesign.DIM, 13f, false);
        button(s1, "Open Groq Console  ↗", true, v -> openUrl(GROQ_CONSOLE));

        LinearLayout s2 = card(c);
        para(s2, "2 · Paste your key", ExpertDesign.TEXT, 15f, true);
        keyField = field(s2, "Paste gsk_… here", cfg.key, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(s2, "Paste from clipboard", false, v -> paste());

        para(c, "Model", ExpertDesign.DIM, 12f, false);
        modelField = field(c, "model", cfg.model, InputType.TYPE_CLASS_TEXT);
        para(c, "Base URL  (advanced — any OpenAI-compatible API works)", ExpertDesign.DIM, 12f, false);
        urlField = field(c, "base url", cfg.baseUrl, InputType.TYPE_TEXT_VARIATION_URI);

        status = new TextView(this);
        status.setTextSize(13f);
        status.setPadding(0, dp(12), 0, dp(4));
        status.setVisibility(View.GONE);
        c.addView(status);

        enableBtn = button(c, "Enable AI answers", true, v -> enable());
        ((LinearLayout.LayoutParams) enableBtn.getLayoutParams()).topMargin = dp(18);
        button(c, "Use offline only for now", false, v -> skip());

        setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            header.setPadding(dp(18), dp(16) + bars.top, dp(18), dp(16));
            c.setPadding(dp(18), dp(16), dp(18), dp(24) + Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "No browser found — visit " + url, Toast.LENGTH_LONG).show(); }
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
            if (t != null && t.length() > 0) keyField.setText(t.toString().trim());
        }
    }

    /** Validate the key with a tiny live request before committing — fail fast with a clear message. */
    private void enable() {
        final AiConfig test = new AiConfig(this);
        test.baseUrl = urlField.getText().toString().trim();
        test.model = modelField.getText().toString().trim();
        test.key = keyField.getText().toString().trim();
        if (test.key.isEmpty()) { setStatus("Paste your key first.", false); return; }
        setStatus("Checking your key…", true);
        enableBtn.setEnabled(false);
        io.execute(() -> OnlineLlm.stream(test, Prompt.SYSTEM, "Reply with the single word: ready",
                new OnlineLlm.Cb() {
            @Override public void onToken(String delta) {}
            @Override public void onDone() { ui.post(() -> finishOk(test)); }
            @Override public void onError(String message) {
                ui.post(() -> { setStatus("Couldn't connect: " + message, false); enableBtn.setEnabled(true); });
            }
        }));
    }

    private void finishOk(AiConfig test) {
        cfg.baseUrl = test.baseUrl; cfg.model = test.model; cfg.key = test.key;
        cfg.enabled = true; cfg.setupSeen = true; cfg.save();
        Toast.makeText(this, "AI answers enabled  ⚡", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Keep whatever's typed, mark the wizard seen, but don't force-enable. */
    private void skip() {
        cfg.baseUrl = urlField.getText().toString().trim();
        cfg.model = modelField.getText().toString().trim();
        cfg.key = keyField.getText().toString().trim();
        cfg.setupSeen = true;
        if (cfg.key.isEmpty()) cfg.enabled = false;
        cfg.save();
        finish();
    }

    // ---- view builders (dark + orange, in code) ----

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ExpertDesign.SURFACE);
        bg.setCornerRadius(dp(12));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        c.setLayoutParams(lp);
        parent.addView(c);
        return c;
    }

    private TextView para(LinearLayout parent, String text, int color, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextColor(color); t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(3), 0, dp(3));
        parent.addView(t);
        return t;
    }

    private EditText field(LinearLayout parent, String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(ExpertDesign.DIM_2);
        e.setText(value); e.setTextColor(ExpertDesign.TEXT);
        e.setTextSize(14f); e.setInputType(inputType);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ExpertDesign.BG); bg.setCornerRadius(dp(6)); bg.setStroke(dp(1), ExpertDesign.BORDER);
        e.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        e.setLayoutParams(lp);
        parent.addView(e);
        return e;
    }

    private TextView button(LinearLayout parent, String text, boolean primary, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (primary) { bg.setColor(ExpertDesign.ACCENT); b.setTextColor(ExpertDesign.ON_ACCENT); }
        else { bg.setColor(ExpertDesign.SURFACE_2); b.setTextColor(ExpertDesign.TEXT); bg.setStroke(dp(1), ExpertDesign.BORDER); }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(onClick);
        parent.addView(b);
        return b;
    }

    private void setStatus(String msg, boolean ok) {
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
        status.setTextColor(ok ? ExpertDesign.GREEN : ExpertDesign.RED);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() { super.onDestroy(); io.shutdownNow(); }
}
