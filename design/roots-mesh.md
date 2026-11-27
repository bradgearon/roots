# Roots Mesh v1 and localized knowledge stream

The Android home screen now orders grouped master records from the private SQLite Tree, not raw chronological screen text. Score inputs are local branch membership/term matches, unique observed role count, shared vocabulary, localized text, explicit private understanding, wanted flag, real source link, observation age, bounded observed engagement, and observed peer availability/demand. These are heuristics, not a learned quality or truth score. Search precedes ranking; all available matching records are ranked then shown 30 at a time. Tap an exact status URL to launch X (browser fallback); hold for details, manual permalink attachment, local translation, confirm original language, private understanding, wanted flag, or publish. X only supplies a verified permalink when an unambiguous explicit timestamp/permalink node exposes a status URL; otherwise attach the exact copied URL manually. Never guess a remote post ID.

## Localization

Completed overlay translations are written to the same record as the original under a target-language-specific role, only if capture consent and foreground snapshot epoch remain valid. Cached completed translations may be retained when encountered again. SQLite keeps raw and structured originals, translations, notes, links, and peer items. Android tree.gguf is a **private localized projection**: known local-language body parts (or latest explicit translation/confirmed local text), local-language understanding, structural and branch-ID edges, with lexical vectors computed from the localized text. Raw captures, unknown-language text, foreign originals, offer metadata, branch terms and authors are excluded. The old lossless Android snapshot is removed on upgrade. No encrypted secret GGUF section is implemented; originals are kept in private SQLite. The legacy C/C# lossless compiler remains available for explicit private use. English text may have an unknown hint in the current reader; confirm its language or translate it explicitly. Changing target language rebuilds the localized projection.

## Peer protocol

* Android deep link: roots://mesh/v1?host=IPv4&port=8233&pin=SHA256&token=256-bit-token. The URI opens the Mesh UI; no automatic connection. The transport is TLS 1.2/1.3 TCP on the Wi-Fi subnet, with pinned certificate from Android Keystore, length-prefixed UTF-8 JSON frames, version 1, and session token. No custom encryption or plaintext fallback.
* Start listening on one Android device, copy/share its invitation to another, paste or open it, then tap Receive missing knowledge. A fresh install can receive without X/accessibility/capture permissions. Keep the serving app running; no guaranteed background service or Internet-wide discovery. Stop invalidates the invitation.
* Publish only an explicitly authored shareable remembrance, with topic, language, optional public X status URL. No raw X text, original/translated private records, private understanding, SQLite or GGUF are automatically served. The immutable SHA-256 content ID covers every shared field. Receiver verifies the hash before atomic insertion into private SQLite, and deduplicates by ID. Received offers may be served when the recipient explicitly starts listening. Published items cannot be recalled from other devices.
* Inventory pages contain IDs, approved topics, language and bounded observed request count. Local wanted interests/branch terms never leave the device. The receiver compares up to eight invitations, ranks missing items by local interest, then fewest observed replicas, then demand, then language, and asks for up to 32 per sync. Alternate sources are tried on failure. Repeated sync receives the next missing items. No DHT, tracker, NAT traversal, global rarity estimates or background polling. Counts are local observations, not global popularity.
* Peer content in another language is retained in SQLite and can be localized using the same home record action (requires existing local inference permission/model). Only local-language knowledge enters the compiled projection.

## Connection testing and diagnosis

The Mesh view has a **Connection details** section next to the invitation and
subscription controls. **Test connection** checks the current invitations without
receiving or retaining published knowledge. It uses the same network, TLS, and
inventory exchange as Receive. A local Native Tree problem is reported separately
so transport can still be tested when the receiving database is unavailable.

With **Use saved .axon subscription** selected, an empty invitation box reuses
encrypted saved peer connections across view recreation and app restarts. Bind
saves access, and testing/receiving with an explicit current invitation refreshes
access for an already bound identity. The report loads the saved binding first
and distinguishes an older policy-only binding from missing or unreadable access.
Topic/model edits work with an empty box and retain the existing identities.
Removing the binding removes its saved access. Runtime arming remains separate.

The Android listener now authorizes `DIGEST_NONE` for its EC key because TLS
signs prehashed handshake data. Before opening the port it performs a real
`NONEwithECDSA` signing/verification check and reports the result. An incompatible
legacy key is retained while a compatible replacement identity is generated.
That one-time migration changes the certificate pin: update the listening app,
start listening, copy its new invitation, and explicitly update the receiver's
Axon binding. Compatible identities remain unchanged. Incoming TLS failures are
reported without request contents or arbitrary exception messages.

Each timestamped report includes the current network and local IPv4/prefix,
listener state, subscription readiness, runtime access state, and a separate
result for each invited endpoint. Checks show PASS, FAIL, WARN, or SKIP, with
elapsed time and an actionable next check. Tests distinguish:

| Check | What the result establishes |
| --- | --- |
| Invitation and subscription | Valid Mesh invitation; selected saved policy exists and binds the peer identity |
| Network and subnet | Default non-VPN Wi-Fi, an IPv4 address, and another device on that subnet |
| TCP | The invited endpoint accepts a connection on port 8233 |
| TLS | The handshake succeeds with the certificate identity in the invitation |
| Protocol and authentication | A compatible peer accepts the current invitation session |
| Inventory | A valid first inventory page is returned; zero items means the peer is reachable but empty |

Later stages are marked untested when an earlier check prevents them. A test reads
only the first inventory page, at most 256 offers, and reports whether more pages
exist. Each peer has an eight-second exchange budget and the full diagnostic run
has a thirty-second budget. Invitations beyond the remaining budget are marked
untested. Conflicting invitations for the same endpoint are reported explicitly.
Receive also preserves a connection report when an inventory or content request
fails, instead of collapsing every error into “No peer inventory was reachable.”

New peers return bounded authentication/protocol errors after TLS. An older peer
can still close without a response; that result remains ambiguous between a stale
invitation, a stopped/busy peer, or an incomplete exchange. TLS success alone does
not prove invitation access. Stopping and restarting the listener generates a
new session token: copy a new invitation even when the saved `.axon` identity
still matches. Runtime arming and model flow observations are not required for
manual Receive; a wildcard `*` is a valid bulk subscription.

**Copy connection report** copies measured diagnostics without invitation tokens,
full invitation URIs, private topic labels, or published content. Abbreviated
certificate identities and LAN endpoints remain available for troubleshooting.
Changing invitations or subscription settings marks the previous report stale.
Returning to the screen preserves the latest in-memory report as a dated snapshot,
and a fresh test is required before analyzing a changed setup. Invitation grants
are excluded from Android saved view state and autofill.

### Intelligence request templates

**Analyze connection with intelligence** renders a compact diagnosis request and
runs the selected downloaded local model through `LocalIntelligenceProvider`.
The normal connection checks work without a model. The action uses ordinary local
generation and does not depend on runtime Axon retrieval. The screen keeps the
model's interpretation separate from the measured report, and shows model setup
or queue errors without discarding the evidence.

Both outputs use the pinned `com.samskivert:jmustache:1.16` library through the
shared `TextTemplates` loader:

- `android/contextual-overlay/src/main/assets/templates/mesh_connection_report.mustache`
  renders the detailed on-screen and copyable report.
- `android/contextual-overlay/src/main/assets/templates/mesh_connection_analysis.mustache`
  renders the small request to the local model.

Templates receive plain maps built from typed diagnostic records. The diagnosis
request stays within the existing plain-text inference limit, preserves failures
for every tested peer, and includes inventory counts even when the check passes.
It asks for a likely cause, the supporting evidence, and two or three next actions;
insufficient evidence must lead to a suggested test, not an invented finding.
No raw peer response or exception text is interpolated into the model request.

## Validation

Native and JNI contract tests cover localized content and private exclusion. Android JVM tests cover URL validation, content hashing, rarity ordering and home ranking. The two-device smoke test is: (1) publish one local-language remembrance on phone A, (2) start listener and copy invitation, (3) open/paste on fresh phone B and receive, (4) verify home record and second sync receives no duplicates, (5) start B listener and receive from B on another device, (6) test exact X link and retained translation, (7) stop listener and verify old invitation fails. Android build and actual two-device TLS integration require a supported Android SDK/device environment.

For connection diagnostics, additionally test an empty published collection, a
stopped listener, an old invitation after restart, an invitation copied from the
receiving device itself, a different subnet, a VPN/default-network mismatch, and
a mismatched certificate pin. Confirm that the reported stage is accurate, later
stages remain untested, and no test adds a Tree record. Repeat with multiple peers,
including one reachable and one unreachable peer. Verify Copy report and the
model request contain no invitation grants. Run analysis with and without the
selected model downloaded; the measured report must remain available in both
cases. Device tests establish Android Keystore/TLS, routing, and UI behavior that
host JVM checks cannot establish.
