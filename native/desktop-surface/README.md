# Desktop Surface — first implementation slice

Build and run on Windows 11 with Visual Studio 2022 C++/CMake, a recent Windows SDK
including C++/WinRT headers and .NET 9. The normal Roots launcher, existing
translation services, Android, Axon, Mesh and model pins are not changed.

From the repository root:

```cmd
build-surface.cmd
launch-surface.cmd --capture MyGame.exe --ocr
launch-surface.cmd --status
launch-surface.cmd --search "gate"
```

Replace MyGame.exe with an executable you explicitly authorize. Without --ocr,
the native worker reads only visible static UI Automation text. --ocr enables
the WGC/OCR fallback for windows with no usable semantic text, provided the
accessibility traversal completes and does not expose editable, password or
document controls. It uses the Windows OCR engine from installed profile
languages, not a new LLM. If an OCR language pack, GPU or capture permission is
missing, the window can remain uncovered. Windows OCR coordinates are capture
pixels; UIA coordinates are desktop pixels. Neither is yet used to paint.

This milestone is experimental, not a broad desktop capture release. Keep the
visible console open and press Ctrl+C to stop. The allowed executable names are
explicit on every start. Avoid private or confidential content in selected
applications, especially when testing OCR; a pixel OCR engine cannot reliably
recognize every sensitive surface. Common browsers, terminals, password managers,
credential UI and Roots processes are excluded from the native lane. The later
browser lane will use the existing extension and authorized DOM rather than
duplicating native collection. No key/clipboard/microphone collection, no injection
or protected capture bypass. Use an ordinary desktop/borderless game.

The native agent monitors foreground transitions, verifies process creation time
and window identity, checks lock/input desktop, records bounded focused/idle
spans, and samples static text at approximately 1 Hz. It revalidates the source
before forwarding any observation. UIA traversal and WGC frame pool are bounded,
and OCR uses temporary memory only. The initial implementation does not capture
while the input idle threshold is exceeded; active time is not work/billable time.
No screenshot, recording, raw PCM or image archive is created. Repeated visible
text at the same coordinates is suppressed until it changes/disappears or the
source changes.

The independent managed receiver launches the native child, owns its inherited
anonymous pipes and local database, validates source and time fields and groups
body/process/parent/interval/evidence roles into atomic NativeTree batches.
The private database is %LOCALAPPDATA%/Roots/Experience/experience.db, not the
existing social Tree. No LAN endpoint, automatic network transfer or Mesh
registration is added. The console search command is an explicit local display
of committed text. This separate receiver is a staging boundary before integration
into the existing managed Roots host; it avoids touching launcher/TLS logic.
It does not yet materialize full section/scene graphs or reconcile all unobserved
intervals, has a visible console rather than a tray, and has no retention UI.
Stop capture before inspecting or removing private data. OS-managed paging/crash
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
watcher is not a finished compositor, full Experience UI, or automatic
translation of live desktop speech.

References: [Surface plan](../../design/desktop-surface-experience.md),
[Six Stars speech contract](../../design/desktop-surface-speech.md),
[Inworld upstream](https://github.com/inworld-ai/voice-isolator),
[Microsoft WGC](https://learn.microsoft.com/en-us/windows/apps/develop/media-authoring-processing/screen-capture).
