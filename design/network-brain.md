# Network Brain — shared cognition, weighted feed assembly and Engram AI

Status: integrated design proposal, 2026-11-25. Not implemented in Roots. See [portable knowledge](portable-knowledge.md), [Tree of Life](tree-of-life.md), [Honeycomb and Whirlwind](honeycomb-whirlwind.md), [openness](openness.md).

## Responsibility and architectural boundary

The Network Brain manages the network's accumulated understanding. It is distinct from personal poetic micro-cognition, translation, native social services and the UI. Personal Roots retain the participant's history, intentions and relationships; a shared Tree retains eligible common cognition. Independent Trees can operate without a central service and without ChristEye; the WDS Roots application may use ChristEye and Engram AI as its implementation approach. Forge and Roots are intended open specifications, not exclusive WDS runtimes.

An eligible public post observed in the viewport is a candidate for content comprehension, not proof that the observer endorses it. Local comprehension may extract typed subjects, predicates, concepts, explicit claims, source identity/revision, language, creative lineage, uncertainty, candidate associations and embedding references. Separate common content understanding from personal relevance. Deduplicate by source revision, model/configuration and authorized sharing scope; cache where permitted. Contributions may be automatic only under a specific informed opt-in and source/extension policy permitting that use; otherwise keep local or use deliberate share actions. A shared representation remains subject to source rights and deletion even without raw text. Other participants can reuse approved cognition and independently propose corrections, not simply count echoes as corroboration.

## Existing implementation reference: Engram AI (not the biological Anaiero project)

Primary reference: [bradgearon/engram-ai](https://github.com/bradgearon/engram-ai), WDS AI v0.9.1 README. The earlier Anaiero living-computer controller/optimizer is a separate precedent, not the evidence for this neural mesh. The following are software roles inspired by ChristEye, not verified biological equivalences.

| Engram AI mechanism | Roots adaptation proposal |
| --- | --- |
| Arc journal; engrams and astroengrams | Authoritative versioned observations, claims, feedback, source refs; separate feedback from independent evidence |
| IGFBP2 indexer | Scoped numerical location/attention; returns selected IDs and scores, not unrestricted payloads |
| IGF2BP2 handler | Fetch selected records and validate source version/scope |
| Neuronal mesh | Bounded connected propagation, lineage and cycle suppression |
| Wind policy | Small numerical decision/readout weights, separately trained/evaluated/activated |
| RNA microtile | Portable derived numerical memory; compare with GGUF and flat arrays |
| Heart | Independent evidence/policy/outcome judgment; do not equate confidence with truth |
| Persistent response assembly | Adapt to persistent ordered feed/reading assembly; pin source and policy versions |
| Selective upkeep | Event-driven, budgeted background development and explicit rest/defer |

Sources: [NEURAL_MESH](https://github.com/bradgearon/engram-ai/blob/master/docs/NEURAL_MESH.md), [ENGRAM_RESPONSES](https://github.com/bradgearon/engram-ai/blob/master/docs/ENGRAM_RESPONSES.md), [COGNITIVE-RUNTIME](https://github.com/bradgearon/engram-ai/blob/master/docs/COGNITIVE-RUNTIME.md), [FORMATS](https://github.com/bradgearon/engram-ai/blob/master/docs/FORMATS.md).

Existing policy.wind v1 is 1,152 bytes: 64-byte header, 16x16 float32 W, 16 float32 biases; the neural/expression readouts use seven rows over sixteen features. Existing RNA tiles are 256 bytes: 32 float32 hashed-key lanes, 16 feature lanes (first reliability; others reserved), 16 reserved lanes; export includes 64-byte header and sibling provenance index. The keys are not a production multilingual embedding model. The index is a bounded root slice, not an ANN service. Existing checksums are not cryptographic signatures. Keep original formats intact if imported; define new Roots profiles with explicit compatibility, not silently repurpose reserved lanes.

Existing cognitive runtime v0.8 uses the event-step eligibility rule:
```
e_next = min(2, 0.8 * e + 1)
w_next = clamp(w + 0.2 * outcome * e_next - 0.01 * w, -2, 2)
u_next = 0.5 * u + 0.5 * tanh(drive + 0.2 * u)
```
The last recurrence runs four bounded steps over matching predicate edges in that learner. Adaptation to feed ranking is an experiment, not an existing implementation; its outcome is reported success/failure on finite scalar-state demonstrations, not human worth, engagement, or biological time. Do not change factual knowledge weights on a mere view or skip. Existing preconditions and conflicts gate operations independent of activation.

Recorded evidence (repository reports, not independently rerun): [v0.6](https://github.com/bradgearon/engram-ai/blob/master/docs/VALIDATION-v0.6.md) reports 14/14 scalar and 16/16 native CPU CTests, 18/18 held-out synthetic action classifications, connected traversal/cycle suppression, scope, replay, bounded work and local decisions without generation. [v0.8](https://github.com/bradgearon/engram-ai/blob/master/docs/VALIDATION-v0.8.md) reports 23/23 CTests in each of two configurations and bounded held-out entity transfer, recurrence, contradiction and restart checks; explicit typed preconditions/conflicts still gate action. [v0.9](https://github.com/bradgearon/engram-ai/blob/master/docs/VALIDATION-v0.9.md) reports 25/25 CTests in each of two configurations; the experimental 64-unit English sequence learner matched 0/240 held-out final turns and accepted 0/240. Useful assisted conversation comes from the teacher. No evidence here establishes broad language understanding, production ANN, social feed ranking quality, biological efficacy, real Windows GPU throughput or distributed GGUF inference. Use PLaMo for translation, validated task-specific comprehension/embedding providers for content, and deterministic native retrieval; FunctionGemma and SmolLM2 are candidate providers requiring task evaluation, not assumed capable by size alone.

## Feed as activated expression of the Tree

A feed is a durable, scoped assembly of references and presentation plans. It is not a generated answer, a copied third-party feed or a list of global popularity scores. Its input is a Tree revision plus personal activation profile, declared intention, current eligible context and distribution policy. One shared Tree can express many different feeds. The same selection can drive the native visual Garden, accessible narration, scoped replacement proposals or external application API.

Separate weight spaces:
- Knowledge edges: relation type, strength, confidence, evidence, version, time; not preferences.
- Activation: query/intention/interest seeds, recency, declared conditions; local by default.
- Distribution: lane budgets, source caps, creator diversity, exploration, original vs rediscovery vs native vs organic recommendation; not knowledge.
- Feedback: explicit relevance, saves, appreciation, dismissals, validated outcomes; separate from observation/exposure and peer ratings.

Candidate deterministic reference procedure:
1. Freeze eligible Tree/Branch revision, policy, profile, time window and budgets. Resolve scopes before retrieval; private dating/availability cannot leak through unauthorized neighbors.
2. Retrieve semantic and lexical seeds; apply typed geography, category and time filters. A missing attribute is unknown, not zero.
3. Expand a bounded sparse neighborhood by relation type with cycle suppression, max depth/fan-out, evidence paths and confidence. A candidate spreading rule is a_next = (1-alpha)*seed + alpha*P_transpose*a, with bounded normalized nonnegative transition weights, 0 <= alpha < 1, finite iterations. Contradictions/negative evidence handled separately. Preserve the simpler exact search baseline.
4. Rank candidates using a versioned normalized feature vector and explicit profile coefficients, e.g. relevance, fellowship, creativity, novelty, trusted recognition, freshness and evidence. Penalize repetition and unsupported claims separately. Explain each result using source refs and graph paths, not invented motives.
5. Assemble using deterministic weighted lanes (fellowship, discovery, research, history, native, etc.), fairness, quotas and diversity; no universal engagement objective. Tie-break by stable ID. Save cursor/seen ledger; maintain stable ordering within a session and check invalidation at each presentation. Distinguish organic recommendations from separately labeled paid sponsorship. Source attribution, original reveal and correct-source actions remain mandatory.
6. Resolve selected content via permitted adapters/embeds/cache, or show an explicitly labeled reference/summary if unavailable. A reference-only offline Branch cannot reconstruct a missing original. Translation/TTS on demand and lookahead; no need for inference across the full candidate population.
7. Record exposure, feedback, useful outcome and provenance separately; propose changes for review. Learn local preference/distribution before changing shared knowledge. Never send unsolicited outreach or silently alter a person's relationships.

Hard eligibility constraints precede scores and recheck before output/action. Ratings are peer-submitted and independent from dating; no general coolness ranking in romantic matching. Coarse location/availability are typed scoped policy records, not shared unrestricted embeddings. Protect blocks, age eligibility, sparse region and anonymity. Keep unscored participants discoverable.

## Life-supporting expression and Doxa (WDS/ChristEye profile)

Romans 11:16–24 (especially 17–18, 23–24) uses grafting into the olive tree to discuss Israel and Gentile inclusion and warns against boasting over natural branches. Galatians 6:2 calls for bearing one another's burdens; John 15:1–8 depicts abiding and fruitfulness; 1 Corinthians 12:21–26, Philippians 2:3–4 and 4:8, and 1 Peter 4:10 support mutual service, excellence and appreciation. The application to knowledge grafting and mutual nourishment is a design analogy, not replacement of Israel or a claim about the spiritual status of any person.

The Tree cultivates beauty, art, creativity, knowledge, craftsmanship, affection, fellowship, service, hope and demonstrated fruit. Celebrate beautiful men and women, talented engineers, artists, musicians, leaders and other gifts without treating achievement, appearance, popularity or authority as personal superiority. Distinguish self-confidence/excellence from contempt, humiliation or exploitation. These are optional descriptive feed intentions and user choices, not an automated pride detector, spiritual score, beauty ranking or political ideology classifier. Doxa gives recognition without making it a zero-sum worth contest. The open specification does not require the WDS theological interpretation.

## Background and transfer

Personal desktop and authorized server workers can investigate unresolved questions, index approved content, compare candidate relationships, validate evidence, translate, compile or compact scoped Branches, and prepare feed/reading assemblies while the user is away. Browser closure requires desktop or server execution; Android jobs are constrained by OS/battery. Workers use durable event-triggered jobs, budgets, permission checks and rest/defer states; no arbitrary browsing automation or artificial engagement. A contributed result is a proposed delta, with identity, source, evidence, model/algorithm version and scope; the receiving authority judges and commits it. No model gradient exchange is required. A profile can travel with a Tree but must be independently accepted, and user-private state is not included unless explicitly exported. See [portable knowledge](portable-knowledge.md) for graft and offline profiles.

## Services and proposed Forge capabilities

NetworkCognitionIntake, KnowledgeConsolidation, ArcProjection, KnowledgeLocationIndex, KnowledgePayloadResolver, GraphActivation, FeedCandidateRanker, FeedAssembly, FeedCursor, ExposureLedger, ActivationProfile, FeedEvaluation, BranchGraft, KnowledgeWorker. All models are passive records; service logic testable, transaction writes single-authority per scope.

knowledge.observe, knowledge.query, knowledge.activate, tree.activate, branch.activate, branch.graft.propose, branch.graft.commit, branch.graft.detach, branch.regraft.propose, feed.assemble, feed.next, feed.explain, feed.evaluate, activation.profile.create, activation.profile.compare, activation.profile.publish. Versioned scoped inputs/outputs, actor ownership, stable IDs, cancellation, budgets, evidence and typed errors. These are names for proposed Roots capabilities conveyed through Forge, not existing endpoints. Keep old language.detect/translate contracts compatible.

## Qualification plan

Use Portable Tree 001: 10k synthetic/licensed records for correctness, then 100k and 1M for capacity. Compare chronological, keyword, vector-only, weighted graph and hybrid; freeze profile candidates and measure held-out retrieval relevance, diversity, useful discovery, source fidelity, fairness, exposure repetition and explicit user satisfaction (not engagement alone). Use blinded human judgments where practical; no feedback loop that declares its own output evidence. Test no LLM call during ordinary cached assembly, offline export/import on two implementations, graph activation reproducibility, versioned feed cursor, revocation between retrieval and presentation, graft conflicts, deleted sources, incompatible vector spaces, policy leakage, malicious package and echo poisoning. Bench cold/warm latency, memory, CPU/GPU crossover, energy, compilation and delta size. No claim of production validation until measured.
