package org.minimarex.expert;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BERT WordPiece tokenizer for the uncased all-MiniLM-L6-v2 embedder. Faithfully mirrors HuggingFace's
 * BertNormalizer (clean text, pad CJK, lowercase, strip accents) → BasicTokenizer (whitespace +
 * punctuation splitting) → WordPiece (greedy longest-match with the "##" continuation prefix). It MUST
 * match how the corpus was tokenized at build time, or the query embeds into a different vector space
 * and retrieval breaks.
 */
public final class WordPieceTokenizer {

    private final Map<String, Integer> vocab = new HashMap<>();
    private final int clsId, sepId, unkId;
    private static final int MAX_LEN = 192;             // queries are short; cap defensively
    private static final int MAX_CHARS_PER_WORD = 100;

    /** Load vocab.txt (one token per line; line index = token id, i.e. standard BERT vocab). */
    public WordPieceTokenizer(AssetManager am, String vocabAsset) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(am.open(vocabAsset), StandardCharsets.UTF_8))) {
            String line; int lineNo = 0;
            while ((line = r.readLine()) != null) vocab.put(line, lineNo++);
        }
        clsId = id("[CLS]"); sepId = id("[SEP]"); unkId = id("[UNK]");
    }

    private int id(String t) { Integer v = vocab.get(t); return v == null ? 0 : v; }

    /** Encode to token ids framed as [CLS] … [SEP], truncated to MAX_LEN. */
    public long[] encode(String text) {
        List<Integer> ids = new ArrayList<>();
        ids.add(clsId);
        outer:
        for (String basic : basicTokenize(normalize(text))) {
            for (int wp : wordpiece(basic)) {
                if (ids.size() >= MAX_LEN - 1) break outer;
                ids.add(wp);
            }
        }
        ids.add(sepId);
        long[] out = new long[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    // ---- BertNormalizer: clean control chars, pad CJK, lowercase, strip accents ----
    private String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue;                 // clean_text
            if (isWhitespace(cp)) { sb.append(' '); continue; }
            if (isChinese(cp)) { sb.append(' ').appendCodePoint(cp).append(' '); continue; } // handle_chinese_chars
            sb.appendCodePoint(cp);
        }
        String lower = sb.toString().toLowerCase(Locale.ROOT);                      // lowercase
        String nfd = Normalizer.normalize(lower, Normalizer.Form.NFD);              // strip_accents (follows lowercase)
        StringBuilder out = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            if (Character.getType(nfd.charAt(i)) == Character.NON_SPACING_MARK) continue;
            out.append(nfd.charAt(i));
        }
        return out.toString();
    }

    // ---- BasicTokenizer: split on whitespace, then split every punctuation char out ----
    private List<String> basicTokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isWhitespace(cp)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else if (isPunctuation(cp)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                out.add(new String(Character.toChars(cp)));
            } else {
                cur.appendCodePoint(cp);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // ---- WordPiece: greedy longest-match subwords; whole token → [UNK] if any piece is OOV ----
    private List<Integer> wordpiece(String token) {
        List<Integer> out = new ArrayList<>();
        if (token.length() > MAX_CHARS_PER_WORD) { out.add(unkId); return out; }
        int start = 0; boolean bad = false;
        List<Integer> sub = new ArrayList<>();
        while (start < token.length()) {
            int end = token.length();
            Integer curId = null;
            while (start < end) {
                String piece = (start > 0 ? "##" : "") + token.substring(start, end);
                Integer v = vocab.get(piece);
                if (v != null) { curId = v; break; }
                end--;
            }
            if (curId == null) { bad = true; break; }
            sub.add(curId);
            start = end;
        }
        if (bad) out.add(unkId); else out.addAll(sub);
        return out;
    }

    // ---- char classes (BERT semantics) ----
    private static boolean isWhitespace(int cp) {
        if (cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r') return true;
        return Character.getType(cp) == Character.SPACE_SEPARATOR;
    }
    private static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') return false;
        int t = Character.getType(cp);
        return t == Character.CONTROL || t == Character.FORMAT;
    }
    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) return true;
        int t = Character.getType(cp);
        return (t >= Character.DASH_PUNCTUATION && t <= Character.OTHER_PUNCTUATION)
            || t == Character.INITIAL_QUOTE_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION;
    }
    private static boolean isChinese(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
            || (cp >= 0x20000 && cp <= 0x2A6DF) || (cp >= 0xF900 && cp <= 0xFAFF);
    }
}
