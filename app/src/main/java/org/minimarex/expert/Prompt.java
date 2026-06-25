package org.minimarex.expert;

import java.util.List;

/** RAG prompt construction — reused verbatim from the nanoLLM dapp (app.js buildPrompt / AI_SYS) so the
 *  online model answers strictly from the retrieved passages and cites them inline as [n]. */
public final class Prompt {
    private Prompt() {}

    public static final String SYSTEM =
        "You are a precise Minima blockchain expert. Answer using ONLY the provided context passages and "
      + "cite them inline as [n]. If the context does not contain the answer, say so plainly — never invent facts.";

    public static String build(String query, List<Retriever.Hit> passages) {
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < passages.size(); i++) {
            Retriever.Hit p = passages.get(i);
            if (i > 0) ctx.append("\n\n");
            ctx.append('[').append(i + 1).append("] (").append(p.title).append(")\n").append(p.text);
        }
        return "You are a precise Minima blockchain expert. Answer the QUESTION using ONLY the CONTEXT "
             + "passages below. Cite the passages you use inline with their bracket numbers like [1] or [2,3]. "
             + "If the context does not contain the answer, say \"The corpus doesn't cover that\" — do not invent facts.\n\n"
             + "CONTEXT:\n" + ctx + "\n\nQUESTION: " + query + "\n\nANSWER:";
    }
}
