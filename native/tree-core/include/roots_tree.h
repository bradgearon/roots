#ifndef ROOTS_TREE_H
#define ROOTS_TREE_H
#include <stddef.h>
#include <stdint.h>
#if defined(_WIN32)
#  ifdef ROOTS_TREE_BUILD
#    define ROOTS_TREE_API __declspec(dllexport)
#  else
#    define ROOTS_TREE_API __declspec(dllimport)
#  endif
#else
#  define ROOTS_TREE_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif

/* UTF-8, NUL-terminated strings. Callbacks borrow data only during invocation.
 * One handle serializes database operations; queries invoke callbacks outside
 * its mutex. Close only after all operations complete. No exceptions cross ABI.
 * One authorized local database per Tree; the embedding application enforces
 * capture consent and scope. This is NOT a multi-tenant security boundary. */
typedef struct roots_tree roots_tree;
typedef enum roots_tree_result {
    ROOTS_TREE_OK=0, ROOTS_TREE_INVALID=1, ROOTS_TREE_STORAGE=2,
    ROOTS_TREE_LIMIT=3, ROOTS_TREE_STALE=4
} roots_tree_result;

typedef struct roots_observation {
    const char *source;       /* e.g. x.android.home.fragment */
    const char *external_id;  /* empty if not known; never manufacture a tweet ID */
    const char *body;
    const char *language;     /* optional; empty if unknown */
    int64_t observed_ms;
} roots_observation;
typedef struct roots_fragment {
    int64_t sequence;
    const char *source, *external_id, *body, *language;
    int64_t observed_ms;
} roots_fragment;
typedef int (*roots_fragment_callback)(void *user, const roots_fragment *fragment);
typedef int (*roots_relation_callback)(void *user, const char *subject,
                                       const char *predicate, const char *object);
typedef int (*roots_agent_callback)(void *user, int64_t id, const char *name, const char *term);

ROOTS_TREE_API uint32_t roots_tree_abi_version(void);
/* Thread-local diagnostic, borrowed until the next guarded call on this thread. */
ROOTS_TREE_API const char *roots_tree_last_error(void);
ROOTS_TREE_API roots_tree_result roots_tree_open(const char *db_path, roots_tree **out);
ROOTS_TREE_API void roots_tree_close(roots_tree *tree);
/* Atomic bounded batch. Exact duplicate fragments do not add knowledge. */
ROOTS_TREE_API roots_tree_result roots_tree_observe(roots_tree *tree,
    const roots_observation *items, size_t count, size_t *inserted);
/* before=0 starts newest-first; otherwise exclusive sequence cursor. Agent 0=all.
 * ASCII case-insensitive literal substring search; limit 1..100. Callback
 * returning nonzero stops iteration. No network or model is invoked. */
ROOTS_TREE_API roots_tree_result roots_tree_query(roots_tree *tree,
    const char *term, int64_t agent_id, int64_t before, uint32_t limit,
    roots_fragment_callback callback, void *user);
/* Structured observations use source record.<network>.<role> and an
 * explicitly local master key in external_id (not a verified remote ID).
 * Page by first fragment sequence; return every member of matching masters.
 * A member match selects the whole master, including branch searches. */
typedef int (*roots_record_member_callback)(void *user,int64_t master_sequence,const roots_fragment *fragment);
ROOTS_TREE_API roots_tree_result roots_tree_records(roots_tree *tree,const char *term,
    int64_t agent_id,int64_t before,uint32_t limit,roots_record_member_callback callback,void *user);
/* Capture-time ordering with a stable tie breaker. Cursor (0,0) starts newest.
 * Timestamp is the earliest observed_ms in a grouped record; before_seq is
 * the exclusive master sequence at that timestamp. No changes to old cursors. */
ROOTS_TREE_API roots_tree_result roots_tree_query_time(roots_tree *tree,const char *term,
    int64_t agent_id,int64_t before_ms,int64_t before_seq,uint32_t limit,
    roots_fragment_callback callback,void *user);
ROOTS_TREE_API roots_tree_result roots_tree_records_time(roots_tree *tree,const char *term,
    int64_t agent_id,int64_t before_ms,int64_t before_seq,uint32_t limit,
    roots_record_member_callback callback,void *user);
/* The same normalized 128 byte-trigram lexical features used by GGUF compile.
 * These are not learned semantic relation weights. Caller owns 128 floats. */
ROOTS_TREE_API roots_tree_result roots_tree_fragment_weights(roots_tree *tree,
    int64_t sequence,float *weights,size_t count);
ROOTS_TREE_API roots_tree_result roots_tree_count(roots_tree *tree, int64_t *count);
ROOTS_TREE_API roots_tree_result roots_tree_agent_create(roots_tree *tree,
    const char *name, const char *term, int64_t *id);
ROOTS_TREE_API roots_tree_result roots_tree_agent_list(roots_tree *tree,
    roots_agent_callback callback, void *user);
ROOTS_TREE_API roots_tree_result roots_tree_agent_remove(roots_tree *tree, int64_t id);
/* Composable canonical predicate keys: ["is","a"] => "is.a".
 * 1..8 ASCII components; caller-owned output buffer. */
ROOTS_TREE_API roots_tree_result roots_tree_predicate(const char *const *parts,
    size_t count, char *output, size_t capacity);
ROOTS_TREE_API roots_tree_result roots_tree_relations(roots_tree *tree,
    int64_t sequence, roots_relation_callback callback, void *user);
/* Writes a derived GGUF v3 snapshot to a NEW caller-owned output path. The
 * caller publishes the revision only if still current and manages retention.
 * This lexical baseline is not a language model or learned embedding. */
ROOTS_TREE_API roots_tree_result roots_tree_compile(roots_tree *tree,
    const char *new_output_path, int64_t *revision);
/* No file is created when no local-language record is available. */
ROOTS_TREE_API roots_tree_result roots_tree_compile_localized(roots_tree *tree,
    const char *new_output_path,const char *language,int64_t *revision);
ROOTS_TREE_API roots_tree_result roots_tree_revision(roots_tree *tree, int64_t *revision);
/* Logical deletion; caller must stop intake and remove any exported snapshots.
 * For physical disposal, close and remove the private DB and sidecar files. */
ROOTS_TREE_API roots_tree_result roots_tree_clear(roots_tree *tree);
/* Durable work references a consented fragment. One job per fragment,
 * operation and profile (e.g. target language/model revision). Unix ms from
 * the database owner, never an untrusted peer's clock. 0 deadline = none.
 * A monotonically increasing lease token fences late worker completions.
 * Callbacks borrow fields only during invocation, outside the database mutex.
 * No row on successful claim/read is represented by no callback invocation. */
typedef enum roots_work_state { ROOTS_WORK_PENDING=0, ROOTS_WORK_RUNNING=1,
    ROOTS_WORK_COMPLETED=2, ROOTS_WORK_FAILED=3, ROOTS_WORK_CANCELLED=4 } roots_work_state;
typedef struct roots_work {
    int64_t id, fragment, priority, deadline_ms, lease_until_ms, token, attempts;
    int64_t ready_ms;
    int32_t state;
    const char *operation, *profile, *worker, *output;
} roots_work;
typedef int (*roots_work_callback)(void *user,const roots_work *work);
ROOTS_TREE_API roots_tree_result roots_work_request(roots_tree*,int64_t fragment,
    const char *operation,const char *profile,int64_t priority,int64_t deadline_ms,int64_t *id);
ROOTS_TREE_API roots_tree_result roots_work_read(roots_tree*,int64_t id,roots_work_callback,void*);
ROOTS_TREE_API roots_tree_result roots_work_claim(roots_tree*,const char *operation,
    const char *worker,int64_t now_ms,int64_t lease_ms,roots_work_callback,void*);
ROOTS_TREE_API roots_tree_result roots_work_finish(roots_tree*,int64_t id,int64_t token,
    const char *worker,int64_t now_ms,int32_t state,const char *output,int64_t retry_at_ms);
ROOTS_TREE_API roots_tree_result roots_work_renew(roots_tree*,int64_t id,int64_t token,
    const char *worker,int64_t now_ms,int64_t lease_ms);
ROOTS_TREE_API roots_tree_result roots_work_cancel(roots_tree*,int64_t id);
#ifdef __cplusplus
}
#endif
#endif
