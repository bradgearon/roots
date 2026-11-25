# Resonance and environmental intelligence

Status: proposal. See [design index](README.md).

## Notification streams and network analytics

Observe authorized social notifications using a platform-specific adapter (Android NotificationListenerService where available), content observations and permitted API metrics. Normalize events with source identity, content reference, event time, observation time, type, actor reference, deduplication key, scope and provenance. Notifications may be delayed, batched, suppressed or incomplete: never equate notification counts with total engagement. Estimate coverage and reconcile with authorized source metrics when possible.

Use a time-versioned graph to study early supporters, mutual interactions, community growth, bridge accounts, rediscovery, cross-language propagation, creative lineage, and audience overlap. Separate actual observed edges from inferred relationships. Report missing data, not invented network structure.

Detect unusual activity by comparing content-specific trajectories with appropriate historical baselines (author, format, exposure where known, audience size, time elapsed, topic and platform). Record acceleration, change points, observed sharing waves, source/community crossings, attention to older creations, and eventual decay or persistence. Do not infer who caused propagation without evidence.

## Vitality versus virality

Virality is the speed and extent of spread. Vitality is multidimensional: attention, appreciation, persistence, propagation, meaningful interaction, generativity, understanding and renewal. Record each as a separate observable metric or explicitly labeled proxy. A small sustained collaboration can be more valuable than a large temporary spike. Distinguish flash, cascade, bridge, evergreen, revival and collaboration outcomes.

## Conditions and repeatable success

Record conditions before, during and after a publication: subject, wording, format, imagery, language, timing, earlier conversations, audience history, early reception, source platform, community state and related external events. Compare successes with similar ordinary and unsuccessful publications, not just other hits. Preserve a Resonance Fingerprint with features, baseline, evidence, missingness, model version, observation window and uncertainty.

Hypothesis engine: use statistics and matched comparisons to establish associations; use LLMs to interpret and propose mechanisms, not manufacture causality. Distinguish repeatable choices, dependencies on other people, unique events, possible coincidences and unknowns. Suggest a follow-up creative approach and evaluate the outcome. Where feasible, test one changed condition at a time; avoid spam, engagement manipulation and exploitative recommendations.

Example: a game-development demo gains attention from musicians after a relevant community repost. Compare prior demos and plain text posts, examine measured audience differences, identify whether other works experienced renewed interest, suggest sharing a new demonstration or explaining its music-related aspects, then evaluate results.

## Environmental intelligence: Poetic Field and Kairos

Maintain time-stamped, evidence-linked environmental snapshots for user-selected geographic regions, language communities and interest networks. No exact location is required. Geographic assignment should be coarse and explicit; do not infer residence or sensitive attributes from public activity.

A Poetic Field describes recurring motifs, metaphors, aesthetic movements, expressions of hope, curiosity, grief, humor, longing and creative direction in sampled public content. Analyze original languages where practical, then provide translations. Represent observed expression, not a claim about an entire region's mental state. Include sampling window, sources, coverage, language, model version, confidence and alternative interpretations. Avoid individual psychological profiling or treating protected characteristics as targets.

Current-events research records topic, geography, event time, publication time, source reliability, citations and relevant developments. Distinguish confirmed facts from commentary. A snapshot must record when information became available; later evidence cannot silently enter an earlier snapshot.

Kairos matches personal creative history and intentions to current events, poetic motifs, cultural state and network readiness. Surface timely opportunities to create, rediscover, learn or connect, with explanation and uncertainty. A user can dismiss, snooze, or ask for more detail. Do not exploit grief or crises for attention.

Compare environmental conditions from 30 days, 7 days, 24 hours before posting, publication time, early reception and subsequent windows. Snapshot collection should precede outcomes when possible to reduce hindsight bias. Reconstructed history must be explicitly labeled.

## Background work

Candidate cadences: event-driven notification normalization and anomaly detection; public-event refresh every 1–3 hours, poetic field every 3–6 hours, culture daily, retrospective analysis daily/weekly. These are flexible proposals, not timing guarantees. Cache shared public context, batch analysis and run deeper inference on material changes. Respect platform scheduling, battery, bandwidth and user quotas. Use a distinct general reasoning model/provider; the existing PLaMo translation model is not assumed capable of this work.

Raw private notification content remains local by default. Exclude unrelated apps and private messages unless explicitly scoped. Any collective analytics must be opt-in, minimized, aggregated with disclosure thresholds and revocable sharing. Do not publish raw histories or identifiable small cohorts.

## Acceptance

- Replay a synthetic notification stream and detect a spike without double-counting repeated notifications.
- Provide a baseline comparison including non-successful examples.
- Trace every environmental assertion to a dated source or explicitly labeled interpretation.
- Show a suggested repeatable condition and a way to assess whether it helped.
- Respect permissions, data deletion, disabled workers and model budget limits.
