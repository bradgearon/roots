# Garden participation — patrols, nearby presence and foreign voices

Status: proposed design; 2026-11-25. Copyright (c) 2026 We Develop Software LLC. All rights reserved. [License](../LICENSE).

The Garden is the environment for the development of the Trees. Roots Web and the Android, browser and desktop integrations provide different ways to participate. See [Tree of Life](tree-of-life.md). Gardens in the UI are scoped communities within this broader architectural concept.

## Purpose-driven patrols

Patrols extend natural-language agents beyond personal-interest recommendations. A participant can specify a purpose, permitted unfamiliar areas to explore, sources, observation limits, frequency, cost, discovery scope and notification policy. An editable intent plan authorizes bounded research; the agent cannot grant itself new access.

Candidate roles include Hall Monitor (affection, fellowship, encouragement and appropriate introductions), Vigilante (public evidence of wrongdoing, legitimate assistance or reporting), Guardian (public requests for help), Gem Hunter (overlooked creators), Ambassador (cross-cultural connections), Peacemaker (constructive dialogue), Detective (open questions) and Benefactor (ways to contribute). These names describe user-chosen purposes, not institutional authority.

Patrols return sourced findings, uncertainty, suggested actions and relevant policy constraints, composed through the deliberate writing process. They can suggest legitimate reporting or assistance but cannot target people for intimidation, monitor private locations, circumvent privacy, deanonymize people, publish allegations as facts or organize harassment. An unfamiliar area is not inherently dangerous or undesirable. Require authorization for external interactions; no autonomous unsolicited outreach.

A finding may become a candidate Wind or opportunity, and a series of consenting interactions may form a Whirlwind. Never fabricate social activity to make an episode appear successful.

## Central optional anonymous geographic presence

Geographic presence is a shared, optional Tree of Life capability, not a dating-only feature. Users may discover nearby communities, creative collaborators, friendships, language exchange, service and romantic opportunities. Keep availability, participation, discoverability and visibility independently configurable per mode.

Use a coarse user-selected region or explicitly authorized coarse location, with no need for continuous precise tracking. Store minimal region membership and policy; avoid live coordinates, movement histories, exact distances, proximity ordering that reveals locations and maps of individual members. Suppress sparse cohorts; use coarse distance labels, query rate limits, stable bucketing, and anti-triangulation testing. Do not reveal a person's anonymous presence through aggregate changes, recommendations or public identifiers.

Anonymous/pseudonymous presence is anonymous to other participants, not a promise that infrastructure holds no identity or security metadata. Maintain separation from public web identity; any linkage is an explicit, revocable user choice. Use non-correlatable mode identifiers where practical and limit cross-mode access. Provide pause, region change, withdrawal, deletion, blocks, reports and safety guidance. Do not disclose private identities on a match automatically.

Candidate records: NearbyPresence (opaque mode-specific id, coarse region, purpose, policy version, availability, consent, expiry); NearbyDiscovery (candidate reference, permitted explanation, region band, policy version, expiry); MutualIntroduction (two scoped references, intent, explicit approvals, state). Authorize read and action independently, including stale queued results.

## Swipe discovery (including optional Honeys dating view)

Swipe cards may support adult dating, friendship and creative discovery as separate modes. Use declared interests, compatible intentions, scoped profile content and coarse region. Pass, save for later, express interest, block and report are distinct actions. An interest does not initiate a message or expose a private identity. Recheck eligibility and both parties' explicit interest before opening any introduction; require consent for further disclosures.

Dating is independently optional and off by default, adult-only, and governed by the settings in [social.md](social.md). Do not infer attraction, orientation or romantic availability. Do not use general social scores as romantic desirability. Foreign characters are explicitly labeled and excluded from human dating matches. Preserve the ability to discover local people without joining dating or ratings.

## Foreign actors and voices through Forge

The existing ForgeActorReference, ForgeServiceRequest, ForgeServicePacket, correlation IDs and state update notices are the transport-neutral foundation. Proposed actor connections let independently hosted characters from Six Stars, Anaiero or other systems retain their own identities, memory, knowledge, expressive voice and internal architecture. Roots provides authorized places for their expression, not ownership of their cognition.

Proposed ActorConnection record: connectionId, actorRef (forgeId, actorId, actorType), verified issuer, capabilities, granted scopes, display identity, human/nonhuman declaration, availability, voice capability descriptor, transport/security metadata, version, revocationState.

Proposed ExpressionEnvelope: expressionId, actorRef, connectionId, originalContentRef, contentType, createdAt, intended audience, presentation constraints, optional audioRef or approved voice descriptor, provenance, authorization, expiry, correlationId. Receive and validate independently of presentation. Preserve the external actor's authorship and original expression; clearly label translations or user-approved modifications. Voice may be delivered as authorized audio or rendered via a licensed, consented voice provider; do not impersonate real people. Keep a separate transport/capability boundary for audio; no automatic dependence on game or TTS libraries in translation.

Proposed operations: actor.connect, actor.capabilities, actor.disconnect, expression.submit, expression.schedule, expression.revoke, voice.capabilities, voice.render. Authenticate the originating actor, validate schemas and permissions, treat incoming content as untrusted data, sanitize rendering, bound budgets and support cancellation/revocation. External actors receive no implicit access to private history, ratings, location, dating, or other actors' memory. They may participate in permitted conversations, creative work, patrols, discernment and fellowship.

Optional callbacks can present a clearly labeled fictional/external character's joke, encouragement or commentary at a later appropriate moment. Respect user-selected frequency, context, tone and mute controls. Avoid targeted harassment or shaming people for sexual orientation, identity or other protected traits; consensual playful humor can relate to a user's declared intentions. Do not disguise an actor as a real person or insert content as if it came from the underlying platform.

## Optional Serpent — discernment practice

The Serpent is an optional role, possibly connected through Forge. It presents bounded, identifiable exercises involving plausible falsehoods, defective reasoning, tempting interpretations, conflicting evidence, gossip, flattery or inappropriate application of knowledge. The purpose is practice in discernment (Hebrews 5:14), not coercion or covert deception.

Participant chooses scope, frequency, difficulty, pause and stop. An exercise has scenario, evidence refs, response options or free response, review rubric, explanation, uncertainty and debrief. Evaluate reasoning rather than demand predetermined assent. No fabricated real-world emergency, deceptive impersonation, covert attacks on relationships or exploitation of vulnerabilities. Distinguish exercise events from real observations and never deposit them as factual evidence.

Internally, a separate adversarial review process can challenge hypotheses, Whirlwind judgments and reweaving proposals, retaining counterexamples and independent evidence. Do not assume the Serpent is required for all cognition or that its challenge establishes truth.

## Acceptance

- A patrol can discover a public opportunity outside familiar communities, explain evidence and stop immediately on revocation.
- Nearby discovery works with coarse region alone, cannot locate an individual through repeated queries, and respects sparse-region suppression.
- Public web and anonymous local identities remain unlinked unless authorized.
- Unavailable, blocked or ineligible people are not matched; swipe interest alone sends no message.
- A foreign actor can connect, submit an attributable expression with authorized voice, and be revoked without affecting translation.
- A delayed callback is optional, correctly attributed and cancelable.
- A Serpent exercise is clearly distinguishable from real evidence and has a reviewed explanation and opt-out.

## Accessible journeys and weighted expression

[Garden listening journeys](listening-journeys.md) make an audio-first Garden a core experience: approved ordered network destinations, launcher, deterministic foreground navigation and scroll, semantic reading queue, local TTS, translation, lock-screen playback and accessible voice/headset controls. Native and permitted API sources can supply screen-off content; do not promise third-party app navigation while locked or arbitrary AI-directed accessibility automation. Preserve TalkBack/braille access and test with blind/low-vision participants.

A Garden assembles authorized discoveries from the [Network Brain](network-brain.md), rather than requesting generative ranking of every post. The current Tree and user intention activate weighted associations; the content mixer balances fellowship, art, beauty, learning, exploration, history and other explicit modes. Label organic discovery, foreign Forge expressions and paid sponsorship separately. An approved portable Branch can provide a complete offline learning/research Garden or an additional source in a journey; distinguish reference-only and self-contained packages. Grafting adds a continuing scoped relationship, not identity surrender or permission to contact people. No public action from a voice instruction without applicable confirmation.

## Economic opportunities and root navigation

Garden may present a creator-support link, an open bounty, collaboration or community fund from eligible root:// resources. Browsing and listening can explain terms without authorizing money. Tips and gifts are voluntary; public payments, ratings, romantic availability and relationships remain separate. See [root protocol](root-protocol.md) and [economy](economy-bounties.md).
