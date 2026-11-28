# Knowledge Wave: coordination derived from GGUF

## Source and responsibility

The Android Tree publishes its localized, private GGUF as an atomic revision. The
coordination observer runs after publication on the existing compiler worker,
reads the real node.embedding F32 tensor and aligned node/edge arrays, and
derives a separate sparse graph of weighted master-record relationships. It skips
all node.text strings and never sends local vectors, branches or weights to peers.
This is **knowledge-graph weighting**, not fine-tuning or editing Qwen model
parameters. The underlying vectors are the existing 128-dimensional hashed
UTF-8 trigram baseline; they are not learned semantic embeddings.

Body/translation/confirmed-language nodes and local understanding form a master
vector, with understanding at 0.7 relative weight. Explicit branch membership
and wanted-state edges are retained without copying private branch labels or
foreign original text into the GGUF. Raw observations and author nodes do not
participate in coordination.

## Derive the graph from observation

For each master, measure cosine similarity to candidate masters. Up to 1,024
masters use exact comparisons; larger graphs use bounded prominent-feature
buckets and branch candidates. Baseline is the candidate mean; minimum similarity
floor is max(0.48, mean+0.16). A lexical edge is a scaled excess above that
baseline; a shared branch adds structural evidence. Keep the eight strongest
outgoing edges; normalize their strengths during propagation. These are
proposed associations, not factual or causal relationships.

The local field contains affinity (branch/want evidence), novelty (one minus
nearest observed similarity), convergence (strength and cross-branch links plus
understanding), and temporary activation. The home rank adds a bounded derived
priority to its existing observed metrics. Each card reports its wave,
convergence, novelty, and number of candidate links.

## Watch revisions, not timers

When a new GGUF revision appears, compare master signatures and neighbor
sets against the previous revision. Only new/changed masters and newly found
links introduce pulses. Initial history is gently bootstrapped. Unchanged
revisions cannot multiply activation. At each step, retain 36% of the pulse at
its current node and distribute at most 64% across normalized outgoing links.
An isolated node retains its own pulse; the remaining pulse after four
transmissions is discarded. Accumulated activation is clamped to [0,1] and
decays lazily with a 20-minute half-life, using elapsed realtime rather than
wall-clock timestamps. No background LLM calls or network polling.

The observer runs automatically when snapshot publication changes, including
local capture, received knowledge, understanding, wants and branch changes.
Home shows the latest computed field on resume/refresh and signals newer waves
without interrupting reading. Clearing or switching language invalidates the
field; no private wave state is persisted outside the existing GGUF/SQLite.
Peer rarity/demand remains an independently observed ranking signal; wave
propagation is not yet exchanged or used as a distributed scheduler.

## Validation

Existing native/JNI checks now feed an actual generated localized GGUF into the
reader and coordinator. Pure host Java checks cover related/isolated nodes,
decay, revision deduplication, new understanding/wanted stimulation, propagation,
ordering, bounds and language/clear invalidation. Android device testing is
still necessary. No knowledge/model performance claims are inferred from
these structural tests.

The numeric coefficients, degree cap, similarity threshold and half-life are
initial experimental defaults. They are not learned from user judgments yet.
Future work: replace lexical vectors with better embeddings, learn weights
from explicit feedback, and introduce privacy-preserving peer coordination
only after validating local convergence and utility.
