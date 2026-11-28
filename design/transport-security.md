# Roots transport security and knowledge representation

Status: owner-confirmed architecture, 2026-11-27. This clarification supersedes the earlier broad instruction to remove all encryption.

## Normative rule

Use the normal secure connection provided by the transport, such as TLS on IP connections and the platform's standard Wi-Fi/Bluetooth security. Preserve existing connection establishment, peer authentication, Axon bindings, authorization, and transport security. Do not replace a secure channel with plaintext to satisfy this rule.

Roots knowledge records, serialized messages, compressed archives, manifests, and application-managed knowledge storage must not have an additional application-level encryption layer. Compress the knowledge as needed, then send it directly through the existing transport. Do not encrypt an archive or wrap an already-secured connection in a second application cipher.

Content hashes, integrity validation, signatures, deduplication, scoped permissions, and the operating system's normal credential/key protection are distinct from redundant payload encryption. Preserve them; do not expose credentials or private data by removing normal platform security.

This is a design requirement for all Roots subsystems. It does not authorize widening sharing scope, changing the established connection or Axon exchange, or undertaking a broad security migration without a separately requested change.

## Implementation update

Public IP Axon exchange on TCP 8235 and the BLE-to-Wi-Fi bulk handoff now use standard TLS 1.2/1.3. The existing persistent Axon signing key proves possession and binds the exact TLS server certificate to the discovered peer identity before any inventory or archive is exchanged. No extra AES or HMAC wrapper runs above TLS. Messages and compressed archives are ordinary length-prefixed byte sequences; receiver memory budgets remain, without a 64 KiB archive limit.

The existing BLE discovery and authenticated BLE connection remain intact, including the existing REP/1 connection protection for a Bluetooth-only exchange. No second cipher is applied to stored or compressed knowledge. Private LAN invitations, TLS and GPU grants remain unchanged. BLE discovery is not mandatory for manual invitations or local-IP discovery. Older IP public peers need an update to use the TLS route; there is no insecure IP downgrade. The Bluetooth-only path still uses the existing secure REP/1 connection rather than TLS. Build and physical interoperability verification remain pending.
