# Architecture and roadmap

Status: proposal; not implemented. See [design index](README.md).

## Existing foundation

Roots is a local-first translation and social integration platform. The repository README documents the Android accessibility overlay and on-device PLaMo 0.8B model, Chrome extension and Windows desktop host (.NET 9, llama.cpp, PLaMo 2B by default). The existing translation API and transport-neutral Wds.Roots.Api Forge contracts (ForgeActorReference, ForgeServiceRequest, ForgeServicePacket, IForgeService.InvokeAsync, correlation IDs and state update notices) are the starting point. Do not replace the existing viewport or translation implementations to introduce social intelligence.

The general reasoning and embedding providers proposed here are distinct from the translation provider; do not assume the translation model can reliably classify or reason about social content. No silent cloud fallback.

## Logical service boundaries

| Service | Responsibility |
| --- | --- |
| Source adapters | Authorized platform observations, capability flags, normalization and provenance |
| Viewport observation / presentation | Stable content identity, lookahead, bounded substitution, cancellation and original reveal |
| Event intake | Deduplication, idempotency, ordering, consent and retention |
| Graph projector | Time-versioned relationships and queryable current state |
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

Keep models/data records simple; place decisions in testable service implementations. UI should call operations rather than own business logic.

## Shared graph and events

Suggested entities: Person, NetworkIdentity, Content, Observation, Relationship, Garden, Highlight, Recognition, Rating, AvailabilityPolicy, ProfileFact, Intent, Agent, Opportunity, EnvironmentSnapshot, ResonanceFingerprint and Insight.

A source observation includes source/platform, external ID, canonical URL when permitted, event timestamp, observed timestamp, schema version, provenance, permission scope and dedup key. Store a reference to authorized source content rather than unrestricted copies. Temporal graph edges carry type, origin, validity interval and visibility; inferred edges retain confidence and evidence. Immutable event IDs do not imply immutable personal data: support deletion/redaction and derived projection rebuilding.

Use a local transactional database (SQLite is a candidate) for events, projection tables, preferences, model metadata and durable jobs. Index by source ID, time, person/content and topic. Store embeddings only where needed and treat them as personal data. Keep raw notification/conversation content in a separately scoped store with short configurable retention. Secrets use platform keystores. Initial implementation need not require a graph database or a distributed event bus.

A later opt-in sync/service tier may hold public event context, approved cross-user recognition, ratings, availability and agent introductions. Private histories do not sync by default. Distinguish local-only capabilities from operations requiring shared state. Use authenticated access, encrypted transport, server-side permission checks, audit, revocation and replay-safe writes. Existing desktop LAN HTTP is documented as a trusted-network development mode; do not extend it to public social services.

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

Maintain compatibility with language.detect and language.translate. Define contracts and use fixture/mock providers before binding to Android/Chrome/desktop. An LLM may propose an intent or policy rule, but only a validated, user-approved structured policy can authorize actions.

## Background execution

Android notification listener is event-driven after explicit OS/user authorization; accessibility data availability varies by host app. Android scheduled work is opportunistic and subject to OS constraints; use WorkManager for suitable persistent deferrable tasks, not guaranteed exact intervals or a permanently running inference loop. Chrome workers may suspend; persist checkpoints. Desktop host can manage longer permitted jobs. Shared public-context refresh may run on an authorized server. No promise of identical availability across platforms.

Job envelope: job ID, actor, purpose, policy version, input references, dependency IDs, priority, not-before, expiry, budget, idempotency key, retry count, checkpoint and cancel token. Check permission at enqueue, before execution and before presentation/action. Batch similar work; use fast classification/cache before expensive reasoning. Enforce per-agent time/token/network budgets; record cost and provenance. Scope source data to the intended task, and treat imported content as untrusted data (never as executable instructions).

## Delivery phases (proposed)

1. Design and contracts: schema, policies, local storage, capability discovery, adapter fixtures; preserve translation regression checks.
2. Personal utility: universal URL saves, historical highlights, local graph, content preferences and attributed reversible substitution prototype. Add local notification observation with deduplication and user-controlled permissions.
3. Recognition and analytics: optional accounts and verified identity linking, awards, network exploration, baseline-aware Resonance, data export/delete. Validate API access before promising particular network integrations.
4. Environmental intelligence and agents: dated public event research, multilingual Poetic Fields, natural-language plans, conversation suggestions, background discovery and outcome evaluation.
5. Social participation: independently opt-in peer ratings/levels, scoped availability and discovery, abuse handling, Gardens and mutual introductions. Dating only after adult eligibility, safety, privacy and consent controls are verified.

Phases are flexible; implement availability policy foundations before any cross-user interactions. Dating and ratings must not be prerequisites for other functionality.

## Verification and open decisions

- Unit/contract tests: normalized events, deduplication, projection rebuild, scope enforcement, rating aggregation, time-based expiry, prompt-injection resistance, cancellation and inference budget.
- Android/Chrome tests: offscreen lookahead availability, recycled rows, rapid scroll, gestures, source identity, original reveal, permission revocation and fail-open rendering.
- Analytics tests: synthetic spike streams, missing observations, comparison cohorts, forward-only snapshots, calibrated uncertainty, no future leakage and no unsupported causality.
- Agent tests: explicit policy, scope isolation, stale availability, no unauthorized outreach, reproducible evidence and outcomes.
- Open: platform API entitlements and store rules, third-party source and model license audit, provider choice/latency, storage/retention defaults, cross-user identity and sync, scoring formula, moderation staffing and adult eligibility process.

No build or runtime changes are required by this design-only documentation.
