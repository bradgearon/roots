# root:// — decentralized cognitive resource addressing

Status: proposed open Roots specification; not registered or implemented. Forge remains an independent open specification. See [openness](openness.md).

## Identity and resolution

A root URI identifies a logical knowledge resource independently of the host providing it. The same Tree, Branch, node, actor, feed profile, creation, bounty or payment offer may be resolved locally, from an imported file, over an authorized service or through [Whirlwind](whirlwind-exchange.md). It is not a transport or spending permission.

Illustrative human-readable aliases: root://music/composition, root://wds/christeye, root://local/garden, root://creator/music/support, root://project/game/bounties. The local namespace is reserved to the resolving device/account; arbitrary unqualified names are not globally unique. A proposed canonical form is root://id/<issuer-id>/<tree-id>/branch/<branch-id>?revision=<digest>. URI syntax, permitted characters, canonicalization, identity encoding and reserved names need a normative grammar. Aliases resolve through signed, scoped name records with expiry, sequence, issuer and target identity; never silently merge identities because aliases match. Exact revision and mutable head references are distinct. Pin a revision for reproducible retrieval.

Resolution order may prefer local cache, imported package, approved peer/provider and network lookup according to the user's policy. A request includes scope, purpose, deadline, network/budget settings and compatible profile. Return identity, manifest/revision, authorized locations/capabilities, evidence of mapping and freshness; indicate stale, offline, unresolved, conflict, unsupported or denied explicitly. Don't leak private existence through public discovery. Validate signatures, hashes, alias ownership, redirects, hop limits, URI decoding, payload and scheme allowlists; resist SSRF, malicious deep links and impersonation. Untrusted links cannot invoke actions, execute code, subscribe, graft or authorize payment. Require independent capability and action authorization.

## Experience and interoperability

Roots browser can handle both HTTPS source content and root:// knowledge resources, providing graph-derived navigation, search, activation, narration, download, subscription, grafting and contribution. An HTTPS gateway is optional; no mandatory WDS resolver, blockchain, DNS, central service, proprietary SDK or LLM. Support file-only implementations with stable offline identities. Use Forge to communicate actor operations, including root.resolve, root.describe, root.search, root.activate, root.subscribe, root.transfer, root.graft, root.verify and root.contribute. These are candidate capabilities, not a finalized wire protocol. Define URI grammar, canonical signatures and interoperable vectors before pursuing URI scheme registration; reference RFC 3986 and RFC 7595 for design and registration. Existing browser/OS support cannot be assumed.

Acceptance: resolve identical pinned resource from file and independent peers; conflicting alias ownership is not silently resolved; verify head updates, revoked grants, offline and stale states, hostile links and private scope isolation; opening a payment or bounty link performs no financial action. Test two independent implementations and document gateway behavior.
