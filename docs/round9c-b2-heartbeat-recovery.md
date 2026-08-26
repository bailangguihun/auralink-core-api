# ROUND 9C-B.2 — Creation heartbeat and automatic recovery

Creation execution uses a single injectable `Clock`; the production bean is
`Clock.systemUTC()`. All claim, heartbeat, lease, recovery-fence, and terminal
recovery timestamps derive from that clock and are stored as UTC
`LocalDateTime` values for the existing SQLite columns.

When Creations are enabled, startup holds `CreationRecoveryGate` closed until a
bounded recovery sweep observes no stale `RUNNING` claims. A stale candidate is
only a `RUNNING` Creation with a non-null claim token and lease at or before
`now - recoveryGrace`, ordered by old lease then ID and limited to the configured
batch size. Each candidate is first conditionally fenced by its observed token
and lease timestamp; all later recovery writes require the replacement recovery
token.

Recovery never calls a provider. It requeues only all-pending work, retained
successful-prefix work, and a fenced `RUNNING + NOT_SENT` boundary. The latter
retains and marks its V4 dispatch attempt `RECOVERY_REQUEUED_NOT_SENT`; when it
is eventually restarted, the same NOT_SENT row is reused and the safe recovery
evidence is retained in its bounded resolution code. `SEND_STARTED` is terminal
with `PROVIDER_DISPATCH_AMBIGUOUS`; it is never replayed. Persisted successful
steps can finalize the Creation with their durable final image or canonical
poem. Invalid layouts and `RUNNING + RESULT_PERSISTED` are quarantined with a
safe inconsistency code and require operator review.

## External Provider exactly-once boundary

Persisted `SEND_STARTED` evidence prevents unsafe automatic replay, but it
cannot prove whether an external Provider received zero requests or exactly one
request. A local process may fail after the Provider receives a request but
before a durable response is persisted. Automatic recovery therefore never
replays `SEND_STARTED` work. Establishing an exactly-once external outcome
requires Provider-specific durable idempotency or reconciliation, which is
outside the completed B.2/C.2 scope.

The dispatcher checks the local gate before every claim. The worker checks the
gate and its heartbeat ownership before every provider boundary; a lost claim
can never persist a stale result. A dedicated single-thread heartbeat scheduler
refreshes the matching `RUNNING`/token lease every 30 seconds, and a separate
single-thread scheduler runs non-overlapping bounded recovery sweeps. Shutdown
closes the gate, stops claims and recovery scheduling, waits bounded active
workers while heartbeats remain live, then stops the heartbeat timer.

Defaults (all non-secret):

- lease: 15 minutes
- heartbeat: 30 seconds
- stale grace: 90 seconds
- periodic recovery: 60 seconds
- recovery batch: 50, maximum 20 startup batches
- recovery fence lease: 5 minutes

`AURALINK_CREATIONS_ENABLED=false` remains the production default. The public
capability projection reports `CREATION_RECOVERY_NOT_READY` while enabled
Creations have not drained startup recovery. Creation DTOs expose only
`NONE`, `PROVIDER_DISPATCH_AMBIGUOUS`, or `OPERATOR_REVIEW_REQUIRED` recovery
state; no token, lease, request key, provider, source, or storage information
is exposed.

No migration is part of this round. V4 provides the active execution attempt,
dispatch state, request-key boundary, durable result digest, and resolution
fields required for conservative recovery.

## Server-local validation

Run only from `/root/autodl-tmp/auralink`, never from the SSHFS worktree:

```bash
cd /root/autodl-tmp/auralink/backend
sha256sum auralink.db
sqlite3 auralink.db 'PRAGMA integrity_check; PRAGMA foreign_key_check;'
mvn -o test
mvn -o clean package
bash src/test/scripts/round9b2-packaged-mock-harness.sh
sha256sum auralink.db
sqlite3 auralink.db 'PRAGMA integrity_check; PRAGMA foreign_key_check;'
git diff --check
git status --short
```

The operator must separately compare the protected production counts and
Flyway history before and after validation. Do not enable Creations in
`backend/.env`, start Spring against `backend/auralink.db`, or apply V3/V4 to
that database.

## Operator validation boundary

ROUND 9C-B.2 FULL SERVER-LOCAL MAVEN SUITE: DEFERRED_BY_OPERATOR

This is an explicit operator validation boundary, not a failure. The accepted
focused tests, B.2 preflight, packaged recovery Harness, C.2 focused tests,
and `NORMAL_COMPLETION` smoke do not replace that deferred full server-local
Maven suite. C.3 signal, crash, halt, SQLite-busy, two-JVM recovery-race, and
crash/restart scenarios have not been executed and are not claimed as PASS.
