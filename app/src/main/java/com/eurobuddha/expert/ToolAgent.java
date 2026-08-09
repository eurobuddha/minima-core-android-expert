package com.eurobuddha.expert;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The agent loop for web-tool answers: stream a round from the model; if it ends in tool calls,
 * execute them (search / fetch / GitHub), append the results and go again — bounded by rounds and
 * wall clock so the single io executor is never held hostage. Runs synchronously on the caller's
 * (io) thread; Ui callbacks fire on that thread too.
 */
public final class ToolAgent {
    private ToolAgent() {}

    public static final int MAX_ROUNDS = 4;
    public static final long MAX_MILLIS = 90_000;

    public interface Ui {
        void onStatus(String line);              // "Searching the web for …"
        void onReset();                          // discard content streamed before a tool round
        void onToken(String delta);
        void onWebSource(Tools.WebSource s);
        void onDone();
        void onError(String message);
    }

    public static void run(AiConfig cfg, String query, List<Retriever.Hit> hits, Ui ui) {
        try {
            final JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", Prompt.SYSTEM_TOOLS));
            messages.put(new JSONObject().put("role", "user").put("content", Prompt.build(query, hits)));

            final Tools.Ctx ctx = new Tools.Ctx();
            ctx.nextN = hits.size() + 1;
            ctx.progress = ui::onStatus;

            final long start = System.currentTimeMillis();
            final JSONArray schema = Tools.schema();

            for (int round = 1; round <= MAX_ROUNDS; round++) {
                boolean allowTools = round < MAX_ROUNDS
                        && System.currentTimeMillis() - start < MAX_MILLIS;

                final StringBuilder content = new StringBuilder();
                final List<OnlineLlm.ToolCall> calls = new ArrayList<>();
                final String[] error = {null};

                OnlineLlm.stream(cfg, messages, allowTools ? schema : null, new OnlineLlm.ToolCb() {
                    @Override public void onToken(String delta) { content.append(delta); ui.onToken(delta); }
                    @Override public void onDone() {}
                    @Override public void onError(String message) { error[0] = message; }
                    @Override public void onToolCalls(List<OnlineLlm.ToolCall> tc) { calls.addAll(tc); }
                });

                if (error[0] != null) { ui.onError(error[0]); return; }
                if (calls.isEmpty()) { ui.onDone(); return; }

                // Tool round: drop any half-answer the model streamed before deciding to call tools.
                ui.onReset();
                JSONArray tcArr = new JSONArray();
                for (OnlineLlm.ToolCall c : calls) {
                    tcArr.put(new JSONObject()
                        .put("id", c.id)
                        .put("type", "function")
                        .put("function", new JSONObject().put("name", c.name).put("arguments", c.args)));
                }
                messages.put(new JSONObject()
                        .put("role", "assistant")
                        .put("content", content.length() == 0 ? JSONObject.NULL : content.toString())
                        .put("tool_calls", tcArr));

                for (OnlineLlm.ToolCall c : calls) {
                    int before = ctx.sources.size();
                    JSONObject args;
                    String result;
                    try { args = new JSONObject(c.args.isEmpty() ? "{}" : c.args); }
                    catch (Exception e) { args = null; }
                    result = args == null ? "TOOL_ERROR: bad arguments" : Tools.execute(c.name, args, ctx);
                    for (int i = before; i < ctx.sources.size(); i++) ui.onWebSource(ctx.sources.get(i));
                    messages.put(new JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", c.id)
                            .put("content", result));
                }
                ui.onStatus("Writing the answer…");
            }
            ui.onDone();   // MAX_ROUNDS exhausted — last round ran without tools, so content already streamed
        } catch (Exception e) {
            ui.onError(e.getMessage() == null ? "agent error" : e.getMessage());
        }
    }
}
