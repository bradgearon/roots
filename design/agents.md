# Agents and personal state

Status: proposal. See [design index](README.md).

## Natural-language agents (Tendrils)

Users create an agent by stating an intention in ordinary language: "expand my network of Japanese game developers," "find people who might collaborate on my music project," "help me explore a new interest," or "find opportunities appropriate for my engineering experience."

An intent compiler produces an editable, inspectable plan: goal, desired outcome, eligible sources, allowed data scopes, matching criteria, exclusions, cadence, budget, expiration, notification policy and action permissions. Ask for approval when scope or cost increases. No agent can grant itself permissions.

Candidate roles: Explorer (interests and communities), Connector (people), Scout (professional/creative opportunities), Muse (ideas), Scholar (learning), Curator (content), Gardener (existing connections), Bridge (languages/cultures), Resonance (success patterns), Kairos (timely opportunities). These are templates, not mandatory personas.

## Living personal state

Separate stable user-provided profile, declared skills, languages, interests, projects, creative history, verified identities, relationships, current intentions and temporary user-provided state (e.g., available time, creative direction). Each value carries origin, timestamp, scope and optional expiry. Keep inferred interests separate from explicit user statements; allow correction, removal and opt-out. Do not infer sensitive identity or psychological states from incidental content.

An agent receives only the profile fields and evidence allowed for its purpose. Availability, dating preferences and ratings visibility are authoritative user controls, not model guesses. See [social.md](social.md).

## Conversation intelligence

Analyze explicitly authorized conversation selections, preferably on-device or via the user's approved local desktop. Identify shared interests, complementary skills, open questions, commitments, follow-ups, resources, potential collaborations and creative ideas. Example: a person building procedural environments mentions interest in composition; a suggestion is to explore adaptive soundtracks together. Do not infer that the other participant desires collaboration or romance. Preserve a minimal evidence pointer, uncertainty and a proposed next step. Private conversations are not automatically shared with other agents, users or servers.

Offer ideas during or after a conversation, as appropriate to the interface, without interrupting the original interaction. Suggest replies or introductions only; user decides whether to send them. Do not impersonate or secretly contact people.

## Discovery and coordination

Agents operate on the authorized Tree of Life and its underlying relationship representation. One may find a creator, another recognize a relevant interest, another connect a current event to a past work. Share structured, scoped findings and evidence; never leak private source context across permissions. Rank opportunities by relevance, complementary interests, novelty, availability, feasibility and evidence quality; no universal popularity requirement.

Support deliberate interest expansion: familiar topics, adjacent disciplines and an adjustable exploration budget for unfamiliar areas. Offer discovered content to the timeline mixer, highlights or an inbox. A dismissed finding should not recur unless meaningfully changed.

Opt-in opportunity exchange allows two users' agents to match shared goals or complementary skills via minimal, explicitly shared opportunity descriptors; mutual availability and approval gate any introduction. No profile or private-message export is implied.

## Patrols, writing and emergence

Purpose-driven patrols extend exploration into unfamiliar public or explicitly authorized areas; see [Garden participation](garden.md). Foreign actors may participate through Forge under scoped capability and authorship contracts. Agent observations may become candidate Winds, but agents do not fabricate activity or force interpersonal connections. The [Whirlwind and Honeycomb design](honeycomb-whirlwind.md) governs reciprocal development and retention. Agent-written findings and ideas must follow the [actual writing process](tree-of-life.md), not direct model generation.

## Execution and UX

Agent lifecycle: draft -> authorized -> active -> paused/expired/completed/revoked. Show last run, discoveries, next eligibility, budget and permissions. Allow manual run, pause, edit, delete, mute, and digest. Prefer event-driven wakeups and scheduled batches to continuous polling; persist checkpoints and deduplicate results. Back off on failures; cancel work and invalidate caches when permission is revoked.

Agent output: recommendation with evidence, why it matches the user's intention, confidence/limitations, suggested next steps, expiry and feedback controls. External posts, messages, introductions, account changes and sharing private data require explicit authorization appropriate to each action. No autonomous unsolicited outreach by default.

## Acceptance

- Create and edit an agent from a short natural-language intent.
- Suggest a novel adjacent interest, a relevant person and a feasible opportunity with source evidence.
- Develop a useful idea from an authorized sample conversation without asserting another person's hidden intention.
- Pausing or revoking an agent stops its work and prevents further discoveries from being presented.
- An unavailable or blocked person never appears in a matchmaking result for that mode.

## Distributed knowledge workers and offline cultivation

Tendrils can operate against personal Roots and authorized shared Trees through the [Network Brain](network-brain.md). Desktop and approved web/server workers can run while the user is away: Explorer discovers associations, Investigator researches open questions, Evaluator challenges claims, Organizer improves neighborhoods/indexes, Translator prepares multilingual representations, Compiler builds compact packages, Distributor offers scoped deltas. A browser tab alone cannot guarantee work after closure. Agent plans specify resource budgets, source rights, scope, permitted output recipients, device preference and expiry. Separate personal benefit from explicitly opted-in contribution to a shared Tree. An idle worker does not acquire arbitrary authority to browse, message or disclose.

Workers may import a Branch, operate offline and propose independently developed knowledge or feed profiles; use [grafting](portable-knowledge.md) for ongoing authorized participation. Proposals retain provenance and uncertainty; merge requires validation/authority. Stop on cancellation/revocation, checkpoint and dedup, no unsolicited outreach. Preserve Engram AI selective upkeep principles (event-driven work, bounded lineage, rest/defer, no self-corroboration); candidate Wind/profile activation is separate from training. Agent-authored written reports still use deliberate writing.

## Bounty and Whirlwind workers

Tendrils may find matching bounties, suggest useful deliverables or perform explicitly approved bounded work, train a candidate student or prepare a Branch proposal while the user is away. Whirlwind is the peer exchange capability. A worker cannot claim a paid obligation, incur costs, accept terms, disclose data, pay or settle without independent applicable authorization. Bounty evaluation and payment use explicit terms and evidence, not AI-inferred worth. See [economy](economy-bounties.md) and [neural development](developing-neural-intelligence.md).

## Lily Tendrils

Higher-level agents coordinate [Lily Tendrils](tendrils.md): bounded persistent semantic sensors querying composed predicates and proposing evidence-linked growth. The neuronal knowledge layer provides context, but does not own Lily's sensor weights. Literal branch matching in native v1 is only an initial baseline.
