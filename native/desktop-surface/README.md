# Desktop Surface — first implementation slice

Build and run on Windows 11 with Visual Studio 2022 C++/CMake, a recent Windows SDK
including C++/WinRT headers and .NET 9. The normal Roots launcher, existing
translation services, Android, Axon, Mesh and model pins are not changed.

From the repository root:

```cmd
build-surface.cmd
launch-surface.cmd
launch-surface.cmd --status
launch-surface.cmd --search "gate"
```

Start the watcher once; explicitly launching it enables capture immediately. It automatically
follows the currently focused eligible application; it never launches, injects
into, or controls that application. No executable name is required. Switch
between already-running programs normally. The foreground window is the initial
capture scope, not every monitor, occluded/background window or entire desktop.

Optional controls: `launch-surface.cmd --capture --exclude private-app.exe`
adds an exclusion; repeat --exclude for multiple executables.
`launch-surface.cmd --capture --no-ocr` uses UI Automation only.
By default the worker uses two independent reading paths. Cached UI Automation
queries read visible labels and read-only text; a bounded control walk is
available if a provider rejects cached queries. OCR reads the actual foreground
window pixels, including rendered page text, games, images, and canvas text.
A separate accessibility safety query identifies editable/password rectangles.
Those regions are blanked before recognition; they no longer suppress the whole
window. If the safety query or coordinate mapping fails, OCR is skipped rather
than reading unredacted pixels. The structured reader can continue. Full-resolution
overlapping tiles support 4K and large windows within a 16-megapixel frame budget.
Screen changes are fingerprinted only in memory and identical frames are skipped.

### Local OCR engine

The native worker uses an installed 64-bit Tesseract 5 engine when available
(Apache-2.0; upstream installation information:
https://github.com/tesseract-ocr/tessdoc/blob/main/Installation.md).
It looks in `%ProgramFiles%/Tesseract-OCR` or an absolute trusted
`ROOTS_TESSERACT_DIR`. It loads the C API and language data once and passes
redacted grayscale pixels in memory, not image files. English is the default;
set `ROOTS_OCR_LANG=eng+jpn` for multiple installed traineddata languages.
On a normal capture launch, `launch-surface.cmd` checks for the installed engine
and English data. If absent, it provisions the pinned UB Mannheim Tesseract 5
package through Windows Package Manager. Windows may require approval for the
first installation. An existing configured installation is never overwritten.
`--status`, `--search` and `--no-ocr` skip provisioning. If installation is
unavailable or fails, capture continues with the existing local backends.

If Tesseract is absent, Roots attempts the existing Windows.Media.Ocr backend.
Microsoft officially supports this API in packaged desktop applications; an
unpackaged executable may not have a usable engine. If the console reports
`no-ocr-engine`, install the upstream Windows Tesseract 5 distribution, e.g.
`winget install --id UB-Mannheim.TesseractOCR --exact`, then restart the watcher.
No rebuild is needed for this optional backend. Neither backend sends media to a
network service. The console displays process IDs, visible UIA counts, mask
counts, OCR coverage/backend status, and periodic committed-event totals;
it does not print captured text. Use the existing Experience viewer or
`launch-surface.cmd --search "example"` to read stored observations.

The current scope is the active foreground window (including its displayed
portion on multiple monitors). Whole-desktop simultaneous monitoring,
background-window acquisition and visual scene understanding remain separate
work. No process-name blacklist has been reintroduced.

This milestone is experimental, not a broad desktop capture release. Keep the
visible console open and press Ctrl+C to stop. Pause or stop capture before opening private content; OCR cannot reliably
recognize every sensitive surface. There are no built-in process-name exclusions.
The optional --exclude argument is the only name-based exclusion. The collector
still checks foreground visibility, current Windows session, secure desktop and
capture protection, and fences sensitive accessibility controls. Browsers are
eligible for this same native policy; the planned authorized DOM lane will
provide richer browser text without duplicating native observations. No key/clipboard/microphone collection, no injection
or protected capture bypass. Use an ordinary desktop/borderless game.

The native agent monitors foreground transitions, verifies process creation time
and window identity, checks lock/input desktop, records bounded focused/idle
spans, and samples static text at approximately 1 Hz. It revalidates the source
before forwarding any observation. UIA queries, fallback traversal and the WGC frame pool are bounded,
and OCR uses temporary memory only. Input idle status is recorded independently of text observation: an unlocked
screen may still be presenting a cutscene or movie. Secure desktop and lock
boundaries still suspend capture. Active time is not work/billable time.
No screenshot, recording, raw PCM or image archive is created. Repeated visible
text at the same coordinates is suppressed until it changes/disappears or the
source changes.

The independent managed receiver launches the native child, owns its inherited
anonymous pipes and local database, validates source and time fields and groups
body/process/parent/interval/evidence roles into atomic NativeTree batches.
The private database is %LOCALAPPDATA%/Roots/Experience/experience.db, not the
existing social Tree. No LAN endpoint, automatic network transfer or Mesh
registration is added. The console search command is an explicit local display
of committed text. Start the regular Roots host with launch.cmd as well, reload
the Chrome extension, and open its existing Tree page. The Knowledge source
selector switches between social/received knowledge and private desktop
Experience. The default Experience view is **Screen groups**, with date
headings and newest observation time first. A screen group combines all text
observations emitted by the same foreground reading pass; each original
observation retains its identity, coordinates, source and weights. Legacy
observations group by session and timestamp, without migrating or deleting
stored records. Search and branch filtering return the entire matching screen
for context. Grouped records and raw fragments remain available, including
activity intervals. All views page in descending observation time using a stable
sequence tie-breaker. Repeated unchanged text remains deduplicated, so a group
is a reading pass's observations, not a saved image or guaranteed complete
transcription of every pixel. Live refresh, search, scoped branches,
and native 128-component lexical weight inspection work across both sources.
Experience branch membership uses the same literal-matching native agents;
new observations join matching branches automatically. Compile private Experience
GGUF writes only %LOCALAPPDATA%/Roots/Experience/experience.weights.gguf,
with a recorded revision and no exchange integration. These are lexical feature
weights, not learned semantic relationship strengths. The watcher remains
independent of the existing PLaMo launch and TLS configuration.
It does not yet materialize full section/scene graphs or reconcile all unobserved
intervals, has a visible console rather than a tray, and has no retention UI.
The view can read during capture. Stop the watcher and host before removing private data. OS-managed paging/crash
behavior is outside the collector's guarantees.

## Audio: Inworld realtime adapter

The chosen initial separator is Inworld Voice Isolator v1 realtime (Apache-2.0,
48 kHz mono). The upstream package version is pinned to 1.0.0; model registry
checksums are verified by the publisher's loader. This reference worker is
Python/PyTorch, not GGUF; native conversion is not a prerequisite for testing.
Create a dedicated virtual environment, select the matching PyTorch CPU/CUDA
wheel, and install audio/requirements-inworld.txt explicitly. The ordinary
Surface and Roots launchers never install it, download weights or start it.

audio/inworld_worker.py implements a binary, memory-only streaming transport.
It loads once, opens one model streaming session, accepts little-endian float32
mono blocks (normally 960 samples / 20 ms) and returns final speech and
sample-aligned residual pairs. The default lookahead is 2 frames / 20 ms;
startup handshake reports the model's actual algorithmic latency in samples.
The reference 63 ms excludes inference, scheduling, transport and speech
translation. CPU/CUDA selection is explicit, a backlog is bounded to two
seconds, and explicit EOS flushes only real input. EOF cancels. The worker
requires cached weights or an explicit local safetensors path unless the
operator selects --allow-download. Do not use --allow-download in the live lane.

Source sample indices allow the caller to align the residual with original PCM.
Voice plus residual reconstructs the aligned mono input, within floating-point
roundoff. Inworld estimates foreground voice, not all speakers, and can remove
or retain portions of music/effects or overlapping dialogue. Stereo preservation
requires a separate evaluated method; do not simply discard stereo. Existing
Six Stars ASR must receive the original authorized stream as specified in the
speech contract (isolated speech may be an explicitly evaluated optional input).
Only the replacement mix uses the separation lane.

This worker is **not yet wired to WASAPI**, a virtual audio route, or Six Stars.
The next runtime work is process-loopback capture with timestamps, persistent
local speech adapter, stable translated phrases and the adaptive PCM renderer.
Native loopback is read-only; selective audible suppression needs an authorized
routing endpoint. Until that path is verified, do not mute the original app.
Normal translation and original audio are unchanged.

Model-free protocol fixtures:

```cmd
cd native\desktop-surface\audio
python -m unittest -v test_inworld_worker.py
```

The Windows CI workflow compiles the native/managed components and runs these
fixtures, but live UIA/OCR, GPU throughput, real model streaming, audio routing
and privacy edge cases still require target-device testing. The initial console
watcher is not a finished compositor, full scene-understanding system, or automatic
translation of live desktop speech.

References: [Surface plan](../../design/desktop-surface-experience.md),
[Six Stars speech contract](../../design/desktop-surface-speech.md),
[Inworld upstream](https://github.com/inworld-ai/voice-isolator),
[Microsoft WGC](https://learn.microsoft.com/en-us/windows/apps/develop/media-authoring-processing/screen-capture).

### Browser Experience, dialogue and backups

Reload extension 0.4.6 and choose Experience → Enable visible webpage reading.
Grant Chrome's requested site access once. It uses visible top-level page text,
not hidden DOM, form values, browser history, or background tabs. The host also
checks the live Surface lease and foreground Chromium window. The setting can
be turned off in Experience. Restricted browser pages, incognito, cross-origin
frames and inaccessible or protected content are outside this lane.

Game subtitles still use the native OCR backend. Spoken-only conversations are
not transcribed by this build; that requires the pending process-loopback audio
capture and speech recognition pipeline. Consecutive screen observations from a
single application session are grouped into time-based sections, not inferred
semantic scenes. Exact text repeats retain their individual timestamps and
appear as occurrence counts in the weight details.

Run `launch-surface.cmd --backup` to create a consistent, self-contained
`experience.backup.db`. Surface also makes a rolling backup after one minute
and every 15 minutes if the revision changed. SQLite's online backup API reads
committed WAL content and verifies the copy before publishing. Do not upload a
raw copy of the main DB while capture is running. Default diagnostic output is
written to `surface-diagnostics.txt`; use `--diagnostics` for console output.

Compile private Experience GGUF publishes both `experience.weights.gguf`
(full lossless metadata/weights) and `experience.content.gguf` (observed text
only, with structural screen/session links and exact-repeat relations). Both
files contain their own internally consistent revision. A newer live revision
no longer invalidates a completed snapshot. The content vectors remain
lexical, not trained semantic embeddings. These files and the rolling backup
are private, local artifacts and do not enter Mesh or Axon exchange.

### Scroll-aware captured webpage reader

Click a screen card or individual preview to expand the complete stored text.
Browser screen cards have a separate **Read captured webpage in page order**
reader. The capture lane samples after scrolling settles as well as periodically.
It records document-relative positions (including enclosing scroll offsets)
and stable visible text-node span anchors. The host checks durable first-seen
identities and same-position text hashes before inserting a line, so overlapping
viewports and scrolling back do not add the same observed text repeatedly.
New lines still form a chronological screen group; page reading uses a separate
top-to-bottom order and a stable forward cursor. Repeated text elsewhere on
the page can remain a distinct observation. Browser order is approximate for
virtualized/reflowing pages, complex columns or sticky overlays; positions record
first sight, not a hidden DOM dump. A navigation/reload or new Surface run starts
a new document identity. No attempt is made to reconstruct unobserved text or
historical page positions from old records. Native OCR/UIA continue to retain
separate screen occurrences; their within-screen display is sorted by coordinates
inside each acquisition lane.

### Pause or resume Experience capture

The Tree page and toolbar popup have a separate **Pause Experience capture** /
**Resume Experience capture** button. This master switch governs native UIA/OCR,
foreground activity and authorized browser DOM capture. It does not change the
X-post Knowledge capture setting, Mesh exchange, translation, or saved data.
The setting is saved in the local Experience directory and survives restart.
Pausing stops the owned native reader and blocks new writes through the shared
capture epoch; a paused receiver remains ready to resume. Wait until the UI
reports **Paused** (not **Pausing**) before exposing content you want to keep out
of Experience. Ctrl+C still fully stops the Surface receiver and its child.

The same control works without the browser or Roots host:

```cmd
launch-surface.cmd --pause
launch-surface.cmd --status
launch-surface.cmd --resume
```

If the watcher has already stopped, resume only enables the setting; run
`launch-surface.cmd` to start the receiver. The first upgrade requires stopping
the old watcher, rebuilding, and reloading the extension and webpage tabs.
An older watcher is explicitly reported as not supporting pause.
