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

Open android/ in Android Studio. Use the Gradle version specified in android/gradle/wrapper/gradle-wrapper.properties, SDK 36, compatible Android Gradle Plugin/JDK, NDK and CMake 3.22.1. Generate the Gradle wrapper with installed Gradle if needed. Build :app:assembleDebug on an ARM64 device. The CMake build retrieves llama.cpp b10724. Existing X accessibility rendering, viewport and placement services are retained.

1. Open Roots and grant the in-app disclosure and Android Accessibility permission.
2. Tap Download local PLaMo model (~550 MB); Android DownloadManager downloads it over Wi-Fi. Once complete, test translation.
3. On-device inference is default. Choose manual or automatic mode, target language and overlay theme.
4. To use desktop inference instead, start launch.cmd --lan on your PC, save http://YOUR-PC:8123 and the Roots token in the app, and explicitly enable "Use desktop host instead of on-device PLaMo". Disable that toggle to return to local inference. No silent fallback or transmission in local mode.

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
