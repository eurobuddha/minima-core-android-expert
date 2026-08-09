# Graph Report - expert  (2026-08-09)

## Corpus Check
- 17 files · ~239,004 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 129 nodes · 276 edges · 13 communities (9 shown, 4 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 17 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `f0a8f113`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MainActivity
- SetupActivity
- User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly.
- Corpus
- .stream
- WordPieceTokenizer
- AiConfig
- gradlew
- ExpertDesign
- Prompt

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 32 edges
2. `SetupActivity` - 19 edges
3. `AiConfig` - 13 edges
4. `WordPieceTokenizer` - 13 edges
5. `Embedder` - 10 edges
6. `Corpus` - 8 edges
7. `OnlineLlm` - 7 edges
8. `Cb` - 7 edges
9. `Hit` - 7 edges
10. `Retriever` - 6 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `AiConfig`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/expert/MainActivity.java → app/src/main/java/com/eurobuddha/expert/AiConfig.java
- `SetupActivity` --references--> `AiConfig`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/expert/SetupActivity.java → app/src/main/java/com/eurobuddha/expert/AiConfig.java
- `MainActivity` --references--> `Corpus`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/expert/MainActivity.java → app/src/main/java/com/eurobuddha/expert/Corpus.java
- `MainActivity` --references--> `Embedder`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/expert/MainActivity.java → app/src/main/java/com/eurobuddha/expert/Embedder.java
- `Embedder` --references--> `WordPieceTokenizer`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/expert/Embedder.java → app/src/main/java/com/eurobuddha/expert/WordPieceTokenizer.java

## Import Cycles
- None detected.

## Communities (13 total, 4 thin omitted)

### Community 0 - "MainActivity"
Cohesion: 0.21
Nodes (8): EditText, Handler, LinearLayout, TextView, MainActivity, Hit, AppCompatActivity, Button

### Community 1 - "SetupActivity"
Cohesion: 0.22
Nodes (11): Bundle, EditText, Handler, LinearLayout, Override, TextView, SetupActivity, LayoutParams (+3 more)

### Community 3 - "Corpus"
Cohesion: 0.23
Nodes (5): Corpus, AssetManager, Pattern, Pattern, Retriever

### Community 5 - "WordPieceTokenizer"
Cohesion: 0.16
Nodes (6): Embedder, AssetManager, AssetManager, WordPieceTokenizer, OrtEnvironment, OrtSession

### Community 6 - "AiConfig"
Cohesion: 0.19
Nodes (5): AiConfig, Bundle, Override, Context, SharedPreferences

### Community 7 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **1 isolated node(s):** `RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions.`
  These have ≤1 connection - possible missing edges or undocumented components.
- **4 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `SetupActivity`, `Corpus`, `WordPieceTokenizer`, `AiConfig`?**
  _High betweenness centrality (0.423) - this node is a cross-community bridge._
- **Why does `Embedder` connect `WordPieceTokenizer` to `MainActivity`, `AiConfig`?**
  _High betweenness centrality (0.214) - this node is a cross-community bridge._
- **Why does `AiConfig` connect `AiConfig` to `MainActivity`, `SetupActivity`, `.stream`?**
  _High betweenness centrality (0.171) - this node is a cross-community bridge._
- **What connects `RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions.` to the rest of the system?**
  _1 weakly-connected nodes found - possible documentation gaps or missing edges._