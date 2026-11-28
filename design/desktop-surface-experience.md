# Roots Surface and Experience — desktop implementation plan

Status: proposed implementation contract, 2026-11-27. No runtime changes are implied by this document.
Copyright (c) 2026 We Develop Software LLC. All rights reserved. See [LICENSE](../LICENSE).

## 1. Product contract

Roots Surface is an interactive-user-session C++ desktop agent that acquires what the user is experiencing and optionally replaces the visual or spoken language in real time. Roots Experience compiles those observations into persistent Tree knowledge. They share one acquisition pipeline, but experience capture, visual translation, audio interpretation, and audio replacement are independently enabled.

The primary capture paths are:

- Browser: structured DOM, accessibility semantics, navigation and media information through the existing extension. Use the DOM for text and source identity before considering pixels.
- Native applications and games: Windows Graphics Capture (WGC) textures, application/window provenance and a separate authorized application-audio stream. UI Automation and supported game/media adapters enrich the stream instead of being prerequisites for it.

Capture can cover eligible foreground applications broadly once the user enables it; do not require an application-specific plugin. Application/site exclusions and budgets are policy, not hardcoded acquisition rules. A persistent indicator, pause/stop control, lock-screen suspension and protected/sensitive-content exclusions are required from the first release.

**Persist Tree records, not media recordings.** No continuous video, screenshot archive, raw audio files, encoded media segments, or persistent frame cache. Multi-image checkpoints consist of a few representative images in volatile memory, analyzed together to produce a textual semantic record. Persistent checkpoint images are not part of v1. Do not add an encoder, recording format, media database or screenshot directory. Optional persistent images, if ever requested, require a separate explicit design decision.

Personal experience remains local. Do not automatically promote it into social knowledge, public Axons, private bulk Mesh exchange, or cloud/peer inference.

## 2. Existing repository integration

| Existing implementation | Integration decision |
| --- | --- |
| native/tree-core/include/roots_tree.h and src/roots_tree.cpp | Reuse native Tree observations, grouped master records, query, agents, revisions and durable work API. The native Tree groups record.* with local:* keys. No new ABI required for first milestones. |
| src/Wds.Roots.Tree/NativeTree.cs | Existing managed binding is the host's storage interface. Add missing managed work bindings only when needed. |
| src/Wds.Roots.Host/Tree/DesktopTreeService.cs | Continue existing public/social/browser knowledge behavior. Its Catalog currently walks every fragment and branch: do not write personal experience into its database. |
| src/Wds.Roots.Host/Tree/DesktopMeshService.cs | Continue existing Mesh protocol; do not include the experience database in inventory, need, want, public exchange or automatic export. |
| src/Wds.Roots.Host/Tree/DesktopRootsHost.cs | Reuse authenticated loopback administration; add a separate Experience service and private queries. |
| src/Wds.Roots.Host/LocalTranslationGateway.cs | Preserve FastText detection, PLaMo translation and Forge operations; add independently scoped Surface/Experience operations. |
| chrome-extension/roots-translate/ | Preserve existing X reader and translation. Generalize through optional site adapters and visible DOM text ranges. Do not grant content scripts unrestricted host administration. |
| Android contextual-overlay | Reuse concepts of verified source geometry, content fingerprint vs display occurrence, revision fencing, language confidence and bounded work. Do not port Android-specific overlay implementations to Windows. |
| launch.cmd, scripts/Build-DesktopTree.ps1 | Keep established host startup/shutdown and model pins. Add a separate opt-in Surface build/launch command. |

Existing ports are unchanged: private inference 8081, LAN translation TLS 8223, local gateway 8224, Mesh TLS 8233. No new LAN listener, model replacement, llama.cpp/GGML/CUDA upgrade or automatic inference rebuild. Existing PLaMo model and engine configuration remain authoritative. Vision is an optional local provider. Speech acquisition and rendering reuse the existing Six Stars services and adaptive PCM playback behavior through a dedicated adapter (see [streaming speech contract](desktop-surface-speech.md)). Do not install duplicate ASR/TTS engines or alter the existing PLaMo configuration. Verify Six Stars service capabilities and compatible voice assets before implementation.

## 3. Proposed component layout

    native/desktop-surface/
      CMakeLists.txt
      core/                  # clock, identity, immutable envelopes, bounded queues
      launcher/              # roots-surface.exe, tray, interactive lifecycle
      platform/windows/      # foreground events, process identity, DPI/session state
      acquisition/wgc/       # frame pool and texture lifetime
      acquisition/uia/       # optional semantic enrichment
      acquisition/ocr/       # local OCR adapter
      scene/                 # changes, observations, temporal grouping, checkpoint selector
      compositor/            # DirectComposition, DirectWrite, software fallback
      audio/                 # WASAPI, VAD, playback clock, mixer
      adapters/              # optional game/media integrations
      transport/             # local host client, reconnect, acknowledgements
      diagnostics/
      tests/
    src/Wds.Roots.Host/Surface/
      SurfaceSessionService.cs
      SurfaceProviderRegistry.cs
      SurfaceWorkScheduler.cs
    src/Wds.Roots.Host/Experience/
      ExperienceTreeService.cs
      ExperienceIngestService.cs
      ActivityTimelineService.cs
      ExperienceQueryService.cs
      ExperienceEndpoints.cs
    chrome-extension/roots-translate/
      surface-dom.js          # generic opt-in site adapter, proposed
      surface-audio.js        # optional offscreen audio processing, proposed
      experience.html/js      # private daily/scene view
    scripts/Build-DesktopSurface.ps1
    build-desktop-surface.cmd
    launch-surface.cmd

Names are proposed, not preexisting files. Prefer C++20 for Surface, Win32/C++/WinRT and system SDK libraries. Reuse the existing MSVC/CMake installation and a standalone build/desktop-surface cache; do not bring inference into this CMake dependency graph. Keep pure state/contract tests portable where practical.

## 4. Runtime and ownership

The managed host owns durable storage, language/model providers, translation cache and scheduling. The native Surface process owns Windows capture handles, foreground tracking, audio capture/render endpoints, live scene state and the presentation surface. It runs with the ordinary interactive user's permissions, not as a system-wide service or elevated injector.

Start with a user-launched tray process; let the host optionally start/manage that same executable later. A launcher-owned child belongs to a kill-on-close job, not a process-name kill sweep. Graceful shutdown closes capture and audio immediately, restores original presentation and finalizes activity spans; bounded waits and explicit gap records cover unexpected termination. An independently launched or externally managed model/process is never killed by Roots.

The browser extension communicates through the existing authenticated loopback service worker. It can control Surface and send structured observations, but must not own native acquisition or direct access to a database. Browser native messaging is an optional launcher bridge, not needed for the first version.

Use a local control connection for text/events and a bounded, current-user-restricted local IPC channel for temporary media. Initial choice for native IPC: authenticated named pipe for control and PCM, and a bounded shared-memory/texture handoff only when a provider actually needs pixels. Do not send base64 screenshots through the existing translation HTTP endpoint. Provider workers must accept memory input and return structured outputs without file dumps. Keep capture alive if a model is busy; show explicit degraded state if the host is unavailable.

## 5. Acquisition, timing and source identity

### Activity (independent of recognition)

Track foreground-window changes via SetWinEventHook(EVENT_SYSTEM_FOREGROUND, out-of-context); reconcile against GetForegroundWindow. Associate HWND with GetWindowThreadProcessId and best-effort executable basename from QueryFullProcessImageName, process creation time, session and verified app identity. Never use PID, HWND or process name alone as a durable ID because they can be reused. Store a local device ID, run UUID and per-window lifetime ID. Use the current Windows user session only.

Use GetLastInputInfo for idle indication, not key capture. Lock/unlock, suspend/resume and session changes are boundaries; reconcile using WTS session notifications and power notifications. A sparse activity heartbeat/checkpoint records the last observed time. Persist closed intervals and bounded incremental progress; on restart close interrupted intervals at the last observation, mark the subsequent gap unknown. Do not count sleep, locked time or collector downtime as active work. Active foreground time is evidence, not proof of work or billable time. Distinguish focused, idle, media-playing (when observed), locked and unknown. Never infer keystrokes or productivity.

Use QueryPerformanceCounter (QPC) plus UTC correlation, a run-relative monotonic clock, and an explicit clock-adjustment marker if wall time jumps. Store UTC timestamps and monotonic durations; convert to the chosen time zone only for daily presentation. Activity lanes, audio and visual events may overlap, but foreground totals must not double-count overlapping intervals.

### Native visual lane

Use WGC on the active authorized app/window where supported. Use an authorized monitor capture only when needed for a scene spanning windows; attribution of regions must be explicit. Do not mistake every pixel on a monitor for the foreground process. No capture of secure desktops, passwords, protected content or other user sessions. Start with Windows 11 and feature-detect OS/adapter capabilities. Use the system capture picker where required; keep system/user-visible capture indicators and normal permissions.

FrameArrived obtains Direct3D11CaptureFrame, its ContentSize, Surface and SystemRelativeTime, then hands off an owned texture or bounded copy and releases the frame promptly. DirtyRegions can reduce work on supported OS versions; otherwise compare a small downsampled/tiled change map. Never read back the full texture merely to decide whether it changed. Track resizing, monitor moves, DPI, rotation, HDR/SDR, device loss, minimized/occluded targets, and capture loss. Skip unobservable content rather than bypassing restrictions.

The Windows agent should identify browser-owned windows so DOM-covered text is not OCR'd a second time. OCR for browser canvas/video/image areas is an opt-in visual fallback only. Exclude the Roots overlay and synthesized audio from capture, and suppress loops that would translate their own output. Prefer source-window capture; where display capture is used, apply supported self-exclusion or disable conflicting presentation if feedback cannot be excluded.

### Browser semantic lane

Generalize the existing visible X extraction with opt-in host permissions and explicit site policies. Observe visible content and structure using MutationObserver, IntersectionObserver, DOM Range.getClientRects and navigation/resize/scroll updates. Track tab/document/navigation identity and occurrence, source revision, origin and sanitized URL; keep actual post identifiers when provided by the DOM. Never infer a remote post ID from text. Keep query strings/fragments and raw page titles out of records unless sanitized and meaningful.

Ignore inputs, editable areas, passwords, hidden nodes, private message surfaces and restricted/secure pages. Do not collect browser storage, cookies, headers, clipboard, form values or underlying application state. Support permitted ordinary site text without requiring a separate plugin per site. Native window tracking supplies foreground timing; the extension refines that same interval into a browser site/activity instead of adding duplicate time. If browser permission is unavailable, record app-level activity and a coverage gap rather than silently broadening capture.

### Audio lane

Use authorized process-loopback WASAPI capture (target process tree, supported Windows versions); default excludes microphone and unselected/background audio sources. Browser tabCapture is a separate explicit user gesture/permission and uses an offscreen document/AudioWorklet. Do not use both native process loopback and tab capture for the same stream. Preserve original playback when interpretation is not enabled. A game/media adapter can supply a real dialogue track or subtitle when supported.

Original authorized PCM, sample index, channel/rate, QPC timestamp, source stream ID and discontinuities enter a bounded ring. Forward that stream as-is (lossless PCM with format metadata) to the Six Stars speech adapter; do not classify voices, separate speech, resample for ASR or cut the input into playback clips in Roots. Six Stars owns recognition, any required resampling/VAD, speaker segmentation and available voice classification. Roots receives incremental transcript/segment results and sends translated phrases to the Six Stars voice renderer. Initial voice choices are Astral Stream (male example) and Elusiaa (female example); a general voice/profile system is deferred. Verify that the provider exposes the required voice classification and voice assets; do not guess a speaker's gender from a name or a single acoustic characteristic. Unknown/ambiguous voice uses a user-configured fallback or original playback. Persist transcript text, language, time range, confidence and selected synthetic voice metadata only. Missing audio is an explicit gap. No audio samples in Tree, logs, spool, diagnostics or crash-upload artifacts controlled by Roots. No voice cloning without appropriate authorization. See [streaming speech contract](desktop-surface-speech.md).

## 6. Experience compiler and record contract

Hierarchy:

    run -> application session -> activity section -> scene
                                                   -> observation
                                                   -> semantic checkpoint
                                                   -> understanding

A session is a contiguous observed application interval. A section is a meaningful activity inside it. A scene is a visually/conversationally coherent period; game transitions, window/page changes, sustained visual change or topic shifts may open a new scene. An observation records one measured source text, dialogue segment, action or visual fact. A checkpoint summarizes a small ordered group of representative frames and timed transcript evidence. Understanding is a revisioned derived interpretation, never substituted for its original evidence.

Use distinct local keys such as local:experience:<device>:<run>:<kind>:<id>. A repeated sentence can share a content hash for translation, but each new occurrence receives its own observation ID/time. ID is not a guessed game or network ID. Parent IDs are typed local references, not a claim that the current native Tree already materializes every proposed relationship.

Proposed observation envelope (version 1; UTF-8 JSON for control/metadata only):

    {
      "version": 1,
      "eventId": "run-uuid:sequence",
      "type": "observation",
      "deviceId": "local-device-uuid",
      "runId": "run-uuid",
      "sessionId": "session-uuid",
      "sectionId": "section-uuid",
      "sceneId": "scene-uuid",
      "source": {
        "adapter": "windows.wgc.ocr",
        "process": "game.exe",
        "processInstance": "pid:creation-time",
        "windowInstance": "window-uuid",
        "origin": null
      },
      "time": {
        "startUtcMs": 1795824000000,
        "endUtcMs": 1795824004200,
        "startMonotonicUs": 1000000,
        "endMonotonicUs": 5200000
      },
      "payload": {
        "kind": "dialogue",
        "text": "The gate opens at dawn.",
        "language": "en",
        "confidence": 0.94
      },
      "evidence": {
        "method": "ocr",
        "frameSequence": 18,
        "sourceRevision": 3,
        "boundsPx": [120, 780, 680, 850],
        "provider": "provider-name-and-revision"
      }
    }

Frame sequence and source revision establish provenance; there is no saved image for that sequence. For ASR use audio sample ranges and provisional speaker labels. The host validates event kind, source, authorized scope, sizes, clock bounds, parent IDs and capture epoch. Assign stable event identity on acquisition; acknowledge after atomic commit, retry idempotently on transient failure. Never let an input payload specify an arbitrary Tree source namespace or sharing scope.

Map onto the existing NativeTree.Observation(source, externalId, body, language, observedMs) rather than creating a new store format. Proposed grouped roles:

| Source role | Body |
| --- | --- |
| record.experience.body | Readable original observation or activity/scene description |
| record.experience.kind | session, section, scene, observation, checkpoint, understanding |
| record.experience.application / process | Sanitized application and process identity |
| record.experience.origin | Adapter plus authorized sanitized page/site or game location |
| record.experience.parent | Typed parent reference (session/section/scene) |
| record.experience.interval | Versioned structured timing/state, UTC and monotonic duration |
| record.experience.dialogue | Original spoken/subtitle text associated with the observation |
| record.experience.evidence | Compact structured method, confidence, revision, bounds or sample range |
| record.experience.<lang>.translation / localized | Validated target-language text, no redundant translation if already local |
| record.experience.understanding | Revisioned readable inference linked to observation/checkpoint evidence |

Use bounded JSON metadata values and normalized text chunks at the actual native UTF-8 limits; one complete logical event must fit an atomic observe batch. Use stable per-event keys so the Tree's existing uniqueness constraint deduplicates retries without deleting separate real-world occurrences. An interval close or corrected understanding is a new immutable revision with supersession linkage. The timeline projection selects the latest valid revision and never sums both open and closed versions. Do not overload .body with every metadata field. Do not use the existing localized GGUF projection as the activity ledger: it selects language-bearing body/translation/understanding and is capped. Structured interval queries remain authoritative.

The host starts a dedicated ExperienceTreeService holding a second NativeTree handle at %LOCALAPPDATA%/Roots/Experience/experience.db. A single local query UI can present a unified view across both Trees. The experience database and its agents, projections, work queue, derived summaries and hashes are excluded from DesktopTreeService.Catalog and all Mesh inventory, want, need, public import/export and peer compute routes. Explicit later sharing should create a separately reviewed derived record in the shareable Tree; never publish the private DB or silently broaden current permissions. Add tests across both public and private exchange paths before enabling capture.

## 7. Semantic checkpoints and efficient processing

Do not send each frame to a vision model. Maintain small distinct lanes:

- Acquisition: latest-frame ownership, short-lived GPU textures; maintain a small bounded frame pool. Coalesce stale pending visual work.
- Change detection/tracking: compare dirty regions or downsampled tiles; update observed positions separately from semantic processing.
- OCR: run on changed text regions or selected whole-frame samples. Preserve source coordinates and confidence. Normalize text only for matching/classification, not original display.
- Scene compiler: select sparse representative frames based on content change and scene transitions; a checkpoint contains only textual results and time/evidence references after analysis.
- Audio: VAD continuously on authorized stream; ASR on bounded speech segments, ordered by time; emit gaps if real-time throughput cannot be sustained.
- Durable semantic jobs: once the original text/metadata is committed, existing Tree work claims/leases may be used for translation and summarization. They refer only to persisted text/metadata; never enqueue a durable job that depends on an expired image or PCM buffer.

Initial tuning budgets (proposals to measure on the user's Windows hardware, not claimed benchmark results):

| Work | Starting policy |
| --- | --- |
| Frame pool | 2-3 latest GPU frames per active source, explicit ownership and release |
| Change map | Low-resolution comparison at up to 10-30 Hz while motion is present; lower when stable |
| OCR | 1-2 Hz baseline, burst to 5-10 Hz on changing dialogue/text regions if budget permits |
| Checkpoint | Up to 3-4 diverse frames at bounded model resolution; normally one per meaningful scene/section transition; sparse periodic checkpoint for long scenes |
| Checkpoint lifetime | A short in-memory deadline (e.g. 15 seconds); drop/mark missing if provider unavailable |
| Audio | Small PCM ring (e.g. 10-20 seconds); continuously forward native PCM to Six Stars; no indefinite queue |
| Persistence | Small atomic observation batches and activity updates; store only semantic records |

Enforce independent byte/texture-count budgets, not just rates; account for original resolution, pixel format and outstanding consumers. Budget limits are configuration and capability dependent. Release all media references on stop, lock, source change and deadline expiry. No media file writes or background uploads. Provider-created diagnostic artifacts must be disabled. OS paging and crash dumps are separate system behavior; do not promise memory never reaches disk unless deployment explicitly addresses those controls.

The vision provider is local, optional and separate from PLaMo and Six Stars speech services. Begin with OCR and deterministic scene segmentation; add multi-image understanding after evaluating a pinned vision-capable model against fixture sequences. Record actual source facts separately from generated activity hypotheses, confidence and model revision. A generated summary cannot assert unseen actions or repair an unobserved hour by invention.

## 8. Visual replacement and audio presentation

### Compositor contract

Live display occurrence is distinct from translation content and persistent knowledge identity. Each presentation item includes source/window lifetime, element/occurrence ID, geometry revision, source text fingerprint, local-to-screen transform, visibility/clip and target language. An inference response carries the source fingerprint, revision and target language; it cannot reposition a surface. Apply it only to a current matching visible occurrence. Cached translations can populate multiple independently tracked occurrences.

Use browser DOM replacement or a separate anchored DOM layer where the source is structured. Keep original text/restoration state, avoid translating our own output, preserve selection and accessibility, and reconcile recycled nodes and layout reflows. On Windows use a non-activating transparent click-through window; D3D11/DirectComposition composition swap chain with Direct2D/DirectWrite as the primary backend. WARP/software backend is a tested fallback for supported desktop/borderless surfaces when hardware composition fails or measurable contention warrants it. CPU rasterization is not a substitute for GPU capture, and switching backend should use hysteresis rather than alternating on every frame.

Translate semantic elements directly when possible. For OCR-only applications draw bounded masks and shaped text over measured regions; don't claim to reconstruct a complex moving background exactly. When geometry becomes uncertain, retain only unchanged verified items or remove affected items; never shift all overlays based on a delayed scroll event, nested scroller, or a new model result. Batch atomic scene commits, compare actual paint, fence out-of-date frames. Coordinate transforms must account for DPI, client/frame offsets, monitor origin, capture size and resizing. Verify with synthetic fixtures, then live applications. Do not clear stable text because another item is newly discovered.

Support ordinary desktop windows and borderless fullscreen first. True exclusive fullscreen may require explicit supported engine/plugin integration; use compatible user-installed ReShade add-ons or game SDKs only after permission/license/anti-cheat compatibility review. Never bypass anti-cheat, DRM, capture protection or protected process restrictions. ReShade, XUnity.AutoTranslator and LunaTranslator are references/evaluation candidates, not dependencies approved for vendoring.

### Audio compositor contract

Pipeline: original authorized PCM -> Six Stars speech recognition and voice classification -> Roots FastText/PLaMo translation -> Six Stars streaming voice rendering -> one continuous adaptive PCM playback session. Keep the original transcript and generated translation aligned to the same dialogue observation. Speech synthesis and inference run asynchronously; audio device callbacks do not wait on them. For the initial implementation reuse Astral Stream and Elusiaa as example preset voices, selected by provider evidence, without a general profile subsystem.

Implement the continuous renderer around the proven Six Stars mobile SmartPcmPlaybackWriter and desktop SmartDirectSoundPcmPlaybackSession behavior. Maintain one persistent logical audio output, adaptive startup lead, measured first-chunk latency, production speed and inter-chunk jitter, playback-head accounting, ordered forward-only PCM commits and same-stream pause/refill. Do not play generated packets as separate clips. Keep generation ahead of playback and measure actual throughput; if the producer cannot sustain live input, degrade or pass through instead of accumulating an unbounded delay. See [the detailed streaming speech contract](desktop-surface-speech.md).

Offer original audio, interpretation/ducking and **suppress/replace speech** modes. In replacement mode the output is synthesized translated dialogue plus the original non-speech audio when a real separate dialogue track or verified separation is available. Native process loopback only captures audio; it cannot isolate or suppress the original voice by itself. A routed browser tab or explicitly routed native endpoint can replace the whole application mix; without a dialogue stem or reliable separation, full-original mute is a separately explicit option, not represented as speech-only suppression. Restore original routing and gain on stop, failure or device change. Do not promise exact audiovisual synchronization for an independently advancing live game; translated speech may require a short adaptive delay and safe late-result policy. Establish latency and underrun metrics before claiming real-time dubbing.

## 9. Host protocol and controls

Proposed versioned authenticated loopback routes under /api/experience/v1:
status, capture/policy, ingest, timeline, scenes, records, search, clear/export (semantic records only).
Proposed /api/surface/v1:
status, start/stop, target/backend/presentation policy, capability and diagnostics.

Use the existing loopback-only port and token verification conventions, separate route scopes/capture epochs, bounded requests and authorization checks before storage. Extension commands must be allowlisted to trusted extension UI; content scripts can submit only permitted observations from an active authorized document. Avoid arbitrary process launching, URL execution, raw media retrieval or network expansion from page messages. Native producer authorization is scoped to current user, current capture lease and host instance. Host supplies trusted ingestion time and validates metadata, with acquisition time retained separately. Reconnect resends only bounded semantic batches with stable event IDs. Raw media is never spooled. If the host remains unavailable, record an explicit coverage gap when reconnected rather than writing raw data elsewhere.

UI: tray and extension Tree page expose one visible master capture switch, pause/stop, active application/site, separate visual/audio options, capture exclusions and a concise status/diagnostics view. Experience view shows chronological activity, scenes, searchable text/dialogue, inferred sections and gaps. Use the current Tree/branch mechanisms where appropriate. Timesheet candidate reports aggregate focused observed intervals, display gaps, show inferred task labels with confidence and allow user correction/export. Do not automatically submit timesheets or label activity billable. Do not require translating every experience in order to retain it.

## 10. Implementation milestones and acceptance gates

Each milestone is a separately testable PR, not a parallel rewrite. Do not advance to broad capture before local-only persistence and pause/stop controls pass. Default launch and existing translation remain working even when optional Surface components are absent.

| Milestone | Scope and first deliverables | Acceptance gate |
| --- | --- | --- |
| S0 — design/contract | This plan, envelope fixture, ownership and threat model, test plan | Review requirements, decisions and exclusions; no runtime changes |
| S1 — local experience foundation | Native C++ launcher/tray, independent CMake build, foreground/idle/session tracker; managed ExperienceTreeService, typed ingestion and private timeline UI | Simulated and real app switches, idle/suspend/lock, crash/restart, DST/wall-clock change, dedup retries, accurate interval totals; existing Mesh catalogs contain zero experience records |
| S2 — memory-only visual acquisition | WGC texture lane, per-process provenance, bounded frame lifetime/change detector, OCR provider; existing DOM adapter integration and optional site permissions | Capture two desktop apps and an authorized borderless game, no persistent pixels/audio, no duplicate browser observations; validated text and source bounds; lock/pause/restart tests |
| S3 — visual presentation | D3D11/DirectComposition + DirectWrite, touch-through surface, software fallback, DOM replacement, existing FastText/PLaMo provider | Synthetic and live multi-DPI/multi-monitor, resize, repeated text, ad/layout reflow, late result, scroll, device loss and fallback tests. No stale overlay over another app; desktop/phone translation still works |
| S4 — experience compiler | Section/scene state machines, multi-frame in-memory selector, local vision provider contract, durable text work and daily timeline/search | Known fixture scenes retain observed text and time, correctly flag gaps and uncertain understanding; media buffers expire; report can reconstruct an activity interval without a screenshot archive |
| S5 — audio observation | Per-process WASAPI and optional Chrome tab adapter; original PCM stream to Six Stars recognition/voice-classification adapter; transcript persistence | Verify provider capability; separate authorized process audio, speech timestamps/coverage, no feedback or persistent PCM, no microphone capture by default; pause/stop and device changes restore normal behavior |
| S6 — translated speech | Six Stars voice renderer, Astral Stream/Elusiaa presets, adaptive continuous PCM playback, interpretation/ducking and explicitly routed suppress/replace mode | Language detection before translation; provider voice capability verified; adaptive startup/refill based on measured render speed, no per-chunk player restarts, normal original audio restored on failure, bounded lag and output routing |
| S7 — deeper integration | Optional approved fullscreen/game adapter, real dialogue stem/routed replacement, source-separation experiment | Per-game compatibility, license and user authorization review; independent optional builds; never bypass protected content or anti-cheat |

S1 is intentionally useful before OCR or voice: it solves the timesheet use case with deterministic activity records. S2 and S3 establish the shared visual pipeline. S4 adds scene understanding rather than merely screen OCR. S5 can be developed independently after the private ingest/clock contract is stable.

## 11. Verification and performance

Add pure native tests for source identity, clock boundaries, bounded queues, event ordering, segmentation, occurrence/revision arbitration and no stale paint. Add managed host checks for envelope validation, atomic dedup, interval projection, restart recovery, capture-off fencing, database separation and shutdown with open sockets/workers. Extend existing host and Tree checks without starting a model. Use synthetic test windows with known text/geometry and generated audio fixtures; do not retain real user captures as test assets. Browser fixture tests cover DOM recycling, nested quotes, navigation, accessibility, credentials and consent revocation.

Measure separately: frame callback time, texture memory, OCR throughput, model queue delay, ASR lag, TTS first audio, compositor frame latency, GPU/CPU usage, foreground timing accuracy, Tree bytes per hour, dropped observations and idle overhead. Target quick startup, stable bounded memory, minimal interruption to active app/game, and zero Roots-owned raw-media files. Choose frame budgets and model/provider profiles from measurements. Prioritize visible translation and immediate speech over historical summarization; keep activity timing cheap even under severe load. Backpressure discards optional unprocessed frames rather than blocking the compositor, input or audio thread. Record coverage gaps, not fabricated results.

Private data controls are acceptance criteria: opt-in capture, visible state, exclusions for sensitive/credential surfaces, no hidden capture of secure/private communications, user-controlled retention/deletion, no cross-user sessions, no logging raw captured content, no media archive, no experience records exposed in any default peer route. Pause/clear increments capture epoch and fences all in-flight results. A deletion operation covers private DB, derived semantic projections and job results; do not promise forensic erasure of OS-managed copies. The experience store stays local even when LAN translation and Mesh are enabled.

## 12. Platform references and unresolved decisions

These are API and integration references, not imported code or new dependencies:

- [Windows Graphics Capture](https://learn.microsoft.com/en-us/windows/apps/develop/media-authoring-processing/screen-capture) and [frame metadata](https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.direct3d11captureframe): texture acquisition, QPC timing, version-gated dirty regions.
- [DXGI composition swap chain](https://learn.microsoft.com/en-us/windows/win32/api/dxgi1_2/nf-dxgi1_2-idxgifactory2-createswapchainforcomposition): DirectComposition rendering path.
- [Windows process-loopback audio sample](https://learn.microsoft.com/en-us/samples/microsoft/windows-classic-samples/applicationloopbackaudio-sample/): capture target process tree, not native replacement.
- [Chrome tabCapture](https://developer.chrome.com/docs/extensions/reference/api/tabCapture/) and [offscreen media guide](https://developer.chrome.com/docs/extensions/how-to/web-platform/screen-capture): authorized tab media routing.
- [ReShade](https://github.com/crosire/reshade), [LunaTranslator](https://github.com/HIllya51/LunaTranslator), [XUnity.AutoTranslator](https://github.com/bbepis/XUnity.AutoTranslator): candidate integration/reference projects. Review upstream licenses before use in proprietary Roots.

Decisions pending implementation evidence: local OCR/vision providers and model pins; Six Stars streaming recognition/voice-classification and voice asset capabilities; audio adapter contract compatibility; GPU/CPU resource thresholds; app-specific capture reliability; actual exclusive fullscreen compatibility; native virtual audio endpoint approach; structured long-term retention settings; eventual explicitly authorized experience sharing. Until those gates are satisfied, no speculative media persistence or cross-device sharing. Existing host/inference configuration, Android and Mesh wire protocol remain unchanged by this plan.
