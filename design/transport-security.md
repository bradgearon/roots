# Roots transport security and knowledge representation

Status: owner-confirmed architecture, 2026-11-27. This clarification supersedes the earlier broad instruction to remove all encryption.

## Normative rule

Use the normal secure connection provided by the transport, such as TLS on IP connections and the platform's standard Wi-Fi/Bluetooth security. Preserve existing connection establishment, peer authentication, Axon bindings, authorization, and transport security. Do not replace a secure channel with plaintext to satisfy this rule.

Roots knowledge records, serialized messages, compressed archives, manifests, and application-managed knowledge storage must not have an additional application-level encryption layer. Compress the knowledge as needed, then send it directly through the existing transport. Do not encrypt an archive or wrap an already-secured connection in a second application cipher.

Content hashes, integrity validation, signatures, deduplication, scoped permissions, and the operating system's normal credential/key protection are distinct from redundant payload encryption. Preserve them; do not expose credentials or private data by removing normal platform security.

This is a design requirement for all Roots subsystems. It does not authorize widening sharing scope, changing the established connection or Axon exchange, or undertaking a broad security migration without a separately requested change.

## Current implementation caveat

The existing private LAN Mesh uses TLS. The newer public REP archive path is a separate protocol: it authenticates peers and negotiates compressed plaintext archives with integrity tags; this does not itself establish a standard TLS connection. Earlier REP variants retain their existing channel encryption. Do not describe REP archives as TLS-protected without verifying the actual transport. Future transport changes should use one normal secure channel rather than redundant application encryption. This clarification records the requirement without modifying runtime behavior.
