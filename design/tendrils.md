# Tendrils — predicate-directed development

Status: design proposal; native v1 currently implements literal-interest branches, not the full Tendril engine. Copyright (c) 2026 We Develop Software LLC. All rights reserved. See [LICENSE](../LICENSE).

## Definition

A Tendril is a lightweight, persistent developmental process. Its sensor queries for relationships, not merely strings. It can seek what something is, where it originates, what it belongs to, what inspired it, or what it is used for. When the sensor finds a candidate, the Tendril proposes a link, builds a node, branches or grows the Tree, subject to judgment. Higher-level agents coordinate many Tendrils according to an intention; they are not one thread or model instance per Tendril.

The canonical relationship has a subject, ordered predicate components and object, with evidence, direction, epistemic status, scope, time, source and version. Predicate components such as is, a, in, part, of, inspired, by are reusable grammatical tokens rather than an ever-growing class hierarchy. Their grammatical role and semantic meaning are distinct. is.a, is.in, is.part.of, inspired.by, is.used.for and depends.on can be composed; order and direction matter. Do not conflate classification, containment, membership, part-whole, origin, similarity and causality. A shared component is not proof of equivalent meaning.

## Numerical representation

Keep exact identities and authoritative evidence alongside derived numerical weights. A candidate profile defines vocabulary, token IDs, ordered component sequence, position/role, dimensions, numeric encoding and version. For a first reference experiment encode components with one-hot or sparse weights, then compare learned low-dimensional representations.

Let P[r,k,c] be the component weight for predicate r, position k and component c. A sensor S[k,c] supplies sensitivities; a bounded score can combine dot products or cosine similarity with exact direction/type constraints. Do not treat this score as truth or confidence. Component overlap can suggest candidate predicates; retain exact IDs even when representations are near one another. Tensor merging means compositional association, pooling or separately validated learning, not destructive union of distinct semantics. Compare order-aware and bag-of-components baselines; evaluate opposites and role reversal.

A conceptual sparse relationship tensor T[subject,predicate,object] can be traversed and contracted with sensor weights without materializing the full dense Cartesian product. Typed path composition proposes candidates only: A is part of B and B is used for C do not by themselves prove A is used for C. Keep independent evidence and judgment before promotion. Evidence strength, semantic similarity, association strength, activation, preference and provenance are separate axes.

Proposed GGUF projection (shape notation is logical; wire layout must be specified):

| Tensor or mapping | Responsibility |
| --- | --- |
| predicate.component | Shared vocabulary and component vectors |
| predicate.composition | Ordered component IDs/weights, offsets and positions |
| predicate.semantic | Optional learned semantic representation with profile fingerprint |
| edge.offset / edge.target / edge.predicate | Sparse adjacency and typed relation IDs |
| edge.weight / edge.confidence | Separate association and evidential confidence channels |
| tendril.sensor | Versioned query sensitivities |
| tendril.activation | Current bounded direction and state |
| tendril.feedback | Evaluated developmental outcomes, separate from views and likes |

Stable node/predicate IDs and evidence refs live in authoritative records; GGUF ordinals are local to a revision. Do not silently compare incompatible profiles. The v1 native compiler is a lexical feature snapshot and explicit predicate metadata, not a trained semantic tensor. Implement deterministic composition and exact querying first; measure the value of learned weights against that baseline.

## State and lifecycle

TendrilState: id, treeId, branchId, originNodeRef, intentionRef, sensorProfileRef, orderedPredicateQueries, sourceScope, frontier, seenEvidenceKeys, budget, checkpoint, policyVersion, state and lastOutcome. SensorHit: subjectRef, candidatePredicate, objectRef or candidate node, evidenceRefs, score, profileVersion, provenance and uncertainty. GrowthProposal: operations, expectedRevision, evidenceRefs, judgmentRef, policy/consent, and idempotency key.

Observe -> activate -> query -> receive -> propose -> judge -> commit -> grow or rest. Operations: LINK existing nodes, BUILD a candidate node, GROW/BRANCH extend investigation, STRENGTHEN with independent evidence, PRUNE or retire unsupported relations, REST on insufficient evidence or budget. Retain alternative explanations and negative findings. Do not turn a source's assertion into established fact by repeated circulation; no automatic interpersonal relationships or outreach.

## C++ service implementation

Use passive records and service-owned logic. PredicateVocabulary/PredicateComposer, TendrilSensor, TendrilScheduler, GrowthService, JudgmentService, TreeCompiler. A bounded shared worker pool handles sensor queries and candidate formation; a serialized transactional writer commits accepted changes; independent compilation reads a pinned revision. Persistent queue/checkpoints, per-Tendril budgets, cancellation, cycle suppression, maximum depth/fanout, dedup and policy revalidation. Favor event-triggered wakeups rather than polling. Do not block accessibility, feed rendering or translation on cognition. Batch numerical work across active Tendrils, optionally use SIMD/GPU after CPU measurement. No thread or LLM per Tendril. Agents translate user intentions into permitted sensor plans; external source access is explicitly scoped.

## First implementation increments

1. Canonical ordered predicate vocabulary, native exact query by predicate and evidence-linked edges; keep literal-interest v1 agents usable.
2. Sparse component/position representation and deterministic sensor matching with a pinned tensor profile; persist explicit sensor configurations, frontier and budgets.
3. Bounded service scheduling and proposal/judgment/commit, with replay and stale revision checks; do not auto-accept inferred relationships.
4. GGUF compiler/reader for predicate and sensor tensors, validate mappings, compare exact versus weighted retrieval and resource costs.
5. Teach through independently evaluated outcomes, version sensor weights, test on held-out data and roll back regressions; use broader research agents only after scope and evidence checks.

## Acceptance

- is.a and is.in share is without becoming equivalent; is.part.of is distinguishable from contains; inspired.by does not reverse direction.
- Repeated identical observations cannot strengthen a claim as independent evidence.
- A Tendril can link a known node or propose a new node, with evidence and judgment; failed or stale proposals do not mutate the Tree.
- Replaying the same event is idempotent; pause/revoke cancels queued work; cycles terminate within budget.
- A compiled predicate profile reloads with identical IDs, position weights, query results and evidence mappings; incompatible profiles are rejected.
- Compare exact and weighted sensor retrieval on held-out multilingual examples with explicit error categories, memory, latency and CPU/power costs; no claim of understanding based solely on similarity.
- Scroll capture, translation and UI remain responsive while many lightweight Tendrils are scheduled.

See [portable knowledge](portable-knowledge.md), [Network Brain](network-brain.md), [agents](agents.md), [Honeycomb and Whirlwind](honeycomb-whirlwind.md), and [native core](../native/tree-core/README.md).
