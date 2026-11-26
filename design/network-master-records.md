# Network master records (initial X adapter)

Raw authorized accessible text is still retained as before. Structured members use the existing fragment table: `source=record.<network>.<role>`, `external_id=local:<network>:<fingerprint>`. This key is provisional and not an X post ID. A local identity derived from available author/handle and body is used; ambiguous or missing identifiers can split or merge visually identical records. Explicit card geometry and unique containment determine whether ancillary text may attach; uncertain text remains in the raw lane.

The shared native C ABI `roots_tree_records` queries entire master records, uses the first member sequence as a stable cursor, and expands matching branch/search members to the whole record. No destructive migration is needed. Existing fragments remain queryable via raw mode, not retroactively associated. Compiled GGUF retains fragment vectors and adds role, master and network edges; physical per-network GGUF files are not yet emitted. SQLite remains authoritative. Translation results, verified external post IDs, media bytes, complete occurrence history, and durable work dispatch are future integrations.

The Stream and Tree display record cards; the Tree has an explicit all-raw-fragments mode. No capture scope or consent changes are introduced.
