# Qwen-TTS Element Flow and Axon subscriptions

Experimental additive component. PR #17 (Gemma Cognitive Atlas) is a research reference, not a dependency. Existing Android Element Flow / roots.axon v1 topic allocation on feature/native-tree-core is also a reference; its source and schema are not edited. Roots' Knowledge Wave is the knowledge-demand field; Element Flow is computation evidence. The independent C++ library lives in src/Wds.Roots.Api.Native/KnowledgeWave. No source models or voices are committed.

## Architecture

- roots::wave::Request: begin(stage), dependency(range), lookup(range,row), observe(range,output,width,positions,output_first), finish(). Returns immutable-per-step elements with exact GGUF source role, byte offset, row geometry and within-projection energy. Use one Request per inference, one Lookahead per exact model/backend profile. Source role must be bound to file digest by host.
- roots::wave::Lookahead: train(context,previous,next), predict(context,previous,min_examples,limit). Empirical conditional next-step top-set frequency, falling back to stage-only history within that context. Return hints to an authorized host's asynchronous prefetch queue; never change execution based on hints. No network calls, hidden service, or auto-training on holdout. Initial context can be Elusiaa/English. Prediction quality is not yet measured. Graph boundaries are coarse (not next-layer callbacks).
- roots::qwen_wave: profiling-only Qwen CLI adapter; maps original quantized ranges through direct/F32/conv/fused QKV loading, tracks graph dependencies, host lookups and direct matrix output row groups. It marks bounded intermediate outputs before allocation, synchronizes/readbacks after compute, and streams local aggregate TSV. 32-row groups, up to 32 MiB retained per graph. Derived/view/reshaped weights, unsupported ops/types, graph segments outside hooks and budget-excluded activations are not scored. Dependency-only observation is not a hot row score. A host-side whole-tensor access is a conservative dependency. This is a single-process/single-synthesis probe, not a concurrent tts-server integration. No GPU optimization claim.
- Axon is a torrent-like subscription descriptor, not a model or weights archive. Proposed separate weight-flow-experimental v2 profile: exact talker and codec GGUF SHA-256 identities, physical rows, bytes, encoding/shape, piece SHA-256, proposed roots://sha256/<digest> resource IDs, optional peer certificate pins and local normalized element shares. Peer authorization, signed manifests, roots:// resolution and Whirlwind multi-peer fetching are future integration. v1 remains unchanged. The descriptor does not authorize transmission, execute code, embed a voice sample, or claim executable dependency closure. Keep trace, descriptors and voice data private; export only with voice consent and model redistribution rights. No auto-seeding.
- A future allocator combines independently normalized knowledge demand and element mass using the existing 50/50 starting heuristic; cold start knowledge-only. Current descriptor stores the weights and element shares, with knowledge demand null until the Roots host supplies it. It is not the Android v1 topic allocator.

## Windows commands

Python 3.11+, Git, CMake, VS C++ build tools. Use a local qwentts.cpp checkout with the pinned upstream commit 2edb35f87b709a070b2d5379f77e5c52a1daa585 and its ggml submodule ef4555b7aede4467a5de803b103615e0a5f30dd8 present. The staging utility clones into a **new** separate directory and applies exact-match source hooks; original checkout untouched. If upstream changes, update/test the hook profile rather than blindly patch. The current adapter is an experimental compile/inference target.

```cmd
cmake -S src/Wds.Roots.Api.Native/KnowledgeWave -B wave-build
cmake --build wave-build --config Release
ctest --test-dir wave-build -C Release --output-on-failure

cd tools\knowledge-wave
python stage_qwen.py D:\qwentts.cpp stage
cmake -S stage -B build -A x64 -DGGML_CUDA=ON -DCMAKE_CUDA_ARCHITECTURES=native
cmake --build build --config Release --target qwen-tts
```

CPU build: omit CUDA flags or set GGML_CUDA=OFF. Use the executable location for your generator. Supply the existing matching Qwen3-TTS Base talker GGUF and Qwen codec GGUF (from the local Six Stars runtime), and an authorized Elusiaa voice reference. Use a text file encoded UTF-8. The probe uses CLI, not the existing port 8101 HTTP server; no disruption to normal runtime. Choose ref-spk instead of ref-wav to exercise pre-extracted speaker embeddings (optional ref-rvq + ref-text ICL). Record backend and driver separately. Never use arbitrary remote paths.

```cmd
set TALKER=D:\models\qwen-talker.gguf
set CODEC=D:\models\qwen-tokenizer.gguf
set VOICE=D:\voices\elusiaa_clone.wav
set EXE=build\Release\qwen-tts.exe
python axon.py run --exe "%EXE%" --talker "%TALKER%" --codec "%CODEC%" --ref-wav "%VOICE%" --voice Elusiaa --language English --text-file en.txt --out runs\elusiaa-en
python axon.py run --exe "%EXE%" --talker "%TALKER%" --codec "%CODEC%" --ref-wav "%VOICE%" --voice Elusiaa --language Japanese --text-file ja.txt --out runs\elusiaa-ja
python axon.py compile --talker "%TALKER%" --codec "%CODEC%" --target runs\elusiaa-en --control runs\elusiaa-ja --out elusiaa.en.axon --budget 4194304
python axon.py materialize --talker "%TALKER%" --codec "%CODEC%" --axon elusiaa.en.axon --out pieces
```

Materialize is optional, local-only, and content-hash checked. It writes opaque pieces to a local CAS directory, no network transmission. Do not commit these files. The byte budget is for candidate pieces, not the runtime memory requirement. CLI stages include talker prefill/decode, code prediction, speaker and codec. Trace stores physical addresses and aggregate scores, not raw activations/text, but logs and speech are sensitive. Run metadata binds hashes of text, reference, output, engine and both GGUF sources. Use a new output directory for each run. Hashing large files is deliberately conservative.

## Experiment design

Use matched English/Japanese utterances of varied length, same Elusiaa conditioning, another authorized voice in both languages, neutral and emotional speaking, repeated seeds and independent text holdout. Use --target repeatedly for target requests and --control for matched controls; separate speaker-vs-language contrasts. For identical traces do not treat copies as independent experiments. The compile score is mean peak energy within each observed projection, minus mean control peak if supplied. This is an exploratory screen, not a cross-layer calibrated importance score or proof of voice/language specificity. Unobserved and unscored paths are not zero weights. Lookup rows are grouped to 32-row pieces, possibly overfetching. SHA addresses pin the source geometry; tensor names are not semantic labels.

Next validate shortlisted ranges via equal-byte matched random controls, reversible zero/attenuation on scratch copies, and joint removals. Evaluate fixed-seed teacher-forced codec logits (when available), independent ASR WER/CER, language ID, authorized speaker embedding similarity, duration, prosody, intelligibility and human listening; pre-register retention thresholds and compare on unseen phrases. Do not infer causality from energy or byte reads alone. Measure next-step recall and false-prefetch bytes vs a static baseline on held-out utterances; compare warm/cold latency and peak memory. Only then attempt sparse kernels (paired FFN gate/up and down input columns; attention head/KV geometry), dependency closure and weight-page runtime loading. Dense Qwen still needs full model and codec; this tool does not remove anything or replace the native loader. Prefetching alone preserves dense computation but may not improve memory use.

References: Deja Vu (Liu et al., ICML 2023), https://proceedings.mlr.press/v202/liu23am.html ; PowerInfer, https://arxiv.org/abs/2312.12456 ; Qwen source https://github.com/ServeurpersoCom/qwentts.cpp/tree/2edb35f87b709a070b2d5379f77e5c52a1daa585 ; Roots #17 https://github.com/bradgearon/roots/pull/17 ; existing v1 https://github.com/bradgearon/roots/blob/feature/native-tree-core/design/element-flow-axon.md .

## Verification status

Model-free C++ and Python checks are included in CI. Native Qwen staging/build and voice runs require the local pinned engine and model files; no real activation measurement, extraction percentage, quality, latency improvement, or working roots:// transfer is claimed here.
