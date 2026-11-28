# External Mesh Network Explorer

## Desired experience

Every public publisher can be browsed by display name, public member/axon identity, declared topics and capabilities. Blank search explores public nodes, exact topic lookup narrows candidates, and richer name/full-text search is a later local index over independently retrieved signed cards. Search is a priority hint, never a whitelist: default public interest is all topics. The network automatically joins eligible public Axons without a per-peer dialog after open-exchange consent. Auto-join is bounded, revocable, and independent from private compute authorization. Internal Mesh membership is never inferred from a public listing; invited members can browse authorized private devices and capabilities separately.

## Decentralized index

External Kademlia uses /roots/external/kad/1. Provider keys are namespaced:
- roots/external/browse/1/<shard> (8 initial shards, stable hash of public Peer ID)
- roots/external/topic/1/<normalized tag>
- roots/external/node/1/<peer-id> (signed value, not provider lookup)

Provider results are hints, not authenticated claims. Fetch the corresponding node card and verify its public key maps to the expected Peer ID, signature, canonical payload, expiration, and that a searched tag really occurs in the signed card. A signature proves control of a key, not the asserted name, expertise, content accuracy or membership. Reject malformed, expired, oversized or mismatched records. Prefer newer valid revisions; don't equate DHT response count with total population. Browse is bounded sampling, not exhaustive enumeration. Increase shard count and use rotating subsets, independent bootstrap paths, and cached signed catalogs as the network grows. No compulsory central registry, tracker or account service.

The first signed directory card has version, peer_id, display_name, sorted unique public topics, revision (Unix seconds), expires (Unix seconds), and capabilities=["directory"]. It is NOT the final public .axon format. The signature covers domain bytes "roots.external.node.v1" plus NUL plus the exact canonical JSON body generated in schema field order. Envelope contains hex body, public_key (libp2p protobuf) and signature. Maximum 8192 bytes. Owner signature verification is independent of Kademlia record replication. Public topic tags are explicitly supplied; don't derive them from private Tree or prompts.

## Automatic joining (next integration gate)

Discovery -> verify signed public node/member/Axon descriptors -> persist candidate -> check local block, consent, quota and compatibility -> schedule connection -> authenticate a public exchange session -> commit durable binding and inventory checkpoint -> continue missing items on future encounters. Never mark a directory candidate "bound" before the exchange handshake. The public binding carries no private GPU, knowledge, relay or invitation grant. Use a stable member identity and portable signed .axon, with expiring separate route records; map BLE and internet endpoints to the same signed member and Axon only after key authorization. Explicitly public redistributable content only, never automatic import into Internal Mesh. A user may promote an item or request a private invitation later.

Use separate queues for discovery and synchronization; small random and novelty-weighted exploration across categories, retain all eligible public descriptors within cache limits, prioritize rare/missing fragments, cap concurrent dials and total bytes, apply jittered backoff. Start with at most two concurrent internet exchanges, configurable storage and mobile battery/data budgets, and pause on toggle-off or OS conditions. A blocked member is never rejoined unless unblocked. Do not scan all IPs or treat a routing node as a publisher.

## First implementation

The Android Explorer lists already bound nearby Axons and fetches verified public internet directory cards through Rust JNI. CLI can serve a routing node, publish a directory card and browse/search. Search is exact tag or blank browse, up to 48 candidates, bounded query windows; incomplete searches are visibly partial. No seed infrastructure is provisioned by this change. Directory-only cards do not establish automatic Internet bindings; the existing default-on BLE service remains the only automatic public data exchange. Public Internet .axon synchronization, durable candidate cache, full-text discovery, member identity unification and private authorized capability browsing are subsequent gates. Android, Windows and Rust build/interoperability tests still required.
