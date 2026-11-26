# Architecture and roadmap

Status: proposal; not implemented. See [design index](README.md).

## Existing foundation

Roots is a local-first translation and social integration platform. The repository README documents the Android accessibility overlay and on-device PLaMo 0.8B model, Chrome extension and Windows desktop host (.NET 9, llama.cpp, PLaMo 2B by default). The existing translation API and transport-neutral Wds.Roots.Api Forge contracts (ForgeActorReference, ForgeServiceRequest, ForgeServicePacket, IForgeService.InvokeAsync, correlation IDs and state update notices) are the starting point. Do not replace the existing viewport or translation implementations to introduce social intelligence.

The Tree of Life is the developmental architecture, not the storage graph. Roots converges with a native web presence; see [Tree of Life](tree-of-life.md). Specialized micro-cognitive operations, including a deliberate writing process, are architectural units rather than a generative model. The general reasoning and embedding providers proposed here are distinct from the translation provider; do not assume the translation model can reliably classify or reason about social content. No silent cloud fallback.

## Logical service boundaries

| Service | Responsibility |
| --- | --- |
| Source adapters | Authorized platform observations, capability flags, normalization and provenance |
| Viewport observation / presentation | Stable content identity, lookahead, bounded substitution, cancellation and original reveal |
| Event intake | Deduplication, idempotency, ordering, consent and retention |
| Tree of Life projector | Time-versioned developmental relationships and queryable current state |
| Highlights / recognition | Local favorites, collections, awards and trophy cabinet |
| Policy / availability | Per-mode permissions, scopes, mutual eligibility, revocation and blocks |
| Ratings | Peer submissions, validation, aggregate projection, moderation |
| Notification observer | Scoped Android notification events; no universal access assumption |
| Resonance | Baselines, anomaly detection, condition comparison and outcomes |
| Environmental intelligence | Dated current events, Poetic Fields and regional/community snapshots |
| Agent orchestration | Intent compilation, scoped planning, budgeted execution and checkpoints |
| Conversation intelligence | Authorized excerpts, ideas, shared interests and follow-ups |
| Kairos / discovery | Match creative history and intentions to environmental opportunities |
| Inference providers | Task-specific local or expressly approved remote models |
| Worker scheduler | Event-triggered jobs, batching, backoff, budgets and cancellation |
| WindObserver / WindCouplingService | Evidence-linked Wind observations and candidate interactions |
| WhirlwindDetector / WhirlwindEngine | Reciprocal influence, bounded episodes and transformation hypotheses |
| HoneycombTopology / CombCellProjector | Logical Mesh neighborhoods and versioned projections |
| VitalityEvaluator / DiscernmentService | Independent judgment of outcomes and harms |
| Discernment encounter orchestrator | Opt-in bounded Serpent encounters and Forge actor battles; private role assignments separated from ordinary participant messages and factual evidence |
| HoneyRetentionService / HoneycombReweavingService | Evaluated feedback and authorized reversible structural adaptation |
| WritingProcessService | Intention, knowledge, understanding, composition, judgment, revision and expression |
| Garden presence / patrol services | Scoped exploration, optional coarse local discovery and independent availability |
| Forge actor connection / expression routing | Foreign actor identity, attributable voice, permissions and presentation |
| Native web service | Profiles, public/scoped publications and social operations under shared policy |

Keep models/data records simple; place decisions in testable service implementations. UI should call operations rather than own business logic.

## Tree of Life storage and events

Suggested entities: Person, NetworkIdentity, Content, Observation, Relationship, Garden, Highlight, Recognition, Rating, AvailabilityPolicy, ProfileFact, Intent, Agent, Opportunity, EnvironmentSnapshot, ResonanceFingerprint and Insight. Add the proposed WindEvent, WhirlwindEpisode, CombCell, CellRelation, HoneyDeposit, ReweaveProposal, WritingRecord, ActorConnection, ExpressionEnvelope and NearbyPresence records in the dedicated design documents.

A source observation includes source/platform, external ID, canonical URL when permitted, event timestamp, observed timestamp, schema version, provenance, permission scope and dedup key. Store a reference to authorized source content rather than unrestricted copies. Temporal graph edges carry type, origin, validity interval and visibility; inferred edges retain confidence and evidence. Immutable event IDs do not imply immutable personal data: support deletion/redaction and derived projection rebuilding.

Use a local transactional database (SQLite is a candidate) for events, projection tables, preferences, model metadata and durable jobs. Index by source ID, time, person/content and topic. Store embeddings only where needed and treat them as personal data. Keep raw notification/conversation content in a separately scoped store with short configurable retention. Secrets use platform keystores. Initial implementation need not require a graph database or a distributed event bus.

The native web presence and opt-in sync/service tier may hold public event context, approved cross-user recognition, ratings, availability and agent introductions. Private histories do not sync by default. Distinguish local-only capabilities from operations requiring shared state. Use authenticated access, encrypted transport, server-side permission checks, audit, revocation and replay-safe writes. Existing desktop LAN HTTP is documented as a trusted-network development mode; do not extend it to public social services.

## Proposed Forge operations

Names below are illustrative and not existing endpoints. Use versioned inputs/outputs, actor reference, correlation ID, typed errors and cancellation. Verify actor ownership and authorize each operation at the service boundary.

| Namespace | Examples |
| --- | --- |
| content | observe, classify, replacement.select, highlight.save, highlight.search |
| recognition | award.give, award.revoke, award.list |
| network | identity.link, graph.query, garden.create, lineage.link |
| social | rating.submit, rating.withdraw, rating.aggregate, availability.get, availability.set |
| resonance | detect, compare, hypothesis.evaluate |
| environment | snapshot.query, poetic-field.analyze |
| agent | intent.compile, create, run, pause, discovery.list |
| conversation | analyze, suggest |
| opportunity | discover, introduction.request |
| wind / whirlwind | wind.observe, wind.couple, whirlwind.detect, whirlwind.evaluate, whirlwind.close |
| honeycomb / honey | honeycomb.cell.query, honeycomb.reweave.propose, honeycomb.reweave.commit, honeycomb.measure, honey.deposit |
| writing | intention.set, knowledge.attach, understanding.review, composition.revise, judgment.record, expression.commit |
| actor / expression / voice | actor.connect, actor.capabilities, actor.disconnect, expression.submit, expression.schedule, expression.revoke, voice.capabilities, voice.render |
| patrol / nearby | patrol.configure, patrol.run, nearby.presence.set, nearby.discovery.query, nearby.introduction.request |

Maintain compatibility with language.detect and language.translate. Define contracts and use fixture/mock providers before binding to Android/Chrome/desktop. A micro-cognitive operation may propose an intent or policy rule, but only a validated, user-approved structured policy can authorize actions.

## Background execution

Android notification listener is event-driven after explicit OS/user authorization; accessibility data availability varies by host app. Android scheduled work is opportunistic and subject to OS constraints; use WorkManager for suitable persistent deferrable tasks, not guaranteed exact intervals or a permanently running inference loop. Chrome workers may suspend; persist checkpoints. Desktop host can manage longer permitted jobs. Shared public-context refresh may run on an authorized server. No promise of identical availability across platforms.

Job envelope: job ID, actor, purpose, policy version, input references, dependency IDs, priority, not-before, expiry, budget, idempotency key, retry count, checkpoint and cancel token. Check permission at enqueue, before execution and before presentation/action. Batch similar work; use fast classification/cache before expensive reasoning. Enforce per-agent time/token/network budgets; record cost and provenance. Scope source data to the intended task, and treat imported content as untrusted data (never as executable instructions).

## Delivery phases (original proposal; superseded by integrated sequence below)

1. Design and contracts: schema, policies, local storage, capability discovery, adapter fixtures; preserve translation regression checks.
2. Personal utility: universal URL saves, historical highlights, local graph, content preferences and attributed reversible substitution prototype. Add local notification observation with deduplication and user-controlled permissions.
3. Recognition and analytics: optional accounts and verified identity linking, awards, network exploration, baseline-aware Resonance, data export/delete. Validate API access before promising particular network integrations.
4. Environmental intelligence and agents: dated public event research, multilingual Poetic Fields, natural-language plans, conversation suggestions, background discovery and outcome evaluation.
5. Social participation: independently opt-in peer ratings/levels, scoped availability and discovery, abuse handling, Gardens and mutual introductions. Dating only after adult eligibility, safety, privacy and consent controls are verified.

Phases are flexible; implement availability policy foundations before any cross-user interactions. Dating and ratings must not be prerequisites for other functionality.

## Integrated delivery constraints

Implement the Tree of Life and writing contracts before shipping system-authored reports; preserve the existing translation flow. Add deterministic synthetic Wind and Comb Cell fixtures before implementing the Whirlwind detector or structural reweaving. Introduce native web identity and policy enforcement before cross-user interactions. Deploy optional presence, patrols, foreign voices and the Serpent independently, after scoped consent and abuse controls. Require explicit human authorization for interpersonal changes and separate independent judgment from discovery. See [Honeycomb and Whirlwind](honeycomb-whirlwind.md) and [Garden participation](garden.md).

## Verification and open decisions

- Unit/contract tests: normalized events, deduplication, projection rebuild, scope enforcement, rating aggregation, time-based expiry, prompt-injection resistance, cancellation and inference budget.
- Android/Chrome tests: offscreen lookahead availability, recycled rows, rapid scroll, gestures, source identity, original reveal, permission revocation and fail-open rendering.
- Analytics tests: synthetic spike streams, missing observations, comparison cohorts, forward-only snapshots, calibrated uncertainty, no future leakage and no unsupported causality.
- Agent tests: explicit policy, scope isolation, stale availability, no unauthorized outreach, reproducible evidence and outcomes.
- Open: platform API entitlements and store rules, third-party source and model license audit, provider choice/latency, storage/retention defaults, cross-user identity and sync, scoring formula, moderation staffing and adult eligibility process.

- Additional verification: writing state traceability; constructive/destructive/stagnant Whirlwind fixtures; Honey retention gates; reweaving rollback, stale version rejection and deletion; anonymous region anti-triangulation; foreign actor provenance/revocation; Serpent blind-role fixtures, activation/assignment metadata isolation, independent evidence review, consent, termination and training-to-factual-store separation.
- Open: canonical ChristEye machine schema reconciliation, validated Honey and Wind domain mappings, composition mechanism, episode thresholds, storage measures and reweaving algorithms.

No build or runtime changes are required by this design-only documentation.

## Integrated architecture: persistent knowledge and weighted feed

The [Network Brain](network-brain.md) is the shared network-management intelligence, separate from personal poetic micro-cognition and translation. Engram AI (artificial neural mesh, not biological Anaiero) is the primary software reference for Arc journal, bounded numeric attention, IGFBP2/IGF2BP2 lookup/fetch, RNA export, Wind readouts, bounded propagation and response assembly. Its validated synthetic mechanisms are not evidence of a production social recommender. The open Roots specification permits alternative implementations.

Logical pipeline: authorized observation -> content comprehension and dedup -> authoritative claims/typed edges -> derived semantic/sparse tensor indexes -> activation -> eligibility-aware candidate ranking -> weighted lane feed assembly -> source resolution -> visual/audio expression -> separately recorded feedback/outcome. Distinguish stable knowledge, local activation, distribution and feedback weights; do not infer authority from numerical scores. See [portable knowledge](portable-knowledge.md) for package manifests, RNA/GGUF comparison, offline transfer, graft lifecycle and merge semantics. GGUF is a candidate tensor container, not an LLM or search engine for these payloads. Transactional storage is authoritative.

Additional service boundaries: cognition intake/consolidation, knowledge location and payload resolver, embedding and sparse graph compiler, package manifest and signature validation, Branch graft and delta reconciliation, graph activation and feed assembly/cursor, exposure ledger, activation profile evaluation, scoped knowledge-worker scheduler, application launcher, browsing adapters, narration/TTS/MediaSession, voice command routing. Models remain passive records; providers and services own decisions. Keep semantic/policy indexes segregated and enforce scope prior to retrieval and at presentation. Candidate operations in network-brain.md, portable-knowledge.md and listening-journeys.md extend the existing Forge translation capability; not implemented endpoints.

## Integrated delivery sequence (current proposal)

| Phase | Deliverables and gates |
| --- | --- |
| 0 — foundation | Verify existing desktop/Android/Chrome builds and translation, mobile language detection, model provisioning/residency, semantic accessibility foundation, installed offline TTS; no silent cloud fallback. |
| 1 — personal utility and portable core | Native audio-first Garden, launcher and deterministic approved browsing, saved links/Doxa history, typed personal graph, SQLite/event projection, common content cognition and exact search; Portable Tree 001 correctness baseline, scoped packages. Demonstrate useful offline experience without network accounts. |
| 2 — activation and exchange | Engram-inspired location/fetch and bounded propagation, weighted feed assembly/cursors, RNA/GGUF/mmap benchmark, signed manifests, deltas, offline graft/import/merge and independent reader; persistent desktop workers, scoped API adapter pilots. Prove portable retrieval and policy tests before scale. |
| 3 — native network and collective Tree | Google sign-in candidate, verified identities, private defaults, profiles/posts/replies/media, Doxa awards, optional native Gardens, authorized shared cognition and distribution, moderate/report/block/export/delete; base availability before cross-user actions. |
| 4 — Resonance and judgment | Notification intake, coverage-aware virality/vitality baselines, creative outcomes, explicit feedback, Whirlwind/Honeycomb fixtures and deliberate writing, held-out feed profile experiments. |
| 5 — environmental intelligence and agents | Poetic Fields, Kairos, Tendrils/patrols, background public research, conversation suggestions, regional snapshots, scheduled listening editions and independent graft contributions; validate sources and no unsolicited outreach. |
| 6 — optional social discovery | Scoped ratings and levels, anonymous coarse geography, friendship/collaboration and adult dating independently enabled/available, safeguards before launch; foreign Forge voices and optional discernment role independently gated. |
| 7 — live audio track | Authorized capture, captions, STT/translation/TTS, voice messages and eventual bidirectional interpretation; can progress independently after core contracts. |

These phases extend the prior roadmap rather than remove its requirements. Open specification work includes profile schemas, canonical examples and independent conformance tests; source reuse license remains to be chosen. Maintain policy and safety gates across every phase. Before scaling compare 10k/100k/1M knowledge records, CPU vs optional GPU, exact vs ANN, query recall, memory, latency, package size, delta bandwidth, power and feed relevance. Test two independent offline implementations with a real file transfer and conflict/revocation fixtures. No runtime or scientific validation is asserted by these design documents.

## Protocol, selective neural development and optional economics

Add [root:// resolution](root-protocol.md), [Whirlwind peer exchange](whirlwind-exchange.md), [developing neural intelligence](developing-neural-intelligence.md) and [economy/bounty services](economy-bounties.md) as independent capabilities. Selectively reuse Engram AI mechanisms, not the whole application. Separate master model, compiled knowledge tensors and developed student model snapshots; no arbitrary weight-to-fact mapping. Poetic association and style belong in Network Brain as well as separately scoped personal micro-cognition. Whirlwind handles peer transfer and reciprocal contribution; only evidenced reciprocal development qualifies as a cognitive episode. Models are passive records and service logic owns validation, authorization and state changes.

Roadmap additions: phase 1 local root URI resolution and file manifests; phase 2 authenticated peer transfer/deltas and student experiments (graph baseline first), independent conformance tests; phase 3 mock social payments, signed recognition and bounty lifecycle, provider sandbox gated separately; phases 4–5 poetic styles, independently evaluated neural learning, optional paid Whirlwind work and verified contribution receipts; optional production economic services only after provider/legal/security approval. No blockchain or payment requirement for any base phase.
