package org.minimarex.expert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hybrid retrieval, ported verbatim from nanoLLM dapp/app.js: dense cosine (dot product over unit
 * vectors) + BM25 (k1=1.2, b=0.75), fused with Reciprocal Rank Fusion (k=60), top-6 with at most 3
 * chunks from any one source. Pure Java — the part that always worked, now native.
 */
public final class Retriever {

    public static final int TOP_K = 6;
    private static final int RRF_K = 60;
    private static final double K1 = 1.2, B = 0.75;
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
        ("the a an of to and or is are be in on for with as it that this you your we our "
       + "i do does how what why when where which who can could would should will").split(" ")));

    public static final class Hit {
        public final int idx;
        public final String source, title, text;
        public final float dense, score;
        Hit(int idx, String source, String title, String text, float dense, float score) {
            this.idx = idx; this.source = source; this.title = title; this.text = text;
            this.dense = dense; this.score = score;
        }
    }

    public static List<Hit> retrieve(Corpus c, float[] qv, String query) {
        int n = c.count, dim = c.dim;

        // dense cosine (unit vectors → dot product)
        final float[] dense = new float[n];
        for (int i = 0; i < n; i++) {
            int base = i * dim;
            double dot = 0;
            for (int d = 0; d < dim; d++) dot += qv[d] * c.vectors[base + d];
            dense[i] = (float) dot;
        }

        final float[] lex = bm25(c, query);
        int[] rDense = rankMap(dense);
        int[] rLex = rankMap(lex);

        final float[] fused = new float[n];
        for (int i = 0; i < n; i++) fused[i] = 1f / (RRF_K + rDense[i]) + 1f / (RRF_K + rLex[i]);

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(fused[b], fused[a]));

        List<Hit> picked = new ArrayList<>();
        Map<String, Integer> perSource = new HashMap<>();
        for (int idx : order) {
            String src = c.source[idx];
            if (perSource.merge(src, 1, Integer::sum) > 3) continue;   // ≤3 per source
            picked.add(new Hit(idx, src, c.title[idx], c.text[idx], dense[idx], fused[idx]));
            if (picked.size() >= TOP_K) break;
        }
        return picked;
    }

    private static float[] bm25(Corpus c, String query) {
        int N = c.count;
        float[] scores = new float[N];
        Set<String> qterms = new HashSet<>();
        Matcher m = WORD.matcher(query.toLowerCase());
        while (m.find()) { String t = m.group(); if (!STOP.contains(t)) qterms.add(t); }
        for (String t : qterms) {
            Integer dft = c.df.get(t);
            if (dft == null) continue;
            double idf = Math.log(1 + (N - dft + 0.5) / (dft + 0.5));
            for (int i = 0; i < N; i++) {
                Integer tf = c.tf.get(i).get(t);
                if (tf == null) continue;
                double denom = tf + K1 * (1 - B + B * (c.lens[i] / c.avgLen));
                scores[i] += (float) (idf * (tf * (K1 + 1)) / denom);
            }
        }
        return scores;
    }

    /** Index → rank (0 = highest score), matching the dapp's rankMap. */
    private static int[] rankMap(float[] scores) {
        int n = scores.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));
        int[] rank = new int[n];
        for (int r = 0; r < n; r++) rank[order[r]] = r;
        return rank;
    }
}
