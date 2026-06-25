package org.minimarex.expert;

import android.content.res.AssetManager;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Embeds a query into the SAME 384-dim space as corpus.json by running all-MiniLM-L6-v2 (ONNX) on-device:
 * WordPiece tokenize → ONNX Runtime → mean-pool last_hidden_state over the tokens → L2-normalize. Runs on
 * native ARM kernels (no WebView WASM), which is the whole reason this works where the MiniDapp couldn't.
 */
public final class Embedder {

    public static final int DIM = 384;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final WordPieceTokenizer tok;

    public Embedder(AssetManager am, String onnxAsset, String vocabAsset) throws Exception {
        tok = new WordPieceTokenizer(am, vocabAsset);
        env = OrtEnvironment.getEnvironment();
        byte[] model = readAll(am.open(onnxAsset));
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        session = env.createSession(model, opts);
    }

    /** Embed one query → unit-normalized float[384]. Synchronous; call off the UI thread. */
    public float[] embed(String text) throws Exception {
        long[] ids = tok.encode(text);
        int len = ids.length;
        long[][] inputIds = new long[1][len];
        long[][] mask = new long[1][len];
        long[][] type = new long[1][len];
        for (int i = 0; i < len; i++) { inputIds[0][i] = ids[i]; mask[0][i] = 1L; type[0][i] = 0L; }

        OnnxTensor tIds = OnnxTensor.createTensor(env, inputIds);
        OnnxTensor tMask = OnnxTensor.createTensor(env, mask);
        OnnxTensor tType = OnnxTensor.createTensor(env, type);
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", tIds);
        inputs.put("attention_mask", tMask);
        inputs.put("token_type_ids", tType);

        float[] pooled = new float[DIM];
        try (OrtSession.Result res = session.run(inputs)) {
            OnnxValue ov = res.get(0);
            float[][][] hidden = (float[][][]) ov.getValue();   // [1][len][384]
            float[][] toks = hidden[0];
            // Mean-pool over all tokens (attention_mask is all 1s — no padding), then L2-normalize.
            for (float[] tokVec : toks)
                for (int d = 0; d < DIM; d++) pooled[d] += tokVec[d];
            float inv = toks.length > 0 ? 1f / toks.length : 0f;
            double norm = 0;
            for (int d = 0; d < DIM; d++) { pooled[d] *= inv; norm += (double) pooled[d] * pooled[d]; }
            float n = (float) Math.sqrt(norm);
            if (n > 0) for (int d = 0; d < DIM; d++) pooled[d] /= n;
        } finally {
            tIds.close(); tMask.close(); tType.close();
        }
        return pooled;
    }

    public void close() { try { session.close(); } catch (Exception ignored) {} }

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
