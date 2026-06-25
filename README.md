# Minima Expert (native Android)

An **offline, on-device Minima knowledge expert**: ask about the protocol, Winternitz one-time
signatures, TxPoW, MiniDapps and node commands and get answers grounded in a local corpus, with
citations — fully offline.

This is the **native-APK rebuild** of the `nanoLLM` "Minima Expert" MiniDapp. As a web MiniDapp it ran
in the Android **System WebView**, whose WASM relaxed-SIMD int8 kernels miscompute — so on-device LLM
generation produced garbage for every quantization, across 14 builds. A native APK never touches the
WebView's WASM: it runs models through **native ARM (NEON) kernels**, where the same models are correct.

## How it works

```
question
   │ embed (all-MiniLM-L6-v2, ONNX Runtime, native)   ← same vector space as the corpus
   ▼
hybrid retrieval over 2,302 pre-embedded chunks
   ├─ dense  : cosine similarity
   └─ BM25   : keyword/command match
        └─ fused with Reciprocal Rank Fusion
   ▼
Retrieval (always)  ─▶ cited source passages
AI (Phase 1)        ─▶ synthesized answer (llama.cpp + Qwen2.5 GGUF, native NEON)
```

- **The corpus is reused verbatim** from `nanoLLM` (`corpus.json`: 2,302 chunks + 384-dim int8
  `all-MiniLM-L6-v2` embeddings) and shipped in `assets/`. Only the single query is embedded on-device.
- **Generation (Phase 1)** downloads a small **Qwen2.5 GGUF** (default 0.5B) on first "Enable AI" and
  caches it for offline use — kept out of the APK to stay small. Android-only; no desktop tier.

## Status
- **Phase 0 (v0.1.x)** — native RAG retrieval with citations (this build).
- **Phase 1 (v0.2.x)** — on-device generation via llama.cpp / JNI + Qwen2.5.
- **Phase 2 (v0.3.x+)** — model picker (0.5B / 1.5B), management, polish.

## Build
Requires a **JDK 17/21** (the Android Studio JBR works):

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Standalone — it does **not** talk to a Minima node (no IPC/pairing). Just install and ask.

## Releases
Versioned APKs + changelog: **[eurobuddha/minima-core-apks](https://github.com/eurobuddha/minima-core-apks)**
(tags `minima-expert-v<version>`).

## Project layout
- `app/src/main/java/org/minimarex/expert/` — `MainActivity` (chat UI), `Corpus` (parse + dequantize +
  BM25), `WordPieceTokenizer` (BERT), `Embedder` (ONNX Runtime), `Retriever` (dense+BM25+RRF),
  `ExpertDesign`. (Phase 1 adds `LlamaEngine` + JNI + vendored llama.cpp + `ModelManager`.)
- `app/src/main/assets/` — `corpus.json`, `minilm.onnx`, `vocab.txt`.
- Embedder reference: github.com/shubham0204/Sentence-Embeddings-Android · LLM: github.com/shubham0204/SmolChat-Android
