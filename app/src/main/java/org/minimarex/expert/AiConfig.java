package org.minimarex.expert;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Online-LLM settings, stored locally (SharedPreferences). Provider-agnostic: any OpenAI-compatible
 * /chat/completions endpoint works (Groq is free — console.groq.com; or OpenAI / OpenRouter / a local
 * server). The API key is entered in-app and never ships in the APK.
 */
public final class AiConfig {

    private static final String PREFS = "minima-expert-ai";
    private final SharedPreferences sp;

    public boolean enabled;
    public boolean setupSeen;     // true once the first-run wizard has been shown (enabled or skipped)
    public String baseUrl;
    public String model;
    public String key;

    // Sensible defaults — free, fast, editable in Settings.
    private static final String DEF_URL = "https://api.groq.com/openai/v1";
    private static final String DEF_MODEL = "llama-3.1-8b-instant";

    public AiConfig(Context ctx) {
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        enabled = sp.getBoolean("enabled", false);
        setupSeen = sp.getBoolean("setupSeen", false);
        baseUrl = sp.getString("baseUrl", DEF_URL);
        model = sp.getString("model", DEF_MODEL);
        key = sp.getString("key", "");
    }

    public void save() {
        sp.edit()
            .putBoolean("enabled", enabled)
            .putBoolean("setupSeen", setupSeen)
            .putString("baseUrl", baseUrl.trim())
            .putString("model", model.trim())
            .putString("key", key.trim())
            .apply();
    }

    /** AI answers are active only when enabled AND a key is present. */
    public boolean active() { return enabled && key != null && !key.trim().isEmpty(); }
}
