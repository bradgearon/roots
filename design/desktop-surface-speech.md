# Roots Surface — Six Stars streaming speech contract

Status: proposed companion to [Desktop Surface and Experience](desktop-surface-experience.md), 2026-11-27. No runtime code or model configuration changes.
Copyright (c) 2026 We Develop Software LLC. All rights reserved.

## 1. Architecture decision

Roots captures the authorized application's original audio stream and sends it to the Six Stars speech service as-is, with format and timing metadata. Roots does not need its own separate ASR, VAD, or voice-classification implementation. The Six Stars adapter owns those functions, including any necessary normalization, resampling or segmentation. Recognition produces time-aligned text and voice evidence. Roots uses the existing language detection and translation path, then asks Six Stars to render the translated speech with the chosen preset voice. Do not send a conversational prompt to a Star: this is faithful translation/dubbing, not a character response or roleplay.

Initial voice mapping (proposed preset names):

| Provider voice evidence | Synthesis preset |
| --- | --- |
| Confident male voice category | Astral Stream |
| Confident female voice category | Elusiaa |
| Unknown, ambiguous, overlapping voices, or unsupported preset | Configured neutral/default voice, subtitles or original audio |

These are example output voices, not identification of the original speaker or a claim about a person's identity. No profile editor, voice cloning, speaker enrollment, or character memory updates in the first version. Keep ephemeral speaker continuity where the service provides it; a later profile system can associate voices with observed characters or user choices. Check the actual configured voice IDs and assets instead of assuming the display names are valid service keys.

Six Stars already contains speech recognition/voice generation abstractions and continuous playback implementations. The required raw streaming input, speaker/voice category evidence, preset availability and no-retention behavior must be verified in the service capability handshake. The existing Whisper path alone is not evidence of reliable speaker classification. Extend the Six Stars adapter if the capability is missing; do not implement a second competing classifier in Roots or fabricate a category.

## 2. Service and transport

    Authorized application audio
      -> Roots capture (native format PCM + timestamp/sample index)
      -> one local Six Stars recognition stream
      -> incremental/final speech segment events
      -> Roots translation (existing FastText/PLaMo, unchanged models)
      -> Six Stars streaming synthesis (selected voice)
      -> Roots continuous PCM output/mixer
      -> selected output device

The host owns the authenticated local service adapter. The interactive C++ agent owns the capture and playback endpoints. Browser tab capture uses the same logical stream contract via the extension's authorized offscreen audio bridge. Reuse a compatible existing local Six Stars protocol where available; otherwise define a small versioned streaming adapter in Six Stars. Do not change the existing phone/mobile audio protocol merely to support Roots.

Proposed messages, not claims about implemented endpoint names:

| Message | Purpose |
| --- | --- |
| stream.start | Source instance, capture epoch, native sample format, start time, target language, permitted recognition/render capabilities |
| audio.frame | Sequence, first sample index, sample count, monotonic timestamp, native PCM payload |
| audio.gap / stream.end | Explicit discontinuity or end of input |
| speech.partial / speech.final | Segment identity/revision, input sample range, source language, transcript, provider confidence, optional speaker continuity/voice evidence |
| synthesis.request | Correlated stable translated phrase, voice preset, target language and priority |
| synthesis.pcm | Synthesis ID, ordinal, output sample format and sequential PCM sample range |
| synthesis.end / cancel / error | Explicit producer completion, cancellation or failure |

Send binary PCM in small ordered transport frames on a persistent connection. Preserve native channel count/sample rate until the provider adapts it. Transport framing is not speech segmentation or a playback unit. A bounded in-memory capture ring absorbs brief transport jitter. No raw audio disk spool. Use sample indices for ordering and time alignment; timestamps must be correlated to the capture clock. Reconnect or format changes create a discontinuity; reject stale frames from an old source or capture epoch. Reuse the local model process/service rather than starting one per utterance.

Release stable complete phrases/sentences for translation and synthesis as soon as context permits; do not wait for a whole conversation or game scene. Partial transcript revisions remain provisional. Once PCM for a phrase has been committed to the playback device it is immutable. Only uncommitted text/audio may be revised or canceled. Preserve original transcript and translated text in the private Experience Tree with their source time range, language, confidence, selected voice and provider revision. No audio recording, WAV export, prompt history or conversation mutation in this adapter.

## 3. Reuse the existing adaptive PCM renderer

Reference the actual Six Stars implementations rather than designing isolated clip playback:

- [SmartPcmPlaybackWriter.java](https://github.com/bradgearon/the-six-stars/blob/HEAD/android/six-stars-mobile/app/src/main/java/com/wds/sixstarsmobile/SmartPcmPlaybackWriter.java): Android continuous AudioTrack writer, learned startup reservoir, first-chunk timing, rate/jitter estimates, low-water refill, playback-head progress and forward-only commits.
- [SmartDirectSoundPcmPlaybackSession.cs](https://github.com/bradgearon/the-six-stars/blob/HEAD/src/Wds.SixStars.Ui/Audio/SmartDirectSoundPcmPlaybackSession.cs): desktop equivalent, continuous DirectSoundOut/BufferedWaveProvider and generation-rate-aware buffering.
- [Desktop smart PCM design notes](https://github.com/bradgearon/the-six-stars/blob/HEAD/ai_updates/DESKTOP-SMART-DIRECTSOUND-PCM.md): one configured output per logical response, pause/refill without reopening, last-resort silence and a short fade after starvation.

These sources are reference behavior; choose whether to reuse a library or port its state machine into the C++ output abstraction after reviewing ownership and API boundaries. The existing desktop speech renderer uses DirectSound, whereas WASAPI application loopback is the capture backend. Do not conflate those roles. The first desktop implementation can use the existing desktop playback service via the adapter where compatible; a native PCM mixer/output adapter must pass the same behavior tests.

One logical dubbing session owns a continuous output clock and one configured endpoint. Multiple voices and phrases append to that stream in source order; no stop/start per chunk, WAV header, sentence or speaker. Normal silence between source utterances is distinct from a stalled producer. A mixer may output silence while idle, without tearing down the endpoint. Format conversion occurs at the output boundary when needed, not by changing the original input capture stream.

### Adaptive scheduling

Maintain separate observed timing statistics for recognition, translation, synthesis provider/voice/language and playback device. Measure:

- input-to-first-stable-text, translation latency, synthesis request-to-first-PCM;
- completed output audio duration divided by actual production time (real-time factor), inter-packet arrival gaps and jitter, and rendered audio per character/phrase;
- device playback head or audio clock, committed and played sample indices, available uncommitted audio and end-to-end lag.

Use the actual renderer's timing policy as the initial baseline: approximately 900 ms learned startup lead, bounded to 500–2200 ms in the existing voice implementation, with rate and jitter smoothing. Those values are inherited starting points for tests, not guaranteed latency or rigid limits for every device. Learn a conservative startup reservoir from initial latency, sustained production speed, recent gaps and predicted remaining audio duration. A producer that renders faster than playback can start earlier; a slow or jittery route needs additional lead. Predict completion using observed generation rates and estimated phrase audio duration, then update that estimate as PCM arrives. Keep a small synthesis lookahead instead of waiting for the current spoken phrase to finish.

The playback controller uses actual buffered audio time (committed frames minus played frames) rather than wall-clock guesses. Start once sufficient contiguous audio is available; commit complete ordered PCM windows forward-only. If lead approaches a low-water threshold while more audio is expected, pause/refill the same logical stream, not reopen a player for the next packet. Resume once the adaptive refill threshold or confirmed end-of-input is reached. Avoid clicks with short transition ramps where necessary; silence is a final safety net, not the normal strategy. A normal producer end drains the playback head before reporting completion. Persistent playback profiles may retain aggregate timing statistics, never sample data.

For live dubbing, adaptive lead has an end-to-end lag budget. If sustained production is slower than real time, a larger buffer only delays starvation; it cannot solve a growing backlog. Prefer stable completed phrases, cancel expired uncommitted synthesis, mark transcript gaps or fall back to original/subtitles rather than creating unbounded lag. Never reorder already committed audio or silently drop source facts from the Experience Tree. In a live game the picture continues advancing; perfect lip synchronization is not promised. Browser/media adapters that control a common playback clock can optionally delay the original presentation to align audio and visuals.

## 4. User-facing audio modes

| Mode | Audible output |
| --- | --- |
| Original | Original application audio; recognition can remain independently enabled |
| Interpret | Original audio plus translated voice, with optional reversible ducking |
| Suppress and replace speech | Translated voice in place of original dialogue, while preserving non-speech audio where a supported stem/separation path exists |
| Replace full application audio | Explicit whole-stream replacement if the user accepts loss of original effects/music |

The requested suppression mode is a real option, not merely playing an additional voice. Process-loopback capture alone cannot suppress speech. Prefer supported native dialogue tracks or an explicitly authorized routed browser/native mixer. With mixed speech/effects, only claim speech-only replacement after separation works reliably; otherwise offer interpretation or explicitly selected whole-stream replacement. Do not silently mute the entire app because speech isolation is unavailable.

Prepare translated audio before engaging replacement where routing permits. A cancel, device loss, provider error, timeout or disabled capture must promptly restore normal original audio. Keep original routing recovery outside the inference worker and use a watchdog for any held attenuation. Prevent feedback from synthesized output into recognition. No silent indefinite muting while the producer stalls. Preserve independent visual translation and local activity capture when audio is off.

## 5. Implementation and verification gates

1. Verify Six Stars adapter capabilities, actual preset IDs, supported languages and continuous PCM protocol; add the missing streaming recognition/voice evidence interface if needed. No changes to existing model pins or mobile playback behavior.
2. Build the authorized application PCM transport and transcript ingestion using synthetic audio fixtures. Verify packet ordering, loss/discontinuity reporting and that no raw media is persisted or sent to unapproved remote providers.
3. Integrate existing Roots language detection and translation with stable segment IDs. Confirm that target-language input can remain original and that ASR revisions do not replay spoken segments.
4. Connect the Six Stars voice renderer and adaptive continuous PCM controller. Compare output behavior against the existing mobile/desktop writer under variable first-chunk latency, production speeds above/below real time, jitter, normal speech pauses, out-of-order packets, cancellation and device changes.
5. Add selectable interpretation and suppression/replacement routes; test independent original audio restoration, speech-only stem replacement, mixed-stream limitations and no self-transcription.
6. Test a long-running live game/browser dialogue stream: bounded memory and lag, no per-chunk output recreation, no avoidable underruns, graceful fallback when throughput is insufficient, and searchable transcript/scene records in the private Experience Tree.

Report actual first-audio latency, estimated vs actual render duration, effective generation rate, refill/underrun counts, playback lead and lag, canceled/expired phrases and restored-output events. No raw audio in diagnostics. Success means continuous playback when throughput permits, correct ordering, predictable degradation otherwise, and preserving the existing Six Stars and Roots behavior. This plan is not a claim that a new dubbing implementation has been built or tested.
