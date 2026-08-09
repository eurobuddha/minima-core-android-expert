package com.eurobuddha.expert;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Minimal https-only GET for the AI web tools. Redirects are followed manually because
 * HttpURLConnection refuses cross-protocol hops (github.com → raw.githubusercontent.com is common);
 * every hop must stay https. Bodies are read under a hard size cap so a fetched page can never
 * blow the prompt or the heap.
 */
public final class HttpFetch {
    private HttpFetch() {}

    public static final int MAX_CHARS = 64 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final String UA = "Mozilla/5.0 (Linux; Android) MinimaExpert/0.7";

    public static final class Result {
        public String body = "", finalUrl = "", contentType = "";
        public int code;
    }

    public static Result get(String url, String accept) throws IOException {
        String target = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!target.startsWith("https://")) throw new IOException("https only — refused " + target);
            HttpURLConnection con = (HttpURLConnection) new URL(target).openConnection();
            try {
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(10000);
                con.setReadTimeout(15000);
                con.setRequestProperty("User-Agent", UA);
                if (accept != null) con.setRequestProperty("Accept", accept);
                int code = con.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = con.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without Location");
                    target = new URL(new URL(target), loc).toString();
                    continue;
                }
                Result r = new Result();
                r.code = code;
                r.finalUrl = target;
                r.contentType = con.getContentType() == null ? "" : con.getContentType();
                InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
                r.body = in == null ? "" : readCapped(in, charsetOf(r.contentType));
                return r;
            } finally {
                con.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private static Charset charsetOf(String contentType) {
        int i = contentType.toLowerCase().indexOf("charset=");
        if (i < 0) return StandardCharsets.UTF_8;
        String cs = contentType.substring(i + 8).trim();
        int end = cs.indexOf(';');
        if (end >= 0) cs = cs.substring(0, end);
        try { return Charset.forName(cs.replace("\"", "").trim()); }
        catch (Exception e) { return StandardCharsets.UTF_8; }
    }

    private static String readCapped(InputStream in, Charset cs) throws IOException {
        try (Reader r = new InputStreamReader(in, cs)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
                if (sb.length() >= MAX_CHARS) { sb.setLength(MAX_CHARS); break; }
            }
            return sb.toString();
        }
    }

    /** Page HTML → readable text: scripts/styles/comments removed, block tags become newlines,
     *  the rest become spaces, basic entities decoded, whitespace collapsed. */
    public static String htmlToText(String html) {
        String s = html
            .replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ")
            .replaceAll("(?s)<!--.*?-->", " ")
            .replaceAll("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/pre)[^>]*>", "\n")
            .replaceAll("<[^>]+>", " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&nbsp;", " ").replace("&#x27;", "'");
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    public static String truncate(String s, int maxChars) {
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n…[truncated]";
    }
}
