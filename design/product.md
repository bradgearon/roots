# Product and experience

Status: proposal. See [design index](README.md).

## Highlights and recognition (Doxa)

Favstar-inspired experiences include personal historical highlights of one's creations, saved favorites, meaningful conversations, best-of-day/week/year/all-time views, search by author/network/topic and creative milestones. Keep private saves separate from public appreciation. Rediscover older works and unfinished ideas, including work that is newly relevant to current events.

Users can award particular content: Golden Star, Hidden Gem, Brilliance, Inspiration, Bridge, Masterpiece or favorite of the day, with an optional reason. A trophy cabinet records recognition received and given, with attribution and user-controlled visibility. Awards are Roots-native, not imported likes; permit revocation, abuse reporting and configurable limits.

Recognition from someone whose taste a user trusts is a discovery signal, not a reason to rank all creators by popularity. Display metrics with source and observation time; distinguish likes, impressions, awards and bookmarks.

## Cross-network graph and Gardens

Represent people, verified external identities, content, events, interests, resources and typed relationships. Candidate adapters: X, Bluesky, Mastodon, GitHub, YouTube and personal sites, subject to platform permissions and rules. A manually shared URL provides a fallback. Verify identity ownership; never merge people solely because handles match. Respect deletions and visibility changes; retain only authorized previews or references.

Gardens are shared collections of people, knowledge, interests and creations. A creation can belong to multiple Gardens. Curators and community awards support discovery. Creative lineage links include inspired-by, builds-upon, translates, explains, implements, remixes and collaborates-with; distinguish explicit attribution from inferred links.

## Intelligent timeline presentation

Users may describe preferences in natural language: "replace posts from this creator," "less repetitive arguments," "more original artwork," or "show older favorites occasionally." Compile them into editable rules and model-assisted classifiers. Support creator, topic, format, repetition, language, tone, source, protected accounts, and allowlist rules.

Use the same observation and viewport mechanisms already present for Android translation as a best-effort lookahead for upcoming posts. Chrome can observe eligible DOM content. Do not assume that every offscreen post is exposed.

Proposed pipeline:
1. Observe content identity, author, available text, bounds and viewport generation.
2. Apply explicit rules; classify ambiguous posts only using a permitted inference provider.
3. Select a replacement from approved networks, highlights, recognition or agent discoveries; cache and pre-render when possible.
4. Display an attributed Roots replacement within the eligible original slot. Provide immediate reveal/undo, and route replacement actions to the correct source.
5. Learn from explicit feedback; respect user intent and avoid silent identity-based filtering.

Android overlays cannot reliably resize another app's layout. Clip or choose content appropriate for existing bounds; never cover unrelated UI or system prompts. Cancel stale work on scroll, row reuse, content mutation or identity change. Fail open to original content on unsupported screens, latency or uncertainty. Preserve gestures, original accessibility and translation controls. A complete replacement feed is a separate future option.

Modes: natural, inspiration, fellowship, exploration, knowledge, nostalgia, peace. A content mixer balances original content, new discovery, historical highlights and other sources; allow an exploration budget instead of merely reinforcing existing preferences.

## Experience

Proposed navigation: Home, Discover, Highlights, Network, Profile; agents and availability reachable directly. Show why a replacement or recommendation appeared, its network and author, and allow users to adjust rules. Ratings and dating remain independently optional; see [social.md](social.md).

## Initial acceptance

- Save a link, find it later, and award it independently of the originating platform.
- A hidden/replaced item can be restored with one action.
- Scrolling and recycled rows do not misplace replacements or cause wrong-account actions.
- No background upload of private observations by default.
- Recognition, collections and profile controls remain usable without overlays or external API access.
