# Request-responsive Axon: design and first implementation

## Goal

A request creates temporary demand in the knowledge mesh. Resolve it using
local knowledge first, authorized peers second, and local intelligence to
interpret the resulting evidence. Preserve privacy, independence, bounded
resource use, and the ability to continue without a peer.

## Layer boundaries and lifecycle

1. **Request / need:** the user chooses Resolve request through Axon in the
   intelligence settings. Only named subscription labels are preselected by
   literal request matching, and the user confirms one or two topics. Never
   transmit the private prompt, inferred free-form query, or private branch
   terms. A bulk-sync wildcard is not a runtime authorization.
2. **Wave planning:** compute the existing Axon allocation from the latest
   GGUF-derived field and explicitly mapped, measured model-element baseline.
   Prioritize confirmed topics matching the request, then their flow shares.
   Use the selected local-language master records and current wave priority
   to select relevant local notes. Two distinct matching records count as
   local coverage, a provisional heuristic rather than verified completeness.
3. **Need / deliver:** ask bound live peers only when local coverage is
   insufficient. For this first implementation a single TLS request returns
   up to three immutable published knowledge items; offer/access/deliver are
   collapsed into one exchange. The receiver checks correlation ID, bounds,
   topic, and content hash. No arbitrary file access or remote execution.
4. **Local resolution:** retrieval starts on its own bounded worker while the
   existing native inference queue prepares the selected model. The retrieval
   deadline is four seconds from request submission, with two-second caps per
   peer exchange, enforced by socket close and client wait. At most three
   evidence notes total, up to 900 context characters; reserve a slot for a
   second confirmed topic. Reference text is labeled untrusted and model
   control delimiters stripped; this does not guarantee immunity to prompt
   injection. Dense model execution remains unchanged. A context-size error
   falls back to the original prompt. The model is not expected to follow
   instructions embedded in reference notes.
5. **Release:** received notes are transient; do not automatically retain or
   publish them. No lease is necessary for a completed single-response
   exchange. A failure, deadline, revocation or Tree clear falls back to local
   generation; no repeated background probing. An already transmitted topic
   cannot be recalled. Revocation fences subsequent requests and the context
   handoff, not already running inference.

## Wire contract (compatible extension to Mesh v1)

Existing pinned TLS endpoint, session invitation token and version field.

Request:
```json
{"version":1,"op":"need","token":"<session token>","request_id":"<uuid>","topic":"music","language":"en","limit":3}
```

Response:
```json
{"version":1,"op":"deliver","request_id":"<same uuid>","items":[{"id":"<sha256>","topic":"music","language":"en","text":"<published knowledge>","link":""}]}
```

An empty items array means no matching publication. The server reads only its
explicit published offer catalog; it never searches private raw capture,
model weights, files, or private understanding. Topic labels are bounded and
validated. Server prefers requested language and observed demand; the existing
bulk Axon receive retains rarest-first across inventory. Runtime rarity-aware
multi-source offers and byte-budget negotiation are not implemented yet.
A peer may observe requested topic labels and requester network address;
the user explicitly confirms those labels before each request. Content hashing
establishes integrity, not truth.

## Session and usage

On both devices install the same new version. On A publish a relevant
remembrance, start the listener, and transfer its invitation to B. On B bind
an Axon with named topics and select Arm runtime Axon (30 minutes). Peer access
is saved encrypted under noBackup separately from the subscription policy; an
empty invitation box reuses those saved connections. Runtime permission still
expires after 30 minutes, and revoking runtime permission does not remove saved
connections used by explicit Test or Receive. Use Settings > Intelligence >
Resolve request through Axon, confirm the proposed topics, and inspect the
reference-count footer and Inference History. Ordinary Test general
intelligence remains unchanged. Revoke runtime access or remove/edit the
subscription to disable new requests. Peer transport is still the existing
same-subnet LAN TLS, not Wi-Fi Aware.

## Next phases

- A dedicated `NeedPlan` with live per-request relevance and uncertainty
  assessment, evidence provenance and a user-visible review/retain action.
- Explicit offer/accept/access leases, per-resource budgets, cancellation,
  authenticated capabilities, and optional bounded streaming. Use a separate
  grant for remote inference; never forward private prompts by default.
- Measured latency/availability, multiple-source rarest-first runtime
  coordination, and opportunistic transport with durable Axon identities.
- Retrieval during inference at safe stage boundaries (new information cannot
  retroactively enter already evaluated dense tokens), then validated
  lookahead element preparation. No model weight swapping or remote element
  execution until compatibility and quality are established.

## Validation

Pure Java checks cover authorized labels, request matching, deadline math,
bounded context and marker sanitization. End-to-end acceptance: serve on A,
request on B, verify a published item is delivered and used as reference;
then repeat without A, with an unmatched topic, with revoked access, and with
a Tree clear. Check that raw prompt never appears in wire messages or logs
and received references are not persisted. Full Android build and two-phone
TLS tests are still required.
