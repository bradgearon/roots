# Roots Internal and External Meshes

Two independent trust and data domains. External Mesh is the shared brain: published knowledge, public Axons, public members and Posts, offline BLE and public Whirlwind routing. Internal Mesh is a private, expandable community: personal devices and invited friends' devices, private knowledge, memory and computation. A friend is not automatically given every capability; the community can expand by explicit invitation and capability-specific acceptance. A public encounter never adds an internal member.

## Identity and invitation

Separate cryptographic transport identities per mesh; no external directory record that exposes an internal membership, endpoint, route or GPU grant. Internal mesh identity is an independently generated community identifier and policy authority; internal device identity, verified transport keys and capability grants are scoped to that mesh. Invites privately convey issuer, mesh id, device/endpoint identity, intended recipient, granted capabilities, expiry and revocation/version; possession of a public Axon or a shared public item cannot create an invite. Capabilities include knowledge.read, knowledge.contribute, inference.use and relay, independently consented to and independently revocable. Friend machines can contribute selected capacity without revealing unrelated private information. No automatic transitive trust or cross-mesh routing.

First remote inference profile uses existing desktop pinned HTTPS and independent bearer key, plus explicit transport endpoint allowlisting. It is a prototype of an internal compute grant, not a complete signed community membership or delegated authorization system. Later use per-device grants, expiry, quotas, revocation and audit; do not share one desktop bearer across an entire community in production.

## Public-to-private promotion

Public items live in ExternalKnowledgeStore, not in the private Tree. Public redistributable explicit records remain eligible for external forwarding. User can bring a selected publication into the Tree, preserving its content ID, source link and existing record format. Promotion does not carry a route, credential, peer membership, instructions or compute permissions. Linking a public publisher for future public updates is distinct from accepting a friend into the private community. Imported material is untrusted evidence; no automatic executable/model activation. Existing manually authorized LAN graph sharing remains separate from the public encounter protocol.

## Transport

Same libraries, separate endpoints, protocol identifiers, discovery and credentials. Iroh primary (IPv6/IPv4 direct, QUIC traversal and encrypted relay); libp2p independent fallback with separate private Peer IDs and explicitly provisioned relay routes; external Kademlia uses its own identity and protocol namespace. No internal endpoint advertisements in external DHT or BLE. An authorized internal rendezvous service may advertise opaque encrypted routes to members only. Host raw llama.cpp remains on localhost and the existing inference gateway retains authentication, model identity, queue and resource limits.

Acceptance: external stranger cannot connect to internal inference, even if an item is promoted; blocked or revoked member denied; separate keys and route stores; private captures cannot be retrieved through External Mesh; friends' opt-in compute capabilities can be individually authorized; remote phone reaches desktop without inbound IPv6 or IPv4 firewall changes where relays are reachable; relay failure falls back only before a request is submitted, avoiding duplicate GPU execution.
