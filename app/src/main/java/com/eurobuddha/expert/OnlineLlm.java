package com.eurobuddha.expert;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    /** A completed tool call, assembled from the fragmented streaming deltas. */
    public static final class ToolCall {
        public String id = "", name = "", args = "";
    }

    /** Extended callback for the agent loop: a round ending in finish_reason "tool_calls" fires
     *  onToolCalls INSTEAD of onDone. */
    public interface ToolCb extends Cb {
        void onToolCalls(List<ToolCall> calls);
    }

    public static void stream(AiConfig cfg, String system, String userPrompt, Cb cb) {
        try {
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", system));
            msgs.put(new JSONObject().put("role", "user").put("content", userPrompt));
            stream(cfg, msgs, null, cb);
        } catch (Exception e) {
            cb.onError(e.getMessage() == null ? "network error" : e.getMessage());
        }
    }

    /** Full-shape call: caller-owned messages array, optional tools. tool_calls deltas arrive
     *  fragmented across the stream (arguments split over many chunks, keyed by index) — they are
     *  accumulated here and delivered whole. */
    public static void stream(AiConfig cfg, JSONArray messages, JSONArray tools, Cb cb) {
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
            body.put("messages", messages);
            body.put("stream", true);
            body.put("temperature", 0.3);
            body.put("max_tokens", 700);
            if (tools != null) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code != 200) {
                cb.onError("HTTP " + code + " — " + brief(readAll(con.getErrorStream())));
                return;
            }
            final Map<Integer, ToolCall> pending = new TreeMap<>();
            String finish = "";
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
                        JSONObject choice = choices.getJSONObject(0);
                        String fr = choice.optString("finish_reason", "");
                        if (!fr.isEmpty() && !"null".equals(fr)) finish = fr;
                        JSONObject delta = choice.optJSONObject("delta");
                        if (delta == null) continue;
                        String content = delta.optString("content", "");
                        if (!content.isEmpty()) cb.onToken(content);
                        JSONArray tcs = delta.optJSONArray("tool_calls");
                        if (tcs != null) {
                            for (int i = 0; i < tcs.length(); i++) {
                                JSONObject tc = tcs.getJSONObject(i);
                                int idx = tc.optInt("index", i);
                                ToolCall acc = pending.get(idx);
                                if (acc == null) { acc = new ToolCall(); pending.put(idx, acc); }
                                String id = tc.optString("id", "");
                                if (!id.isEmpty()) acc.id = id;
                                JSONObject fn = tc.optJSONObject("function");
                                if (fn != null) {
                                    String nm = fn.optString("name", "");
                                    if (!nm.isEmpty()) acc.name = nm;
                                    acc.args += fn.optString("arguments", "");
                                }
                            }
                        }
                    } catch (Exception ignore) { /* keep-alive / partial line */ }
                }
            }
            if (("tool_calls".equals(finish) || !pending.isEmpty()) && cb instanceof ToolCb) {
                List<ToolCall> calls = new ArrayList<>();
                for (Map.Entry<Integer, ToolCall> e : pending.entrySet()) {
                    ToolCall c = e.getValue();
                    if (c.id.isEmpty()) c.id = "call_" + e.getKey();
                    if (!c.name.isEmpty()) calls.add(c);
                }
                if (!calls.isEmpty()) { ((ToolCb) cb).onToolCalls(calls); return; }
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
