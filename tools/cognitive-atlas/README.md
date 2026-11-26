# Roots Cognitive Atlas — GGUF-first experiments

Experimental, isolated from the Roots desktop and Android runtimes. The master is read-only; no model weights are committed. The first stage treats the GGUF container as the experiment address space, not a semantic database and not a source-framework graph.

## Run on Windows

Use Python 3.10+, CMake 3.22+, Git, Visual Studio C++ desktop development, and a locally downloaded, license-approved single-file little-endian Gemma 3 GGUF. For the first smoke test choose the official ggml-org Gemma 3 270M IT Q8_0 conversion:
https://huggingface.co/ggml-org/gemma-3-270m-it-GGUF
Use the model card, Gemma terms (https://ai.google.dev/gemma/terms), and publisher file digest; do not download or redistribute automatically. Next repeat with Gemma 3 1B and 4B (text-only inference; no projector). Start with Q8_0 or F16/BF16 where available, then compare Q4_K_M separately.

From the Roots checkout:
```cmd
cd tools\cognitive-atlas
python atlas.py selftest
cmake -S . -B build -A x64
cmake --build build --config Release --target atlas-score
set MODEL=D:\models\gemma-3-270m-it-Q8_0.gguf
python atlas.py inspect "%MODEL%" > inventory.json
python atlas.py plan "%MODEL%" --scope group > plan.json
python atlas.py scan "%MODEL%" --scorer build\Release\atlas-score.exe --out discovery.json --scope group --limit 12
python atlas.py rank discovery.json > candidates.json
```

The CMake project fetches llama.cpp b11960, the existing Roots native-engine revision, unless -DROOTS_LLAMA_CPP_DIR points to a local checkout. Linux/macOS omit -A x64; use the generator's actual executable path. CPU default; optional GPU build e.g. -DGGML_CUDA=ON and scan --gpu-layers 99. Keep GPU/CPU, thread counts, model version, and quantization fixed across comparisons. Windows builds and real GGUF inference require target-environment verification. If the executable is in a different configuration directory, supply its actual path.

For a full group scan raise --limit above the number of groups in plan.json. With a lower limit the tool samples evenly in directory order, not by category. To refine an interesting range:
```cmd
python atlas.py plan "%MODEL%" --scope chunk --match "blk\.5\.ffn_" --chunks 8
python atlas.py scan "%MODEL%" --scorer build\Release\atlas-score.exe --out fine.json --scope chunk --match "blk\.5\.ffn_" --chunks 8 --limit 24
python atlas.py rank fine.json
```

Layer 5 is an illustrative address, not an observed finding. Replace it with measured candidates. To validate a single chosen region with the held-out examples, copy its exact key from plan/rank and repeat the same scope/chunks with --region KEY --split holdout --out holdout.json. Never tune on holdout; add a separate final test before claiming specialization. For a base (non-instruction-tuned) Gemma model use --mode base. The scorer compares the two single-token answer labels after their longest shared token prefix; it fails explicitly for incompatible tokenization rather than silently scoring the wrong tokens.

## What is mapped

Inventory: file version, architecture, relevant metadata, tensor names, dimensions in GGML order, encodings, absolute byte offsets, actual supported tensor bytes, and allocated spans (may include padding). Unknown formats are listed but not patched. Split models and big-endian GGUF are rejected. Scan supports standard gemma3 text models and the named block matrices only.

Candidate grouping:
- layer: supported attention + FFN matrix tensors in one numbered block.
- group: attention Q/K/V/output vs. FFN gate/up/down matrix tensors in one block.
- tensor: one named supported matrix.
- chunk: contiguous physical rows within one tensor, divided into N pieces; row boundaries preserve quantization blocks. These are serialized output-row ranges, not assumed neurons, head boundaries or semantic units.

Ablation sets supported complete quantization blocks (or float values) to zero. A scratch copy is made once, then patched and restored between subprocesses. An exact byte-for-byte source/restore digest check is made at completion. Do not modify the master concurrently. Disk requirement is approximately one additional GGUF plus logs. This is a coarse perturbation; severe removal may cause distribution shift rather than a specific capability loss. Embeddings, norms, tokenizer, output weights, and unknown matrix encodings are not ablated in this pilot. The tool does not claim to cover every computational connection.

Every probe is an objective two-choice task, in both answer orders. Record the signed difference in correct vs incorrect next-token logits, average the two orders, and measure fraction of orders correct. Two unmodified baselines estimate repeat noise. Margin loss = baseline correct-answer margin minus ablated margin. Selectivity = category margin loss minus median loss across other categories. Rank filters weak baseline performance, nonpositive selectivity and changes smaller than twice repeat noise. This is a screening heuristic, not statistical significance. Equal-byte matched controls, graded attenuation and independent replication are future work; compare equal-row ranges in a common matrix before drawing conclusions.

Report has SHA-256 of master, scorer binary, probe dataset, inventory, exact region offsets/lengths, raw per-case margins, baseline/repeat, category accuracy, margin loss, spread, and selectivity. It is saved after each completed experiment; incomplete scans are marked incomplete and cannot be ranked. Record actual llama.cpp source revision and compiler/backend configuration with the report. This tool observes effects of changing static weights on logits; it does not measure activations, training updates or infer which weights "moved" during a forward pass.

## Categories and next experiments

40 smoke probes, 20 discovery and 20 holdout, four independent questions per category (two each split), each in both answer orders:
English grammar, vocabulary, basic comprehension, relational reasoning, rhyme, metaphor/personification (displacement recognition), code recognition, engineering reasoning, arithmetic and non-English controls. These are recognition proxies, not proof of English fluency, original poetry, creativity or working engineering skill. Expand with controlled multilingual/paraphrase pairs, independent writing judgments, actual compiler/unit-test results, longer contextual tasks and matched random ranges. Keep task correctness independent of the teacher. Compare against unmodified master and a size-matched distilled student.

An atlas is many-to-many: region -> category evidence and category -> ranked regions. A region contributing to multiple skills may belong to shared infrastructure. A loss when removing a part is evidence of necessity in that model/context, not sufficiency in isolation. Repeat across quantizations and models; the byte addresses are specific to each file SHA, so compare tensor name/dimensions and row geometry rather than raw offsets. To map connections, add pairwise and joint ablations on shortlisted regions and test interaction beyond single-region losses; not implemented in this version. A zeroed GGUF retains its original file size and compute graph.

## Extract a candidate

Only after checking licensing and validating behavior, export an exact region as opaque bytes plus a source hash, tensor identity, row interval, encoding, offsets and fragment SHA-256:
```cmd
python atlas.py extract "%MODEL%" --scope chunk --chunks 8 --region blk.5.ffn_up.weight.rows0-256 --out fragments\example
```
The key above is illustrative; use an actual plan key. A fragment is NOT a standalone GGUF and cannot be run by llama.cpp. It does not include the rest of the computational pathway. Do not share weights without the appropriate license and redistribution terms. Do not check model files, fragments or experiment reports into source control by default.

Next: evaluate joint masks and retention of shared English/comprehension circuitry; implement quantization-aware structural slicing only if validated, or use the atlas to guide smaller student distillation. Preserve tokenizer, embedding, residual, attention, normalization, and output compatibility. Judge a poet on independent generation (imagery, semantic displacement, rhythm, coherence, originality) and an engineer on executable tests, correctness, debugging, security, and retained general reasoning. The resulting specialized model is a new artifact, not a modification to the master.

References: https://github.com/ggml-org/ggml/blob/master/docs/gguf.md ; https://github.com/ggml-org/llama.cpp/blob/master/include/llama.h ; https://ai.google.dev/gemma/docs/gemma_scope . Gemma Scope 2 is optional later corroboration, not a requirement of the GGUF-first pipeline.
