# Roots portable knowledge — draft 0.1

Status: exploratory, design-only. The intended Roots and Forge specifications are open; implementations and knowledge have independent owners and licenses. This draft does not change the repository LICENSE or grant rights. See [openness](openness.md). The Roots application by We Develop Software LLC is one implementation and may use ChristEye. ChristEye is not a conformance dependency.

## Layers and invariants

Forge conveys authorized actor operations and state. Roots defines portable knowledge and its development, query and exchange semantics. The Tree of Life is a developmental architecture, not the data structure; this storage layer can serve both Tree of Knowledge and Tree of Life expressions without redefining them. Keep Honey, Winds, Honeycomb, Whirlwind and Ark distinct as in the existing design. Central Network Brain manages shared cognition and distribution independently of the personal poetic micro-cognition layer. A central server is optional for independent Trees.

Comprehend an eligible observation once per content revision/model configuration/scope where permitted; reuse and extend the record rather than repeatedly interpreting source material. Distinguish observations, claims, inferences, peer ratings, preferences and measured outcomes. Repeated circulation of the same claim is not independent evidence. A view is not an endorsement.

## Logical data model (proposed)

| Record | Required semantic fields |
| --- | --- |
| Tree | issuer, stable tree ID, schema, purpose, policy, head revisions |
| Branch | tree ID, branch ID, selection definition, parent revision refs, completeness/coverage, policy |
| Node | stable issuer-qualified ID, type, version, permitted payload, source refs |
| Observation | event ID, source ID/revision, observed/event time, scope, provenance, dedup key |
| Claim | subject, predicate, object/value, evidence refs, epistemic status, confidence method, producer/model revision, validity |
| Edge | source/target IDs, type, direction, weight semantics and version, confidence, evidence refs, time, policy |
| Vector | node/revision ID, embedding space fingerprint, dimension, metric, normalization, encoding, vector |
| Delta | event ID, issuer, causal dependencies, operation, affected record version, scope, evidence, signature envelope |
| Judgment | evaluation method, evidence, counterevidence, decision, actor, revision |

A Branch is a scoped subgraph or materialized selection, not necessarily a disjoint subtree. Cross-branch edges can reference opaque IDs, but must not leak inaccessible targets. Preserve source identity; no handle-based identity merges. Weight, confidence, freshness, reach, vitality and explicit appreciation are independent. Unknown is not zero. Scores are domain-specific and never an inferred measure of human worth. Geographic association of an event differs from a person's voluntarily declared region. Dating preferences/availability are restricted authoritative policy data, not inferred embeddings.

## Compile and search

Transactional event/record storage is authoritative; compiled tensors and search indexes are disposable projections. Initial reference design: SQLite local, sparse CSR adjacency, normalized multilingual embeddings, flat exact similarity and bounded typed traversal. Benchmark ANN separately (HNSW/IVF/PQ) and GGML batched kernels. Search correctness does not depend on a GPU or LLM. GGUF is a candidate open tensor container, not a trained model or search engine; custom loader required. Do not require llama.cpp to load a knowledge GGUF as model weights. Test GGUF against plain mmap arrays and SQLite; permit alternate encodings with equivalent semantics.

Candidate tensor names (logical shapes, actual GGUF layout/quantization to be verified):
- node.embedding [N,D] float32 baseline, optional FP16/int8 or compatible block quantization; explicit scale/metric.
- edge.offset [N+1] sparse adjacency offsets; edge.target [E] local ordinal; edge.kind [E], edge.weight [E], edge.confidence [E]. Local ordinals never serve as stable external IDs.
- node.flags [N], node.revision [N]; ID/evidence/payload mapping in accompanying table.

Manifest binds stable IDs to local ordinals and declares embedding model artifact hash, tokenizer/preprocessing, dimensions, normalization, metric, quantization, byte order, tensor layout, schema and graph weight semantics. Never directly compare embeddings from incompatible spaces; use explicit re-embedding where permitted or separately ranked results.

Query pipeline: authorize request and candidate scopes; compile intent into typed filters and a query vector; lexical/semantic seed retrieval; bounded typed graph expansion with hop/fanout/budget limits; combine separately defined scores; check evidence, time, policy and blocks again before return; optional deeper cognition and deliberate writing. Allow structured queries without any LLM. Geography/ratings use typed fields, not opaque similarity alone. Keep personal relevance local where possible. Record algorithm version and evidence paths. Negative edges/contradictions do not silently become endorsements.

## Portable package (candidate)

A directory or bounded archive containing manifest.json, records.db (or an interoperable canonical record stream), knowledge.gguf (optional compiled representation), optional indexes, evidence/ (only licensed and authorized original evidence), and a signed manifest of file hashes. Manifest includes tree/branch ID, content and revision hashes, parent heads, issuer, schema/profile, timestamps, capabilities, coverage, embedding descriptors, policy and rights, expiry and dependencies. Define canonical serialization and signature profile before interoperability claims. Indexes are optional and should be locally rebuilt if untrusted or incompatible. Import validates quotas, paths, shapes, offsets, hashes, schema, provenance, signatures and policy; no embedded executable instructions. Signature authenticates issuer, not truth. Staging and atomic activation; quarantine unknown schemas and malformed bundles. GGUF alone does not carry the full semantic contract.

Export only permitted data; distinguish reference-only and self-contained offline profiles. Self-contained research packages must include licensed evidence, dictionary/ID mappings and required model/runtime or explicitly declare missing dependencies. Never promise offline recovery of remote sources. Export private data only to explicitly authorized recipients with encryption; avoid portable dating/location or third-party private data by default. Revocation cannot recall arbitrary offline copies; expiration/revalidation and data minimization help but cannot guarantee deletion.

## Distribution and independent cultivation

Immutable snapshots + small signed delta batches + local mutable overlay; background compaction produces new snapshot without blocking readers. Transfer by ordinary file, USB, local Wi-Fi, authorized peer/server transport or Forge. Package inventories advertise only authorized branch metadata; compare heads and request missing dependencies. Use stable event IDs, dedup, causal parents and deterministic replay. On disconnect, store independent contributions and reconcile on return. Merge observations additively by ID; retain contradictory claims and competing edges with evidence; do not average incompatible weights or repeatedly count echoes. Owner-controlled policy and identity changes require authority/version checks. Concurrent conflicting edits remain pending for judgment rather than silently overwriting. Tombstones/redaction win over stale replay within scope; no resurrection on restore. Do not promise arbitrary conflict-free merging or eventual consistency before testing.

Desktop worker selects approved jobs to validate evidence, compute embeddings, find candidate associations, check prior hypotheses, compile indexes or prepare scoped branches. Mobile prioritizes observation, lightweight retrieval and playback; server can coordinate common public knowledge, cache reusable cognition, and accept bounded contributions. No automatic private upload or silent cloud fallback. Browser work is checkpointed; closed browser work requires an authorized desktop/server. Contributions are proposals, not trusted updates; quotas, independent validation, anti-Sybil, counterexamples and provenance guard against poisoning. Only permitted external sources; a public URL or derived vector is not a blanket redistribution license.

## Forge capability proposals

tree.describe, tree.export, tree.import; branch.select, branch.subscribe, branch.diff, branch.merge.propose, branch.merge.commit; knowledge.observe, knowledge.claim.propose, knowledge.query, knowledge.activate, knowledge.compile, knowledge.revoke; worker.offer, worker.claim, worker.result.submit. Versioned input/output, actor ownership, scope, correlation, idempotency, budgets and cancellation. Define transport-neutral conformance vectors; file-only implementations may implement a portable profile without networking. Do not require a proprietary SDK, ChristEye, a particular model, or a central Roots server.

## First experiments and gates (targets, not measured)

1. 10k licensed synthetic/public-domain records, exact retrieval reference and judged multilingual queries; compare keyword, vector, graph and hybrid, report recall@10/nDCG@10 and evidence coverage. No self-corroboration.
2. Compile 100k nodes at 384 dims, ~8 outgoing edges; measure actual disk size, peak RSS, cold start, p50/p95 query latency, update and rebuild time on Android/desktop. Compare flat arrays, GGUF and ANN. Initial planning targets <100 MB compact payload excluding evidence and optional indexes; warm local p95 <100 ms for bounded retrieval (not LLM generation), recall@10 >=90% relative to exact vector baseline for ANN. Revisit if quality or hardware fails.
3. Export, disconnect, query and add independently authored observations on two implementations; exchange deltas over a file; verify deterministic logical results, duplicate replay, conflicts, stale revocations, incompatible vector spaces, malformed imports and permission isolation.
4. Scale to one million records only after above; benchmark CPU versus RTX 5080 including transfer, power, sustained thermals, and cost. No assumption that GPU improves sparse lookups or GGUF improves search speed.
5. End-to-end Garden scenario: eligible tweet observation -> shared cognition -> typed edges -> scoped branch -> another user's search -> original source resolution or labeled offline evidence -> feedback with separate exposure and appreciation. Field scenario: offline research references + new measurements -> local analysis -> return and review merge.

Suggested capacity model: 384-byte SQ8 vector + 8 edges at 16 bytes + 64 bytes fixed records = 576 bytes/node before scale metadata, ID mapping, evidence, indexes and overhead. 100k ~57.6 MB, 1M ~576 MB decimal. HNSW and text can materially increase that. Compression is lossy; measure retrieval, do not present as preserving complete Internet content.

References for implementation research: https://github.com/ggml-org/ggml/blob/master/docs/gguf.md ; https://github.com/facebookresearch/faiss/wiki/Faiss-indexes .

## Grafting, planting and portable development

Romans 11:17–24 provides the WDS design analogy: a grafted branch participates in the nourishing root without a reason for boasting; natural branches can be grafted back. John 15:1–8 supplies the related imagery of abiding and fruitfulness. These theological passages are not protocol requirements.

Distinguish import (receive and verify package), plant (establish independent Tree), graft (authorized continuing integration), nourish (scoped access to supporting knowledge), prune (retire invalid associations, preserving valid history subject to deletion), detach (end ongoing graft without granting access to retained private data), and regraft (reconcile independently developed branches). A one-time snapshot import does not establish a subscription or ongoing authority.

Graft record: graftId, origin tree/branch and revision, receiving tree and expected revision, identity mapping and unresolved references, granted scopes/rights, compatibility profile, dependency and subscription policy, steward approvals, evidence/conflict decisions, created/expiry time, state, compensating operations. State proposal -> verified -> staged -> authorized -> committed/active -> suspended/detached/revoked. Verify manifest and evidence, map stable identities without collapsing origin, reconcile conflicts, validate both scopes, commit atomically, maintain independent provenance and allow authorized reciprocal contributions. No automatic consent, no forced membership, no overwriting valid local knowledge; re-evaluate authorization on each delta. Re-grafting retains ancestry and accepts only eligible new contributions. Offline copies cannot be forcibly recalled.

Engram AI is the implementation precedent for numerical memory and bounded activation; see [Network Brain](network-brain.md). Compare its existing 256-byte RNA tiles (32 hashed key, 16 features, 16 reserved float lanes; source index external), custom GGUF snapshots and simple mmap layouts. RNA and GGUF are candidate projections, not substitutes for authoritative source records. Wind decision weights are separate from graph association and feed activation weights. No arbitrary model-weight lookup.

Field/education profile: a team downloads an authorized self-contained Branch with licensed references, evidence, model/runtime dependencies and offline query tools; each member records observations and measurements with provenance, shares deltas over local Wi-Fi or removable media, and reconciles with the institution after return. Support portable course libraries, expedition manuals, research corpora and independent application integrations. The field team must retain original measurements and essential safety/operational documents; compressed associations do not replace evidence. Test complete offline use and two independent compatible readers. The 'Internet on a floppy disk' analogy is compressed searchable knowledge about a selected domain, not a complete or lossless copy of the Internet.
