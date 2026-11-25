# Social ratings, recognition, dating and availability

Status: proposal. See [design index](README.md).

## Explicit product distinction

The optional social scoring feature is a peer-submitted rating of how cool, interesting, creative, funny, original, stylish, charismatic or enjoyable a person seems to other participating users. It is NOT an XP/quest system, a compulsory social credit score or an AI-assigned assessment of human worth.

A user can submit 1–10 ratings for chosen categories, skip categories, revise or withdraw a rating, and provide optional positive awards. Example categories: coolness, creativity, humor, interestingness, originality, conversation, style, charisma and inspiration. Category definitions, titles and level thresholds are configurable proposals. Do not treat subjective impressions as verified personality traits.

Profile display may show an opted-in aggregate, rating count, uncertainty and optional social level/title (e.g., Rising Star, Original, Luminary, Icon, Legendary, Mythic). Separate category mean from evidence volume; provisional small samples should not appear as precise rankings. Use one current rating per eligible rater/recipient/category. Consider Bayesian shrinkage or another documented confidence adjustment, independent-rater counts and transparent aggregation; do not lock in a formula before testing. Avoid rewarding raw follower counts or treating popularity as merit. No XP from quests is required.

Allow personalized discovery based on categories and ratings from people whose taste the user trusts, only when the rated person allows it. Keep unscored people discoverable. No scoring of nonparticipants. Content awards (Doxa), subjective peer ratings and measured content vitality remain distinct.

## Availability is separate from participation

A module may be enabled while the user is unavailable to receive interactions. The following are independent controls for each applicable mode, not one global toggle:

| Dimension | Examples |
| --- | --- |
| Feature enabled | Off / On; hide module UI when off |
| Outbound participation | Browse, give ratings, initiate permitted requests, or disabled |
| Inbound availability | Available, selective, temporarily unavailable, disabled |
| Discoverability | Nobody, approved matches, connections, selected communities, public where appropriate |
| Visibility | Private, selected audience, connections, public where appropriate |
| Approval | Direct permitted interaction, request approval, deny |
| Schedule | Optional expiry, pause-until, availability windows |

Apply to ratings (give, receive, display, discovery separately), awards, friendship, collaboration, professional opportunities, language exchange, fellowship, agent introductions and dating. Modes do not inherit each other's availability. A person can rate others without receiving ratings, receive ratings privately without displaying levels, pause dating while retaining settings, or allow collaboration without friendship requests.

Policy evaluation: feature enabled for initiating action; initiator allowed outbound action; target opted into applicable inbound mode; scope/audience match; no block or safety restriction; discoverability and approval check. Enforce on both retrieval and action endpoints, recheck before sending, default deny on uncertainty. Unavailable means not eligible for new matching/requests, not merely invisible UI. A person may browse non-matching public information when allowed but must be mutually eligible for introductions. Pause and revocation invalidate queued proposals. Provide clear explanations without revealing private settings.

## Dating

Dating is independent, optional, off by default, and limited to mutually opted-in adults with compatible explicit relationship intentions. Browsing, availability, profile exposure and ability to receive requests are distinct. No inferred romantic availability, no ranking by general social score, no coercive ratings or hidden compatibility claims. Keep dating information out of non-dating discovery and other agents by default. Use coarse user-selected location only where needed. Allow blocks, reports and immediate withdrawal; no contact after a block. No unsolicited agent messages.

Conversation suggestions can identify shared interests or possible topics, not determine attraction. Matching and introduction must honor both people's current settings and approval.

## Abuse and privacy

Opt-in receiving ratings is mandatory. Consider connection-based eligibility, rate limits, anti-Sybil checks, minimum aggregate sample, detection/review of coordinated voting, moderation/reporting, blocking, and appeals. Avoid public shaming, lowest-rated lists, anonymous targeted abuse, exposing raw private votes, or allowing scores to restrict essential app features. Protect minors; adult-only public personal rating and dating are recommended launch boundaries pending review. Opting out must not penalize users or leave a hidden public score.

Record consent/version, evidence for moderation decisions, and retention policy. Permit export/deletion and rating withdrawal. Do not expose rating details beyond authorized audiences. Keep an audit trail of policy changes without retaining deleted personal content unnecessarily.

## Acceptance

- User can enable ratings, give one, and be unavailable to receive any.
- User can receive ratings privately and later hide or withdraw the aggregate.
- Dating can remain disabled while every other module works.
- An unavailable, underage or blocked candidate cannot be matched, rated or contacted through an ineligible mode, including stale queued agent results.
- Concurrent policy changes are checked server-side at action time.
- Small sample, coordinated votes and withdrawn ratings produce correct, explainable aggregate behavior.
