# ROUND 9C-B.1 — V4 attempt ledger and safe owner retry

ROUND 9C-B.1 adds an append-only execution-attempt ledger and an owner-only retry endpoint. It remains disabled unless normal Creation execution is enabled, and it never invokes a Provider while deciding whether a retry is safe.

`V4__add_creation_retry_attempt_ledger.sql` adds `creations.retry_version`, one initial execution-attempt row for every existing Creation, and immutable per-Step dispatch-attempt evidence. Backfilled V3 information is explicitly labelled `LEGACY_V3_STATE_SNAPSHOT`; V3 history is not reconstructed beyond the state it already retained.

Every new Creation receives attempt number 1. A safe accepted retry from retry version `N` changes the Creation to retry version `N + 1` and creates execution attempt `N + 2`. The database has a partial unique index permitting only one unfinished execution attempt per Creation.

`POST /api/v1/creations/{creationId}/retry` requires authentication, owner access, an `Idempotency-Key`, and a body containing `expectedRetryVersion`. The server stores only the SHA-256 digest of that key. First acceptance returns `202`; an exact duplicate returns the original acknowledgement with `200` and `idempotentReplay=true`.

Only `FAILED` and `PARTIAL_SUCCESS` Creations with a structurally valid, non-dispatched retry boundary are eligible. A boundary with any historical `SEND_STARTED` is rejected with `CREATION_RETRY_DISPATCH_AMBIGUOUS`. This includes timeout and crash ambiguity: acknowledgement is not authorization for a second paid request. Prior `SUCCEEDED + RESULT_PERSISTED` Steps remain linked and reusable; they are not reset or replayed.

The round does not implement stale lease recovery, heartbeats, startup recovery, scheduled recovery, or Provider reconciliation. Music remains `PAINTING_TO_MUSIC_DEFERRED_NOT_VALIDATED`; video remains `RESERVED_FOR_FUTURE_IMPLEMENTATION`.

The packaged Mock harness and all persistence tests must use a temporary V1+V2+V3+V4 SQLite database. Do not migrate or start Spring against `backend/auralink.db`.
