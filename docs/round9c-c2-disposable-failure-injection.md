# ROUND 9C-C.2 — Disposable failure-injection framework

This document describes the disposable, server-local framework added for the
ROUND 9C-C.3 crash and concurrency experiments. It is not a production feature
and it has no public API, environment switch, database flag, or `.env` setting.

## Safety boundary

The ordinary application always receives `NoOpCreationExecutionBoundaryHook`.
The only non-no-op implementation is constructed by
`Round9CcPackagedFailureHarness`, launched explicitly through
`PropertiesLauncher`. It first validates a fixture root under `/tmp` whose name
matches `auralink-round9cc.<run-id>` and whose exact regular marker is
`.round9cc-fixture`.

The fixture root, every required child directory, and the marker must be owned
by the launch user and private (`0700` directories, `0600` files). Symlinks,
hard-linked markers, a missing marker, a root outside `/tmp`, or group/world
permissions are rejected. Failpoint events contain only the boundary name.

No ROUND 9C-C script may use `backend/.env`, `backend/auralink.db`, production
MediaAsset storage, provider staging, catalog inputs, or ROUND 8.1 artifacts.
The fixture configuration disables real provider adapters and catalog import,
uses a fresh V1--V4 SQLite database, random loopback port, and fixture-contained
managed/staging roots.

## Failpoints

The dedicated harness can select one scenario failpoint. A selected boundary
creates this mode-`0600` file:

```text
<fixture>/control/<instance>/<FAILPOINT>.reached
```

It waits only for the matching `.release` file, with a bounded timeout. The
wait uses bounded parking rather than a busy loop. `MANAGED_FILE_BEFORE_DB_COMMIT`
and `BEFORE_TERMINAL_CREATION_MUTATION` run inside the existing short result
transaction; a hard kill there demonstrates transaction rollback, not a new
durability protocol.

Supported boundary names are:

- `CLAIM_COMMITTED_BEFORE_SUBMIT`
- `SUBMITTED_BEFORE_WORKER_RELOAD`
- `STEP_RUNNING_BEFORE_SEND_STARTED`
- `SEND_STARTED_COMMITTED`
- `BEFORE_MOCK_ENTRY`
- `MOCK_DURING_EXECUTION`
- `MOCK_RETURNED_BEFORE_VALIDATION`
- `VALIDATED_BEFORE_MANAGED_PERSISTENCE`
- `MANAGED_FILE_BEFORE_DB_COMMIT`
- `RESULT_COMMITTED_BEFORE_ARTIFACT_CLOSE`
- `BETWEEN_SUCCEEDED_STEPS`
- `BEFORE_TERMINAL_CREATION_MUTATION`
- `STARTUP_RECOVERY_GATE_CLOSED`
- `SCHEDULED_RECOVERY_SWEEP`
- `GRACEFUL_SHUTDOWN_DURING_AWAIT`
- `HARD_KILL_WINDOW`

The hook is no-op unless the separate harness provides it. It cannot be enabled
through `application.yml`, an OS environment variable, a JVM system property,
or an API request.

## Durable Mock evidence

The C.2 Mock writes an append-only private journal in `counters/`. Each record
contains only sequence, scenario, role, logical step label, and one of `ENTRY`,
`RETURN`, or `CLOSE`. The `ENTRY` record is forced to the fixture filesystem
before Mock execution begins. Request keys, source text, prompts, URLs, raw
results, paths outside the fixture, and credentials are never recorded.

Recovery has no Mock adapter call path. A recovered `NOT_SENT` Step is the only
case that may later call the Mock through ordinary dispatcher execution.
`SEND_STARTED` is never replayed.

## External Provider exactly-once boundary

Persisted `SEND_STARTED` evidence prevents unsafe automatic replay, but it
cannot prove whether an external Provider received zero requests or exactly one
request. A local process may fail after the Provider receives a request but
before a durable response is persisted. Automatic recovery therefore never
replays `SEND_STARTED` work. Establishing an exactly-once external outcome
requires Provider-specific durable idempotency or reconciliation, which is
outside the completed B.2/C.2 scope.

## Physical managed-file recovery correction

Recovery now resolves the one referenced generated MediaAsset before accepting a
persisted painting result as durable. This uses the existing contained resolver;
it performs no scan. If the managed file is missing, unreadable, outside its
managed root, or has a different size, recovery quarantines the Creation with
`CREATION_RESULT_PERSISTENCE_INCONSISTENT` and does not call a Provider.

## Operator scripts

All scripts live in `src/test/scripts/round9cc/` and require `set -euo pipefail`
and `umask 077`.

- `round9cc-preflight-b2-tests.sh` runs only the three B.2 deferred tests from
  the server-local filesystem. It emits `ROUND9CC_B2_PREFLIGHT_TESTS_OK` only
  after all three pass. This does not replace deferred full B.2 validation.
- `round9cc-build-package.sh` builds the executable JAR offline.
- `round9cc-create-fixture.sh SCENARIO` creates a fresh private root and writes
  the immutable scenario contract.
- `round9cc-start-instance.sh`, `round9cc-await-failpoint.sh`,
  `round9cc-release-failpoint.sh`, and `round9cc-signal-instance.sh` implement
  bounded process control. PID signals require matching start-time and command
  line evidence for the exact fixture harness JVM.
- `round9cc-restart-and-recover.sh` starts a new named instance in the same
  fixture after a prior process has ended.
- `round9cc-audit-fixture.sh` uses a non-signaling runtime-evidence probe: a
  stopped recorded PID and a reused PID continue to read-only audit; an exact
  live fixture JVM, malformed runtime evidence, or matching-start ownership
  mismatch fails closed with a stable safe error. It then uses read-only SQLite
  for integrity, foreign-key, Flyway V1--V4, ledger, generation-log, Painting,
  and fixture `expected-counts.properties` checks.
- `round9cc-cleanup.sh` validates the exact root before stopping only recorded
  fixture JVMs and deleting that root. It never scans or kills arbitrary Java
  processes.
- `round9cc-run-all.sh` requires explicit `--server-local-execute`; C.3 owns
  non-normal scenario seeding, signals, and its final PASS/FAIL evidence.

## C.2 NORMAL_COMPLETION smoke

Only the separately launched `Round9CcPackagedFailureHarness` may execute the
manifest-gated `NORMAL_COMPLETION` path. After normal application startup has
opened the recovery gate, that plain harness coordinator creates one synthetic
owner and one canonical one-step text-to-painting workflow, admits one queued
Creation through `CreationSubmissionService`, and invokes
`CreationQueueDispatcher.dispatchOne()` once. It never calls `CreationWorker`
directly.

The coordinator waits for the one terminal `SUCCEEDED` Creation, one
`SUCCEEDED`/`RESULT_PERSISTED` Step, one finished execution attempt, cleared
claim and lease, unchanged retry version, zero `generation_logs` and official
Paintings, and exact Mock journal counts `ENTRY=1`, `RETURN=1`, `CLOSE=1`.
Only then does it close the Spring context so the recorded supervisor exit is
zero. The coordinator is not a Spring component and is not activated by a
profile, property, environment variable, API, or database value.

Fixture creation alone intentionally creates no database, runtime boundary, or
journal. Audit rejects any fixture missing those records; it also requires at
least one private regular Mock journal before comparing exact event counts.

## C.3 server-local order

Run only from `/root/autodl-tmp/auralink/backend` on a non-FUSE filesystem:

```bash
cd /root/autodl-tmp/auralink/backend
bash src/test/scripts/round9cc/round9cc-preflight-b2-tests.sh
bash src/test/scripts/round9cc/round9cc-build-package.sh
bash src/test/scripts/round9b2-packaged-mock-harness.sh
bash src/test/scripts/round9cc/round9cc-create-fixture.sh NORMAL_COMPLETION
```

For a signal scenario, use the root returned by the creation script, then start
the exact role, await the selected failpoint, and send only `TERM`, `INT`, or
`KILL` with the signal script. The expected direct exit codes are `143`, `130`,
and `137`; a timeout wrapper is `124`, and the harness-only halt simulation is
`86`. Audit after all fixture processes stop, then invoke exact-root cleanup.

Never run C.3 signals, multi-JVM scenarios, or hard-kill experiments from the
SSHFS worktree.

## Deferred boundary

```
ROUND 9C-B.2 IMPLEMENTATION:
COMPLETE

ROUND 9C-B.2 TARGETED PACKAGED RECOVERY HARNESS:
PASS

ROUND 9C-B.2 FULL SERVER-LOCAL VALIDATION:
DEFERRED_BY_OPERATOR

ROUND 9C-B.2 FULL SERVER-LOCAL MAVEN SUITE: DEFERRED_BY_OPERATOR

ROUND 9C-B.2 COMMIT:
NOT CREATED
```

The C.2 framework does not replace final server-local validation of
`ConfigDataImportTest`, `CreationRecoveryCoordinatorTest`,
`CreationRecoveryRepositoryIntegrationTest`, or the deferred full B.2 suite.
C.3 TERM, INT, KILL, Runtime.halt, SQLite-busy, two-JVM recovery-race, and
crash/restart scenarios have not been executed and are not claimed as PASS.
