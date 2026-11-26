# Inference runtime and hardware-aware tuning

Status: autotuning design; independent native build implemented experimentally. Copyright (c) 2026 We Develop Software LLC. All rights reserved.

Roots should discover the fastest reliable inference configuration rather than require the user to know the hardware. The native brain service is independent of Android UI, Tree storage and Forge; it supplies inference, not authoritative judgment or a substitute for deliberate writing.

## Runtime selection

- Discover model/runtime identity, OS, driver, SoC, CPU features, available cores, accelerator capabilities, memory and thermal state. Compiled support does not prove that an operation is accelerated.
- Predict compatible backend, threads, batch, context and offload profile. CPU/KleidiAI and qualified Vulkan profiles use the same GGUF; TPU requires a separate compatible model/runtime. Do not force unsupported instructions or silently send text elsewhere.
- Benchmark a fixed synthetic translation corpus with multiple warm repeats and separate cold load. Check translation accuracy, memory, thermal limits, power and responsiveness. Compare CPU threads first; compare GPU after driver validation. Pick the best passing profile for the task, not merely the highest token rate.
- Persist the profile keyed by device/runtime/model/task/driver; invalidate on changes, allow manual overrides and rollback. Retest within user-approved time and energy budgets, off the UI thread. Keep context resident but requests isolated.
- Predictive pretranslation is distinct: use viewport lookahead, cancellation, cached results and prioritized eligible posts. Speculative decoding is another technique requiring a compatible draft model and verification; its benefit is not assumed.

## Build separation

Build the PLaMo/llama.cpp/KleidiAI/Vulkan native target separately with NDK/CMake. Ordinary APK builds can reuse the locally cached ARM64 library, validated by source, JNI declaration and binary hashes. Never quietly package a stale library. Keep Tree Core independent and do not commit binaries or models.

Next implementation: bounded, opt-in CPU autotuning with durable measured results, followed by GPU comparison. Actual fastest backend remains device/model/task dependent.
