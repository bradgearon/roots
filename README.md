# Roots

**Copyright (c) 2026 We Develop Software LLC. All rights reserved.** Roots-owned software and documentation are proprietary; see [LICENSE](LICENSE). Third-party components retain their own applicable licenses.

See the [Roots design area](design/README.md) for the proposed ChristEye [Tree of Life](design/tree-of-life.md), [Honeycomb and Whirlwind](design/honeycomb-whirlwind.md), [Garden participation](design/garden.md), recognition, micro-cognition, deliberate writing, agents, ratings, availability and native web presence. These are design proposals, not implemented runtime features.

Independent local-first translation and social integration platform. The initial code is extracted directly from Six Stars and Resone, not a replacement implementation of the X integration. No game, companion, Gemma, ASR or TTS dependencies are needed to use translation.

## Windows desktop host

Install the .NET 9 SDK and Visual C++ 2015–2022 runtime, then run from this checkout:

```cmd
launch.cmd
```

The launcher builds the Roots host and its API, downloads the small FastText model, then uses Resone's AI_ROOT resolution, hardware selection, engine pack installer and resumable model provisioner. The default desktop model is the original PLaMo 3 Translate 2B Q4_K_M (about 1.81 GB). To choose the smaller 0.8B version, change the enabled model in config/runtime.json; enable exactly one LLM model. Model digest comes from the publisher's HTTPS X-Linked-Etag if no SHA-256 has been pinned in the manifest. Pin the digest before production; first-run digest retrieval requires network access. The private llama.cpp server listens on 127.0.0.1:8081, Roots gateway on 127.0.0.1:8123. The console displays the token path (%LOCALAPPDATA%/Roots/translation-token.txt). Do not run the Six Stars translation host on the same ports concurrently.

`launch.cmd --lan` explicitly enables trusted LAN access to the gateway for Android. Plain HTTP/WebSocket is not encrypted: restrict the firewall to trusted clients or use a properly secured HTTPS/VPN bridge. `launch.cmd --external-model` uses an already running compatible llama-server on 8081.

Roots preserves Resone's engine and model provisioning method, but keeps a separate llama-server process for PLaMo PFML3 completions rather than importing the music-specific in-process bridge.

## Chrome

Open chrome://extensions, enable developer mode, and load unpacked chrome-extension/roots-translate. Enter the Roots desktop token in the popup. Existing automatic translation of nearby foreign posts, original text, inline results, cancellation, caching and manual translation are retained.

## Android

Windows command-line build (no Gradle on PATH required):

```cmd
build-android.cmd
build-android.cmd clean :app:assembleDebug
build-android.cmd :app:installDebug
```

The launcher uses Android Studio's `android/local.properties` when present, otherwise searches SDK locations including `G:\sdk`. It finds the project's Gradle version in Android Studio's wrapper distribution cache, supports the Gradle wrapper when complete, and locates a configured JDK or Studio's bundled JBR. For a custom Gradle installation, set `ROOTS_GRADLE_BAT` to the absolute `gradle.bat` or `gradle.cmd` path. It forwards additional Gradle arguments and prints the APK path. No tools are installed silently or added to the system PATH. If the matching distribution is missing, sync the project in Android Studio first.

Open android/ in Android Studio. Use the Gradle version specified in android/gradle/wrapper/gradle-wrapper.properties, SDK 36, compatible Android Gradle Plugin/JDK, NDK and CMake 3.22.1. Generate the Gradle wrapper with installed Gradle if needed. Build :app:assembleDebug on an ARM64 device. The CMake build retrieves llama.cpp b11960 (PLaMo 3 tokenizer support). Existing X accessibility rendering, viewport and placement services are retained.

1. Open Roots and grant the in-app disclosure and Android Accessibility permission.
2. Tap Download local PLaMo model (~550 MB); Android DownloadManager downloads it over Wi-Fi. Once complete, test translation. The Android runtime uses the publisher-tested llama.cpp b11960; an existing verified GGUF can be reused. Inference failures now report the actual stage and native error rather than only IllegalStateException. Local model lifecycle events (no prompt or completion text) are recorded in app-private files/diagnostics/plamo-lifecycle.jsonl with one rotated predecessor.
3. On-device inference is default. Choose manual or automatic mode, target language and overlay theme.
4. To use desktop inference instead, start launch.cmd --lan on your PC, save http://YOUR-PC:8123 and the Roots token in the app, and explicitly enable "Use desktop host instead of on-device PLaMo". Disable that toggle to return to local inference. No silent fallback or transmission in local mode.

The local model and inference context remain resident; each request clears the context's memory before processing independent text. Android debug builds optimize native C/C++ inference code while retaining debug symbols/assertions. Lifecycle diagnostics include queue delay, context reuse/setup, tokenization, prompt prefill, generation, token counts and thread count. Device latency and memory usage must be measured; no particular speedup is guaranteed. To test, run the same short translation three times, then alternate two different samples to confirm state isolation. No prompt or output text is written to the lifecycle log.

### Independent native brain build

Windows: run `build-brain.cmd -Sdk G:\sdk` once to build the optimized ARM64 Release inference library using the installed NDK and CMake. Subsequent `build-android.cmd :app:installDebug` builds automatically package the cached `.so` instead of invoking native CMake. The cache lives at `android/.roots-brain/jniLibs/arm64-v8a/libroots_plamo.so`, outside Gradle's cleanable directories. A native-source or JNI change requires rebuilding the brain; `-ProotsUsePrebuiltBrain=false` selects the original integrated build, and `-ProotsUsePrebuiltBrain=true` requires the cache. `build-brain.cmd -Vulkan` builds the optional GPU profile with the host SDK prerequisites; `-NoKleidiAI` builds the baseline CPU profile. Build scripts are experimental and require verification on Windows/Android. See [inference runtime and autotuning design](design/inference-runtime.md).

### Android acceleration

Standard ARM64 builds enable llama.cpp's runtime-dispatched Arm KleidiAI kernels (including applicable Q4_0 quantized matrix kernels). No device-specific ISA is imposed on all compiled code. The initial automatic CPU thread setting uses up to six available processors; select 2, 4, 6, or 8 in Local inference acceleration to compare warm results. The app shows end-to-end sample latency and records prefill, generation, requested/actual CPU thread count, selected device, build capabilities and available CPU instruction flags in the local PLaMo lifecycle JSONL. A build flag or a supported instruction does not prove that every operation uses an accelerated kernel.

```cmd
build-android.cmd :app:installDebug
```

If the optional upstream KleidiAI dependency cannot build, `-ProotsEnableKleidiAI=false` restores the ordinary CPU build.

**Experimental Vulkan:** build with `-ProotsEnableVulkan=true` using a host Vulkan SDK containing recent Vulkan headers and glslc; Android NDK Vulkan loader is linked instead of the desktop loader. CMake resolves SPIRV-Headers from the SDK package or SDK headers, or fetches the pinned Khronos vulkan-sdk-1.4.350.0 header-only dependency if needed. You do not need to install a separate SPIRV-Headers package. On Windows, run from a Visual Studio developer command prompt with a host compiler available. If needed set `-ProotsVulkanSdk=<SDK path>`, `-ProotsSpirvHeadersDir=<directory containing SPIRV-HeadersConfig.cmake>` (optional override), and `-ProotsVulkanHostToolchain=<host toolchain file>`. Then explicitly enable Vulkan GPU in Roots' local inference settings and test the same sample two or three times. The Vulkan build targets Android API 28 (Android 9) or newer because the Vulkan 1.1 loader exports vkGetPhysicalDeviceFeatures2 starting at API 28. CPU-only builds retain API 26. The APK minimum SDK follows the selected native build profile, including the cached brain manifest. Existing older native caches must be rebuilt, and the API-28 Vulkan build uses a new, shorter build directory to avoid stale API-26 linker paths and reduce Windows object path lengths. The native runtime requests all model layers on a registered GPU; CPU may execute unsupported operations. No unsupported-device fallback to desktop or cloud occurs. Turn the Vulkan toggle off if GPU inference fails, produces invalid output, or is slower. Vulkan build and PowerVR driver behavior must be validated on-device before shipping.

Tensor G5's TPU is a separate backend path: Google Tensor SDK/LiteRT-LM requires a compatible converted/compiled model and tokenizer. The existing GGUF is not a TPU executable. Do not claim TPU support or measured GPU speedup from this implementation.

Android uses the existing eligibility heuristics and a CPU llama.cpp provider; desktop/Chrome uses the existing FastText model. GPU/NPU acceleration on Android and a native FastText mobile provider are follow-up work. The existing Android automatic overlay covers original text; Chrome continues to show translations underneath.

## Forge integration

Six Stars' transport-neutral ForgeActorReference, ForgeServiceRequest, ForgeServicePacket, correlation IDs and state update notices are retained in Wds.Roots.Api. IForgeService.InvokeAsync and TranslationForge expose language.detect and language.translate, independently of HTTP/Android/Chrome. Add other capabilities as operations/providers rather than embedding them in UI code.

POST /api/forge/v1/invoke with the existing X-SixStars-Token header:
```json
{"schemaVersion":1,"requestId":"example-1","operation":"language.translate","actor":{"forgeId":"roots","actorId":"local-user","actorType":"person"},"input":{"text":"こんにちは。","sourceLang":"ja","targetLang":"en"}}
```
Response: ForgeServicePacket with the same correlation ID and translation content. Existing /api/translation/v1/health, /detect, /translate and /socket are retained for the clients. LAN sockets authenticate with the token header; Chrome uses the existing initial auth frame.

## Source provenance

- Six Stars feature/translation-main: [570fb5d](https://github.com/bradgearon/the-six-stars/commit/570fb5d78c28b1b59e368e50c57ee30daaafd579). API, FastText, Chrome, Android accessibility module, regression tests and Forge packet contracts.
- Resone master: [9e34529](https://github.com/bradgearon/resone/commit/9e34529942b1365c55ebc5e159db12bc17edd59f). AI_ROOT, engine provisioning, download receipts, hardware selection, integrity and logging.
- PLaMo model publisher: https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF and https://huggingface.co/pfnet/plamo-3-translate-2b-GGUF. Android uses the 0.8B Q4_0 file, publisher revision 7de4409, and documented PFML3 prompt. Review the PLaMo Community License and register commercial use where required before publication.

## Verification

The source extraction and integration are committed. The Windows/.NET build, Android NDK build, actual model downloads, inference quality, and live X rendering still require execution on the target environment. Imported Android regression tests are included. The new desktop contract checks use a mock model response and need no model download.

```cmd
dotnet build src/Wds.Roots.Host -c Release
dotnet run --project tests/Roots.ContractChecks -c Release
```

Do not distribute until the native ABI, model digest, privacy/consent flows, and store requirements have been validated.

## Open architecture direction (design only)

Forge and Roots are intended open specifications; the WDS Roots application remains its own product. Independent applications may eventually exchange Trees and Branches through compatible files or APIs without depending on ChristEye or a proprietary SDK. The intended source/design reuse needs explicit licensing terms; the existing LICENSE is unchanged. See [openness](design/openness.md), [portable knowledge](design/portable-knowledge.md), [Network Brain and weighted feed](design/network-brain.md), and [accessible Garden journeys](design/listening-journeys.md). Engram AI is the reference for bounded artificial neural mesh, RNA tiles, Wind readouts and response assembly; these are proposed adaptations, not features already shipped in Roots. The translation instructions above are unchanged.

Further design: [root:// protocol](design/root-protocol.md), [Whirlwind peer exchange](design/whirlwind-exchange.md), [developing neural intelligence and poetic cognition](design/developing-neural-intelligence.md), and [optional economy and bounties](design/economy-bounties.md). These are proposals; no runtime, financial or licensing changes.
