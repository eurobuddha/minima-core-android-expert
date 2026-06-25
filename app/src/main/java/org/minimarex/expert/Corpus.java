package org.minimarex.expert;

import android.content.res.AssetManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The offline RAG corpus: 2,302 chunks + 384-dim all-MiniLM int8 embeddings, loaded verbatim from
 * nanoLLM's corpus.json (shipped in assets). Dense vectors are dequantized to ~unit-length float for
 * cosine (dot) scoring; a BM25 lexicon (df / per-chunk tf / lengths) is built once at boot.
 */
public final class Corpus {

    public int count, dim;
    public String[] source, title, text;
    public float[] vectors;                              // count*dim, dequantized

    public final Map<String, Integer> df = new HashMap<>();
    public final List<Map<String, Integer>> tf = new ArrayList<>();
    public int[] lens;
    public double avgLen = 1;

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    public static Corpus load(AssetManager am) throws Exception {
        String json = new String(readAll(am.open("corpus.json")), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(json);
        Corpus c = new Corpus();
        c.dim = root.optInt("dim", 384);
        JSONArray chunks = root.getJSONArray("chunks");
        c.count = chunks.length();
        c.source = new String[c.count];
        c.title = new String[c.count];
        c.text = new String[c.count];
        c.vectors = new float[c.count * c.dim];
        c.lens = new int[c.count];
        long totalLen = 0;

        for (int i = 0; i < c.count; i++) {
            JSONObject ch = chunks.getJSONObject(i);
            c.source[i] = ch.optString("source", "");
            c.title[i] = ch.optString("title", c.source[i]);
            c.text[i] = ch.optString("text", "");
            // dequantize: base64 → signed int8 byte → /127 (matches the dapp's dequantizeAll)
            byte[] q = Base64.decode(ch.getString("vec"), Base64.DEFAULT);
            int base = i * c.dim;
            for (int d = 0; d < c.dim && d < q.length; d++) c.vectors[base + d] = q[d] / 127f;
            // BM25 term frequencies for this chunk
            Map<String, Integer> tfm = termFreq(c.text[i]);
            c.tf.add(tfm);
            int len = 0;
            for (int v : tfm.values()) len += v;
            c.lens[i] = len;
            totalLen += len;
            for (String t : tfm.keySet()) c.df.merge(t, 1, Integer::sum);
        }
        c.avgLen = c.count > 0 ? (double) totalLen / c.count : 1;
        return c;
    }

    private static Map<String, Integer> termFreq(String s) {
        Map<String, Integer> m = new HashMap<>();
        Matcher mm = WORD.matcher(s.toLowerCase());
        while (mm.find()) m.merge(mm.group(), 1, Integer::sum);
        return m;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = i.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
