# Roots Cognitive Atlas — desktop experiment

Objective: retrieve real quantized Gemma parameter regions from a request-driven functional index, not claim that model weights contain individually addressable facts. Gemma Scope 2 residual SAEs are a feature microscope; the Atlas connects observed features to FFN groups using coactivation, a corpus baseline, support shrinkage and bounded restarted spreading activation. The physical index is built from GGUF tensor offsets, dimensions and byte counts. It reads exact gate/up row ranges under a byte budget; down-projection columns, attention head partitioning, sparse execution and causal interpretation are not implemented.

## Run on Windows

Python 3.11/3.12 and an accepted Gemma license/Hugging Face account may be needed. Start from repository root:

```cmd
atlas.cmd setup
atlas.cmd test
atlas.cmd run
atlas.cmd query --request "Explain why a root listens for rain" --extract
```

Pass --gguf "C:\path\gemma-3-270m-it-Q8_0.gguf" to run with an existing file. Default run downloads only the 270M reference model, the 292 MB Q8 GGUF, and four selected Scope 2 params.safetensors (not examples or the entire multi-terabyte repository). For a smaller initial experiment use --layers 12. If gated, accept https://huggingface.co/google/gemma-3-270m-it terms and log in with .roots-atlas\venv\Scripts\hf.exe auth login. CUDA is used if installed PyTorch detects it, else CPU. No Android build required. Python dependencies are isolated in the ignored .roots-atlas workspace.

## Measurements

Reference FP32 Gemma 3 270M IT runs locally. Observe residual post-layer states at layers 5,9,12,15 with matching Google JumpReLU encoders (16k, medium sparsity). Sample last eight prompt rows; aggregate top 64 positive features per site; gated MLP down-projection input yields 32-channel FFN group energy at all 18 layers. Normalize within sites and globally. The chat template is included in the prompt observations, so template effects and overlapping wording may dominate. The structural correspondence between reference model and GGUF is checked by shape and exact GGUF hash; quantized activation equivalence is not established.

Train on six small synthetic categories, including controls. Fixed held-out probes are separate; compare the fraction of observed FFN group energy retrieved by Atlas, global baseline, random and an observation-informed oracle under the same gate/up byte budget. Not a benchmark of output quality, intelligence or causal necessity. Query encodes a request with the reference model first, then activates learned feature associations and reads requested physical ranges. This is proof of retrieval, not pre-inference acceleration; a lightweight cue predictor is future work. Raw extracted ranges are not runnable GGUFs.

Micro-cognition is an optional local Gemma controller (on by default). It receives aggregate metrics, proposes a bounded public training probe and a cautious spoken-language explanation, then the actual observer executes the validated probe. No arbitrary commands, no changes to fixed held-out data or budgets. Invalid JSON or repeated question -> abstain. The final assessment is a suggestion, not a measured result; --no-micro disables it. No prompts or raw activation vectors from query are persisted, although sparse signatures and retrieved regions remain private artifacts. Only initial model downloads use the network. No speech synthesis is included yet; speech text is available for Roots' future TTS pipeline.

Artifacts: .roots-atlas/atlas.json (model/SAE revision hashes, tensor directory, sparse association graph, training observations, held-out metrics); last-request.json (ranked plan and feature wave); retrieved/ranges.bin and ranges.json (exact raw ranges, source offsets, lengths and hashes). Do not share model weight ranges contrary to their license. Axon integration and a compact GGUF cognitive index are subsequent work.

Source: https://huggingface.co/google/gemma-scope-2-270m-it (CC-BY-4.0); Gemma model terms separately apply. No actual model run is claimed until the experiment has been executed on a machine with the artifacts.
