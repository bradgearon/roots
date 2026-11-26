# Roots

**Copyright (c) 2026 We Develop Software LLC. All rights reserved.** Roots-owned software and documentation are proprietary; see [LICENSE](LICENSE). Third-party components retain their own applicable licenses.

See the [Roots design area](design/README.md) for the proposed ChristEye [Tree of Life](design/tree-of-life.md), [Honeycomb and Whirlwind](design/honeycomb-whirlwind.md), [Garden participation](design/garden.md), recognition, micro-cognition, deliberate writing, agents, ratings, availability and native web presence. These are design proposals, not implemented runtime features.

Independent local-first translation and social integration platform. The initial code is extracted directly from Six Stars and Resone, not a replacement implementation of the X integration. No game, companion, Gemma, ASR or TTS dependencies are needed to use translation.

## Windows desktop host

Install the .NET 9 SDK and Visual C++ 2015–2022 runtime, then run from this checkout:

```cmd
launch.cmd
```

The launcher builds the Roots host and its API, downloads the small FastText model, then uses Resone's AI_ROOT resolution, hardware selection, engine pack installer and resumable model provisioner. The default desktop model is the original PLaMo 3 Translate 2B Q4_K_M (about 1.81 GB). To choose the smaller 0.8B version, change the enabled model in config/runtime.json; enable exactly one LLM model. Model digest comes from the publisher's HTTPS X-Linked-Etag if no SHA-256 has been pinned in the manifest. Pin the digest before production; first-run digest retrieval requires network access. The private llama.cpp server listens on 127.0.0.1:8081, Roots gateway on 127.0.0.1:8223. The console displays the token path (%LOCALAPPDATA%/Roots/translation-token.txt). The desktop bridge uses port 8223; the private model still uses 8081, so do not run another model server on 8081 concurrently.

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
4. Start `launch.cmd --lan` and run `get-key.cmd` on your desktop. Save `http://YOUR-PC:8223` and the desktop key in Android Settings → Desktop bridge. Enable automatic paired compute. The current network is remembered on save; no idle host probes.

The local model and inference context remain resident; each request clears the context's memory before processing independent text. Android debug builds optimize native C/C++ inference code while retaining debug symbols/assertions. Lifecycle diagnostics include queue delay, context reuse/setup, tokenization, prompt prefill, generation, token counts and thread count. Device latency and memory usage must be measured; no particular speedup is guaranteed. To test, run the same short translation three times, then alternate two different samples to confirm state isolation. No prompt or output text is written to the lifecycle log.

### Predictive translation scheduling

Automatic translation ranks already-discovered visible X posts by expected usefulness when inference finishes, using measured scroll direction/velocity and a bounded latency estimate for the selected CPU, Vulkan or desktop profile and input-length bucket. At rest, readable bodies precede display names, then recent previously seen content may be processed as a lower-priority rollup (up to 15 seconds since last observation). During rapid movement, defer candidates projected to leave before completion. There is one outstanding overlay request; remaining work stays in a reprioritizable pool rather than the provider FIFO. A manual tap takes the next free slot. Estimates adapt in memory and do not determine rendering coordinates. After an uncertain scroll, only a fresh accessibility snapshot restores coordinate trust. Completion publishes translation text without republishing older geometry. The activity dot still reflects the actual provider-start callback.

The prediction horizon is bounded to 1.5 seconds, and discovery remains limited to the existing authorized visible-node reader. This is not concurrent CPU/GPU execution or access to undisclosed offscreen posts. Compare the selected backends on a device; a slower one automatically looks farther toward incoming content. For a metadata-only scheduling trace, use `adb logcat -s RootsTranslationSchedule:D "*:S"` (direction, lane, estimated/actual elapsed time, and visibility; no post text or identifiers).

### Independent native brain build

Windows: run `build-brain.cmd -Sdk G:\sdk` once to build the optimized ARM64 Release inference library using the installed NDK and CMake. Subsequent `build-android.cmd :app:installDebug` builds automatically package the cached `.so` instead of invoking native CMake. The cache lives at `android/.roots-brain/jniLibs/arm64-v8a/libroots_plamo.so`, outside Gradle's cleanable directories. A native-source or JNI change requires rebuilding the brain; `-ProotsUsePrebuiltBrain=false` selects the original integrated build, and `-ProotsUsePrebuiltBrain=true` requires the cache. `build-brain.cmd -Vulkan` builds the optional GPU profile with the host SDK prerequisites; `-NoKleidiAI` builds the baseline CPU profile. Build scripts are experimental and require verification on Windows/Android. See [inference runtime and autotuning design](design/inference-runtime.md).

### FunctionGemma local micro-cognition

The native brain now has a separate resident FunctionGemma 270M model/context slot alongside PLaMo. The two models share one bounded serialized inference queue, not weights or attention state. FunctionGemma uses the optimized CPU backend initially; PLaMo's CPU/GPU setting is unchanged. Rebuild the native brain once after this JNI change (use `-Vulkan` if desired), then install the app. Android settings contains a separate download and editable operation-proposal test, plus a release-memory button. The ggml-org Q8_0 GGUF (292 MB, revision aa87651, SHA-256 verified before load) is downloaded only on explicit consent to [Gemma terms](https://ai.google.dev/gemma/terms). Model weights are not committed or included in the APK.

The native FunctionGemma control-token format offers four bounded operations: search_knowledge(query), compare_meanings(left,right), propose_displacement(source,target,property), abstain(reason). The first completed call is parsed against an immutable allowlist and exact string argument schemas; invalid or unknown calls fail closed. These are proposals only: no Forge call, Tree mutation, search, shell command, network request or automatic X content processing is performed. Input stays local. The app shows the proposal and timings, and `files/diagnostics/functiongemma-lifecycle.jsonl` records phase timings and validation status without prompt/output/arguments. A syntactically accepted call is not proof of semantic correctness. Use explicit knowledge lookup and metaphor/displacement examples and irrelevant/literal negative controls before evaluating fine-tuning. See [FunctionGemma operator design](design/functiongemma-operator.md).

### Android acceleration

Standard ARM64 builds enable llama.cpp's runtime-dispatched Arm KleidiAI kernels (including applicable Q4_0 quantized matrix kernels). No device-specific ISA is imposed on all compiled code. The initial automatic CPU thread setting uses up to six available processors; select 2, 4, 6, or 8 in Local inference acceleration to compare warm results. The app shows end-to-end sample latency and records prefill, generation, requested/actual CPU thread count, selected device, build capabilities and available CPU instruction flags in the local PLaMo lifecycle JSONL. A build flag or a supported instruction does not prove that every operation uses an accelerated kernel.

```cmd
build-android.cmd :app:installDebug
```

If the optional upstream KleidiAI dependency cannot build, `-ProotsEnableKleidiAI=false` restores the ordinary CPU build.

**Experimental Vulkan:** build with `-ProotsEnableVulkan=true` using a host Vulkan SDK containing recent Vulkan headers and glslc; Android NDK Vulkan loader is linked instead of the desktop loader. CMake resolves SPIRV-Headers from the SDK package or SDK headers, or fetches the pinned Khronos vulkan-sdk-1.4.350.0 header-only dependency if needed. You do not need to install a separate SPIRV-Headers package. On Windows, run from a Visual Studio developer command prompt with a host compiler available. If needed set `-ProotsVulkanSdk=<SDK path>`, `-ProotsSpirvHeadersDir=<directory containing SPIRV-HeadersConfig.cmake>` (optional override), and `-ProotsVulkanHostToolchain=<host toolchain file>`. Then explicitly enable Vulkan GPU in Roots' local inference settings and test the same sample two or three times. The Vulkan build targets Android API 28 (Android 9) or newer because the Vulkan 1.1 loader exports vkGetPhysicalDeviceFeatures2 starting at API 28. CPU-only builds retain API 26. The APK minimum SDK follows the selected native build profile, including the cached brain manifest. Existing older native caches must be rebuilt, and the API-28 Vulkan build uses a new, shorter build directory to avoid stale API-26 linker paths and reduce Windows object path lengths. The native runtime requests all model layers on a registered GPU; CPU may execute unsupported operations. No unsupported-device fallback to desktop or cloud occurs. Turn the Vulkan toggle off if GPU inference fails, produces invalid output, or is slower. Vulkan build and PowerVR driver behavior must be validated on-device before shipping.

Tensor G5's TPU is a separate backend path: Google Tensor SDK/LiteRT-LM requires a compatible converted/compiled model and tokenizer. The existing GGUF is not a TPU executable. Do not claim TPU support or measured GPU speedup from this implementation.

The Tree Core is enabled by default and compiled separately when the brain is cached. Android CMake automatically downloads and verifies the pinned SQLite amalgamation into android/.roots-deps; use -ProotsSqliteAmalgamationDir=<directory> or ROOTS_SQLITE_DIR to override, or -ProotsEnableTree=false for a translation-only build. Switching branches with different native sources invalidates the cache; rerun build-brain.cmd if requested.

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

## Native Tree Core (experimental)

The [shared C++ Tree Core](native/tree-core/README.md) is the first implementation slice for scroll observation storage, composed predicates, exact search, branch agents and GGUF compilation. The experimental Android JNI path adds a separately opted-in local capture lane and searchable paginated Tree viewer; the desktop P/Invoke binding exposes a standalone CLI without starting translation. Follow the native core build and verification instructions, including the optional Android SQLite amalgamation. Platform builds and live capture remain unverified. See [Lily Tendrils](design/tendrils.md) for the separate proposed semantic-growth architecture.


### Export Android knowledge and diagnostics

From the repository root with an authorized ADB-connected debug device:

```cmd
export-android.cmd
export-android.cmd -LogsOnly
export-android.cmd -Serial YOUR_DEVICE_SERIAL
```

The default export copies the private Tree database, any WAL/journal sidecars, the latest GGUF and lifecycle logs without stopping Roots. It verifies the source hashes before and after the copy and compares the local files; if the database changes, it retries and refuses to package an inconsistent set. The transient SHM index is regenerated by SQLite. Switch to Roots from X if the database is busy. Force-stopping a package can disable its Android accessibility service, so the exporter no longer force-stops the app. The logs-only variant does not stop the app; logs may change during copying. Each run writes to a unique ignored `logs/device-exports` directory with a SHA-256 manifest. The GGUF is a derived snapshot and can be older than the SQLite database. Keep SQLite sidecars beside the database when opening a copy. No inference model weights, preferences, access keys or unrelated app data are exported. Missing files are reported. Exports can contain private acquired text; store them securely. `-OutputDir` and `-Adb` allow explicit overrides. Debug builds must support `adb shell run-as com.wds.roots`.

The export also includes `tree-lifecycle.jsonl` and its rotated predecessor when present. By default it creates a ZIP alongside the export directory, preferring 7-Zip on PATH or in its standard Windows install locations, otherwise using .NET ZIP compression. Pass `-SevenZip C:\path\to\7z.exe` to override or `-NoZip` to retain only the directory. The archive contains private local observations; it is not encrypted.
