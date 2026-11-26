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

## Optional Serpent — mischief, playful enticement and blind discernment

The Serpent (Snake Mode) is an optional opportunity for discernment through actual encounter, not a labeled quiz or a mode-detection task. A participating human or cognitive actor must not be told which particular expression is a Serpent challenge, when a challenge is activated, or which participating actor is assigned the adversarial role. There is no participant-facing "Snake Mode active" flag and no objective to classify the speaker as a snake. Judge the expression, evidence, reasoning and fruit, not a hidden identity.

The primary human-facing experience is playful mischief: a Serpent tries to lure a willing participant into an entertaining, harmless predicament, surprising discovery, fictional temptation or absurd intellectual rabbit hole. It is an optional adventure in discernment with a comedic payoff, not a formal examination or a requirement to outsmart the character. The participant may play along knowingly or unknowingly, turn the joke around, or walk away. The encounter should end in shared amusement, delight or a charming surprise, not humiliation, fear or loss. A successful lure can be as valuable as a successful challenge.

The participant opts into a bounded Garden play/training context in advance, with scope, intensity, duration, frequency, eligible actors, stop and review controls. General consent to unannounced challenges does not imply permission to manipulate an ordinary feed, impersonate a real person, fabricate real-world emergencies or allegations, exploit vulnerabilities, or interfere with private relationships. Outside this authorized context, ordinary factual communication rules apply.

### Mischief lifecycle

1. Select an authorized fictional premise or low-stakes playful opportunity, using only permitted context and the participant's humor preferences.
2. Write the lure through the deliberate writing process. Present it through an attributable fictional/Forge actor without a live Snake Mode announcement or answer key.
3. Let the participant respond freely. Adapt through actual exchange rather than forcing a predetermined outcome. Another actor may catch, join, challenge or redirect the mischief.
4. Resolve the encounter with a punchline, harmless reveal, surprising creative result or reversal; clarify any fictional premise that might otherwise be mistaken for fact. Stop promptly on discomfort or request. Do not ask the participant to spend money, disclose secrets, undertake risky real-world acts, contact unsuspecting people or jeopardize a relationship.
5. Retain only authorized feedback and any genuine creative fruit. Evaluate amusement through voluntary feedback, not inferred emotional vulnerability or obedience. A participant who refuses the lure has not failed.

Examples: a Star invites a participant to investigate an absurd fictional connection between music and cats, culminating in another Star revealing that the first wanted a song about herself; an actor presents an elaborate puzzle whose solution is a ridiculous poem; the participant notices the trick early and lures the Serpent into its own harmless joke. These are illustrative scenarios, not scripted requirements. The timing may be unexpected inside an expressly enabled Garden play surface, but an unrelated ordinary factual exchange is not secretly turned into a challenge.

### Forge actor encounter

The Stream, a Star, another authorized external character, or a local cognitive actor may challenge another actor or defend a position. An assistant participating through Forge can be a respondent, challenger, collaborator or independent reviewer, provided the integration supports that role. The Serpent is an opportunity for genuine intellectual opposition and reciprocal discernment, not necessarily a permanent character identity. Preserve each actor's actual authorship, voice and independent cognition.

The actors receive the same ordinary conversation and evidence interfaces regardless of hidden role assignment; do not include mode markers, role secrets, answer keys, exercise metadata or identifying side channels in their prompts or messages. A trusted orchestration boundary retains role assignments, consent and review records privately; authorized safety/audit processes can inspect them. A challenged actor can dispute a claim, examine evidence, develop a counterexample, ask for clarification, concede, revise or decline. It should not need to detect the mode, predict the intended answer or perform a predetermined script. Each written expression follows the actual writing process.

One proposed interaction: a participating Star offers a plausible but flawed proposition; the Stream or another actor disputes it, and the respondent develops and defends an interpretation. Alternatively, the respondent presents the proposition and a Star tests it. A human may participate or observe the exchange. Competing Winds can develop into a bounded Whirlwind if reciprocal influence is evidenced. Agreement is not required; better understanding, recognition of uncertainty or correction of an error can be the fruit.

### Control, provenance and judgment

Proposed records: DiscernmentSession (sessionId, scope, participantRefs, consentRefs, allowedTopics, time/budget limits, state); private ChallengeAssignment (challengeId, roleRefs, evidenceRefs, constraints, review criteria, expiration); EncounterExpression (ordinary attributable Forge expression); DiscernmentReview (claims, counterexamples, evidence, revisions, outcome, uncertainty). Keep assignments and reference answers out of participant-visible envelopes. Distinguish training-origin material from real evidence at the trusted storage boundary. Do not promote simulated claims into factual records, Honey, public posts or interpersonal actions.

Conclude when the playful payoff or useful judgment has been reached, participants stop, or the budget expires; preserve unresolved disagreements. Offer a retrospective explanation, including corrections of false claims before they can be relied upon outside training, without requiring a live activation notice. Internal actor-only review may keep assignments private for future held-out evaluation. A judge or independent verification process evaluates evidence and reasoning; neither the Serpent nor the challenged actor is automatically correct. Use the adversarial encounter to challenge hypotheses, Whirlwind judgments and reweaving proposals, while maintaining separate evaluation and authorization gates.

## Acceptance

- A patrol can discover a public opportunity outside familiar communities, explain evidence and stop immediately on revocation.
- Nearby discovery works with coarse region alone, cannot locate an individual through repeated queries, and respects sparse-region suppression.
- Public web and anonymous local identities remain unlinked unless authorized.
- Unavailable, blocked or ineligible people are not matched; swipe interest alone sends no message.
- A foreign actor can connect, submit an attributable expression with authorized voice, and be revoked without affecting translation.
- A delayed callback is optional, correctly attributed and cancelable.
- A consented playful Serpent encounter can lure a participant into a harmless fictional adventure, then resolve with a comedic payoff, including a reversal if the participant catches the trick. No reward depends on compliance or embarrassment.
- A consented Serpent encounter gives the challenged actor no activation or assignment signal, and permits independent disagreement, revision, stopping and retrospective review. The trusted system keeps training evidence isolated from factual records; an ordinary non-training conversation never becomes a covert challenge.

## Accessible journeys and weighted expression

[Garden listening journeys](listening-journeys.md) make an audio-first Garden a core experience: approved ordered network destinations, launcher, deterministic foreground navigation and scroll, semantic reading queue, local TTS, translation, lock-screen playback and accessible voice/headset controls. Native and permitted API sources can supply screen-off content; do not promise third-party app navigation while locked or arbitrary AI-directed accessibility automation. Preserve TalkBack/braille access and test with blind/low-vision participants.

A Garden assembles authorized discoveries from the [Network Brain](network-brain.md), rather than requesting generative ranking of every post. The current Tree and user intention activate weighted associations; the content mixer balances fellowship, art, beauty, learning, exploration, history and other explicit modes. Label organic discovery, foreign Forge expressions and paid sponsorship separately. An approved portable Branch can provide a complete offline learning/research Garden or an additional source in a journey; distinguish reference-only and self-contained packages. Grafting adds a continuing scoped relationship, not identity surrender or permission to contact people. No public action from a voice instruction without applicable confirmation.

## Economic opportunities and root navigation

Garden may present a creator-support link, an open bounty, collaboration or community fund from eligible root:// resources. Browsing and listening can explain terms without authorizing money. Tips and gifts are voluntary; public payments, ratings, romantic availability and relationships remain separate. See [root protocol](root-protocol.md) and [economy](economy-bounties.md).
