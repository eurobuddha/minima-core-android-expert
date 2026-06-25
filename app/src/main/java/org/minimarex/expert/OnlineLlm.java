package org.minimarex.expert;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Streams a chat completion from any OpenAI-compatible endpoint (Server-Sent Events). Used for the online
 * AI answer tier: the RAG passages are sent as context and the model's reply streams back token-by-token.
 * Plain HttpURLConnection — no extra dependencies. Call off the UI thread.
 */
public final class OnlineLlm {
    private OnlineLlm() {}

    public interface Cb {
        void onToken(String delta);
        void onDone();
        void onError(String message);
    }

    public static void stream(AiConfig cfg, String system, String userPrompt, Cb cb) {
        HttpURLConnection con = null;
        try {
            URL url = new URL(join(cfg.baseUrl, "chat/completions"));
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + cfg.key.trim());
            con.setRequestProperty("Accept", "text/event-stream");
            con.setConnectTimeout(20000);
            con.setReadTimeout(120000);
            con.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("model", cfg.model);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", system));
            msgs.put(new JSONObject().put("role", "user").put("content", userPrompt));
            body.put("messages", msgs);
            body.put("stream", true);
            body.put("temperature", 0.3);
            body.put("max_tokens", 700);
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code != 200) {
                cb.onError("HTTP " + code + " — " + brief(readAll(con.getErrorStream())));
                return;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) break;
                    try {
                        JSONArray choices = new JSONObject(data).optJSONArray("choices");
                        if (choices == null || choices.length() == 0) continue;
                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                        String content = delta == null ? "" : delta.optString("content", "");
                        if (!content.isEmpty()) cb.onToken(content);
                    } catch (Exception ignore) { /* keep-alive / partial line */ }
                }
            }
            cb.onDone();
        } catch (Exception e) {
            cb.onError(e.getMessage() == null ? "network error" : e.getMessage());
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String join(String base, String path) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + "/" + path;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private static String brief(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }
}
