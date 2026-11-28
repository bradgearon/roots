# Network master records (initial X adapter)

Raw authorized accessible text is still retained as before. Structured members use the existing fragment table: `source=record.<network>.<role>`, `external_id=local:<network>:<fingerprint>`. This key is provisional and not an X post ID. A local identity derived from available author/handle and body is used; ambiguous or missing identifiers can split or merge visually identical records. Explicit card geometry and unique containment determine whether ancillary text may attach; uncertain text remains in the raw lane.

The shared native C ABI `roots_tree_records` queries entire master records, uses the first member sequence as a stable cursor, and expands matching branch/search members to the whole record. No destructive migration is needed. Existing fragments remain queryable via raw mode, not retroactively associated. Compiled GGUF retains fragment vectors and adds role, master and network edges; physical per-network GGUF files are not yet emitted. SQLite remains authoritative. Translation results, verified external post IDs, media bytes, complete occurrence history, and durable work dispatch are future integrations.

The Stream and Tree display record cards; the Tree has an explicit all-raw-fragments mode. No capture scope or consent changes are introduced.

## Self-contained snapshot format v2

GGUF v3 remains the container; `roots.format.version=2` identifies the Roots schema. The arrays `roots.node.keys`, `roots.node.text`, `roots.node.source`, `roots.node.external_id`, `roots.node.language` and `roots.node.observed_ms` (uint64 Unix milliseconds) align by index with each row of `node.embedding` (128 F32 values, unchanged profile). Branch keys, names and matching terms use corresponding `roots.branch.*` arrays. The existing edge arrays remain compatible, now sorted and deduplicated. No original source text is reconstructed from vectors. The file contains all captured text, including private content; treat it as sensitive and export only with authorization. Translation results are included only when actually stored as observations; separate per-network files and selective sharing remain future work.

Compilation snapshots rows, branches and branch membership in a single SQLite read transaction, then builds/deduplicates edges and writes outside the database mutex. Little-endian tensor rows stream in fixed 512-byte buffers; no full tensor allocation is required. Adjacent identical text reuses its lexical vector. Android publishes only a current revision using its existing atomic replacement mechanism.
