# ROUND 9C-C.3 Batch 1 deterministic execution support

This document describes support for the four server-local Batch 1 scenarios:
`TERM_BEFORE_CLAIM`, `TERM_AFTER_CLAIM`, `TERM_DURING_NOT_SENT`, and
`INT_AFTER_SEND_STARTED`. It is Harness-only support, not a production feature.
No C.3 scenario has been executed or passed merely by adding this support.

## Immutable three-phase fixture protocol

Each Batch 1 fixture is created once and uses three exact Harness phases from
its immutable scenario manifest:

1. `SEED` starts a private Harness with no failpoint after startup recovery has
   opened the gate. The explicit `Round9CcBatch1SeedCoordinator` creates one
   synthetic owner, one one-step workflow, one `QUEUED` Creation, one `PENDING`
   `NOT_SENT` Step, and one active execution attempt through
   `CreationSubmissionService`. It calls no Provider, writes no
   `generation_logs` row, and creates no official Painting.
2. `INITIAL` starts a new Harness on the same validated fixture. Its failpoint
   is derived only from the manifest. `TERM_BEFORE_CLAIM` pauses during startup
   recovery; the other three scenarios enter through the normal dispatcher and,
   where required, Worker path. The Worker is never called directly.
3. `RECOVERY` starts a new exact instance label on the same fixture after the
   bounded stale-lease wait. It has no failpoint, runs only the ordinary
   lifecycle-owned recovery path, verifies the manifest state, records private
   recovery evidence, and closes with exit zero.

For every phase, the exact dynamic-port, role, and environment-boundary record
is written by the Harness's private web-server-initialized listener before a
startup-recovery failpoint can pause the process. It is not a component and has
no normal-application activation path.

## TERM_AFTER_CLAIM recovery-gate correction

`TERM_BEFORE_CLAIM` and `TERM_AFTER_CLAIM` have passed their accepted
server-local executions. The earlier retained `TERM_AFTER_CLAIM` diagnostic
fixture correctly reached
`CLAIM_COMMITTED_BEFORE_SUBMIT`, received `TERM`, and restored its one Creation
to the expected `QUEUED` / `PENDING` / `NOT_SENT` projection with an active
attempt, clear claim and lease, zero `generation_logs`, zero Paintings, and no
Mock or real Provider call. Its RECOVERY process exited with
`ROUND9CC_ERROR:BATCH1_STATE_INVALID` before recovery evidence was written. It
remains diagnostic-only; its historical failure does not invalidate the later
accepted TERM_AFTER_CLAIM PASS.

The failure was Harness-only: its Batch 1 command-line override had set
`auralink.creations.startup-max-batches=1`. The normal coordinator recovered
the one deterministic stale candidate in that permitted batch, but did not have
a second batch in which to observe an empty candidate set. The coordinator
therefore correctly kept the recovery gate closed. A restored database row
projection is not itself proof that the lifecycle recovery gate may open.

The Harness-only override is now bounded at `startup-max-batches=2`. The first
batch can recover the one deterministic candidate; the second must observe no
remaining candidates before the normal coordinator opens its normal gate. If
that second permitted batch is still non-empty, recovery remains fail-closed.
This does not change the production default or manually open the gate.

The retained fixture is not repaired, restarted, reused, or audited as PASS.
No Provider call occurred.

## TERM_DURING_NOT_SENT identity-publication repair outcome

`TERM_BEFORE_CLAIM`, `TERM_AFTER_CLAIM`, and `TERM_DURING_NOT_SENT` are formal
PASS results. The accepted `TERM_DURING_NOT_SENT` execution reached
`STEP_RUNNING_BEFORE_SEND_STARTED`, delivered a real exact-PID `SIGTERM`, and
recorded `workerA` exit `143`. Recovery completed with a `QUEUED` Creation, a
`PENDING` Step, `NOT_SENT` dispatch state, resolution
`RECOVERY_REQUEUED_NOT_SENT`, an active execution attempt, and clear claim and
lease. Recovery Provider calls remained `ZERO`; no real Provider call occurred.

That pass followed the bounded identity-publication repairs. PID and start
values are captured and validated before individually atomic, same-directory,
private publication. `EVIDENCE_PENDING` distinguishes an incomplete valid pair
from malformed visible evidence without weakening `EVIDENCE_INVALID`, PID
reuse, exact ownership, listener, INITIAL, stopped-process, or one-shot
completion checks.

## INT_AFTER_SEND_STARTED inherited SIGINT disposition repair

The first `INT_AFTER_SEND_STARTED` execution reached
`SEND_STARTED_COMMITTED` while `workerA` remained exact-owned and `ALIVE`. The
recorded PID and `/proc` start time matched. An exact-PID `SIGINT` then timed
out with the JVM still alive. Live process evidence showed Java `SigIgn=0x2`:
Java ignored `SIGINT`, did not ignore `SIGTERM`, did not block `SIGINT`, and had
no caught `SIGINT` handler. The asynchronous monitor had `SigIgn=0x6`, meaning
it ignored `SIGINT` and `SIGQUIT`.

An exact-PID diagnostic `SIGTERM` subsequently produced the expected worker
exit `143`. The Mock Provider journal remained empty, so Provider calls stayed
`ZERO`; no real Provider call occurred. The diagnostic fixture was precisely
cleaned, and the production database, packaged JAR, and Git inventory remained
unchanged.

This proves a launch-side signal-disposition defect, not a signaling-target or
state-machine defect: the asynchronous monitor path caused Java to inherit
`SIGINT` as `SIG_IGN`. The Harness launch now passes through GNU
`env --default-signal=INT --` immediately before Java. GNU `env` resets the
inherited disposition before it execs the JVM, while preserving the background
child PID, `/proc` start-time identity, monitor wait status, and private exit
publication. A shell-only `trap - INT` is not used because a shell entered with
an ignored signal cannot reliably reset that inherited disposition. No timeout
increase, signal substitution, process-group signaling, or broader process
matching is introduced.

At that historical pre-repair and pre-rerun checkpoint, formal status was:

```text
TERM_BEFORE_CLAIM=PASS
TERM_AFTER_CLAIM=PASS
TERM_DURING_NOT_SENT=PASS
INT_AFTER_SEND_STARTED=NOT_PASS
FORMALLY_PASSED_C3_BATCH1_SCENARIOS=3
ROUND9CC3_BATCH1_COMPLETE=NO
```

At that checkpoint, the packaged JAR was stale after the source edit, and
`INT_AFTER_SEND_STARTED` was not eligible for rerun until focused tests,
broader C.3 regressions, the sanitized B.2 preflight, a fresh package build,
and the B.2 packaged Harness regression had all passed.

ROUND 9C-B.2 FULL SERVER-LOCAL MAVEN SUITE:
DEFERRED_BY_OPERATOR

## Phase-aware supervisor readiness

The private operator supervisor has one finite 60-second cold-start deadline;
it does not use separate fragile PID and port windows. `INITIAL` reports ready
only while the exact PID/start-time-owned Harness is still live, its recorded
loopback port has a listener, and phase, role, port, and boundary evidence all
match the manifest.

`SEED` and one-shot `RECOVERY` remain under the same deadline until their
expected zero exit and phase-specific completion evidence are present. A fast
one-shot process is therefore accepted after it has stopped only when its PID
and start-time evidence, port, boundary, phase, role, completion evidence, no
exact-owned process, and no exact-port listener all validate. Missing or
malformed evidence fails closed with one stable `ROUND9CC_ERROR:<code>` value.
For a stopped `SEED`, common PID/start, phase, role, port, boundary, exit, and
no-listener evidence is validated before the private `seed` completion marker;
an otherwise valid fixture missing only that marker reports
`ROUND9CC_ERROR:SEED_COMPLETION_EVIDENCE_INVALID`.

For one-shot phases, a non-listening port is a bounded polling state rather
than an immediate error: the exact process may still be bringing Tomcat into
`LISTEN`, or may stop successfully before the next listener inspection. The
supervisor immediately switches to stopped-completion validation when that
transition is observed. `LISTENER_NOT_READY` is emitted only if the exact-owned
process remains live through the deadline and the exact recorded port never
became ready. `INITIAL` remains stricter: it reports ready only while live and
listening, and a stopped instance reports `INITIAL_LIVE_REQUIRED`.

## Process-ownership probe boundary

The private `/proc` probe accepts a live process only when its recorded PID and
start time still match, its command line contains the exact
`-Dloader.main=com.auralink.ops.round9cc.Round9CcPackagedFailureHarness`
argument, and it contains the exact `--fixture-root=<validated-root>` argument.
The command-line read is held in `cmdline`; the fixture-root comparison uses
that same variable. A different loader main or fixture root is rejected as
`PID_OWNERSHIP_REJECTED`; a matching PID with a different start time is
`PID_REUSE_REJECTED`. This is a strict identity boundary and does not relax for
short-lived one-shot phases. Neither the start script nor the probe sends a
signal.

The probe takes a stable first `/proc` snapshot of existence/readability,
process state, and start time. A matching-start `Z`, `X`, or `x` state, a
disappeared process, or a failed final liveness probe is `STOPPED`; a changed
start time is `PID_REUSED`. If the first command-line read does not prove the
exact loader and fixture-root identity, the probe takes a final snapshot before
returning `OWNERSHIP_REJECTED`. That final check prevents a short-lived SEED or
one-shot RECOVERY process which exits during the cmdline read from being
misclassified as a live wrong owner. A still-live, same-start process with a
different loader or fixture root remains strictly rejected.

The snapshot helper emits exactly one `KIND|STATE|START_TIME` line. `LIVE`
contains the parsed `/proc/<pid>/stat` state and field-22 start time;
`STOPPED` and `UNREADABLE` use empty state/start fields. The probe captures and
validates that one line locally before each classification, so snapshot state
cannot be lost through a command-substitution side effect. The stat read uses a
direct Bash file read; it does not append a fallback command inside the capture,
which would discard a healthy stat payload.

The cmdline reader groups its private `/proc/<pid>/cmdline` redirection before
suppressing a read failure. A process that exits between the first snapshot and
cmdline open therefore yields only the final `STOPPED` classification, never a
shell diagnostic or a `/proc` path in operator output.

The normal application, normal tests, and the B.2 packaged Harness never
register this coordinator. It has no component annotation, profile, API,
environment, JVM-property, database, or `.env` activation path.

## Manifest authority and audit boundary

The operator scripts pass only scenario and phase. Arbitrary `--failpoint`
input is rejected before Spring startup with
`ROUND9CC_ERROR:MANIFEST_LAUNCH_MISMATCH`. The immutable manifest supplies the
effective phase role, failpoint, and expected exit code.

For every Batch 1 scenario, `recoveryProviderCalls` uses the canonical
call-count value `ZERO`. In particular, `TERM_BEFORE_CLAIM` records
`RECOVERY_PROVIDER_CALLS=ZERO` in its recovery evidence. `NONE` remains valid
only for fields whose vocabulary explicitly represents absence, such as a
failpoint or safe code; the audit does not normalize `NONE` to `ZERO` for a
recovery Provider-call count.

For Batch 1 the stopped-fixture audit requires all three phases, exact phase
roles and exits, no listener on each recorded dynamic port, no live exact-owned
Harness, one Creation, one Step, one execution attempt, expected claim/lease,
dispatch, safe code, Mock counts, private recovery evidence, and empty managed
and staging roots. Every mismatch emits one `ROUND9CC_ERROR:<code>` value.

`TERM` must produce `143`; `INT` must produce `130`. A signal/manifest mismatch
or a recorded process-exit mismatch fails closed and retains the fixture.

## Server-local execution only

Run only from `/root/autodl-tmp/auralink/backend`, never through SSHFS. Use a
fresh fixture for one named scenario at a time. The intended order is:

```bash
fixture="$(bash src/test/scripts/round9cc/round9cc-create-fixture.sh TERM_AFTER_CLAIM \
  | sed -n 's/^ROUND9CC_FIXTURE_READY root=\([^ ]*\) scenario=.*$/\1/p')"
test -n "$fixture"
bash src/test/scripts/round9cc/round9cc-start-instance.sh "$fixture" seedA TERM_AFTER_CLAIM SEED
bash src/test/scripts/round9cc/round9cc-start-instance.sh "$fixture" workerA TERM_AFTER_CLAIM INITIAL
bash src/test/scripts/round9cc/round9cc-await-failpoint.sh "$fixture" workerA CLAIM_COMMITTED_BEFORE_SUBMIT 30
bash src/test/scripts/round9cc/round9cc-signal-instance.sh "$fixture" workerA TERM
bash src/test/scripts/round9cc/round9cc-restart-and-recover.sh "$fixture" recoveryA TERM_AFTER_CLAIM 4
bash src/test/scripts/round9cc/round9cc-audit-fixture.sh "$fixture"
bash src/test/scripts/round9cc/round9cc-cleanup.sh "$fixture"
```

Do not execute this sequence until focused server-local validation has passed.
On any failure retain the exact private fixture and stop. The fixture uses only
its own V1--V4 SQLite database, managed root, staging root, logs, journals, and
runtime evidence. It never uses `backend/.env`, `backend/auralink.db`, a real
Provider, production staging, production media, or catalog data.

Persisted `SEND_STARTED` evidence prevents unsafe automatic replay. It cannot
prove whether an external Provider received zero or exactly one request;
automatic recovery therefore never replays `SEND_STARTED`. Exactly-once
external outcomes require Provider-specific durable idempotency or
reconciliation, outside B.2/C.2/C.3 Batch 1 scope.

## Final accepted Batch 1 closure

The live exact-PID diagnosis confirmed `SIGINT_IGNORED_CONFIRMED`: the
asynchronous Bash monitor launch had caused the JVM to inherit `SIGINT` as
`SIG_IGN`, even though the recorded Java PID and `/proc` start time matched.
The launcher repair places `env --default-signal=INT -u AURALINK_ENV_FILE --`
at the Java exec boundary, resetting the inherited ignored disposition before
JVM startup without changing exact PID/start identity, monitor wait status,
atomic PID/start publication, or exit-file publication.

All accepted validation gates then passed: 63 focused tests and 83 broader
tests completed with zero failures, errors, or skips; the B.2 preflight,
package build, and B.2 packaged Harness also passed. These focused and broader
C.3 results do not replace the independently deferred full B.2 Maven suite.

The fresh formal rerun reached `SEND_STARTED_COMMITTED` with Java `SIGINT`
neither ignored nor blocked. A real exact-PID `SIGINT` produced worker exit
`130`; Recovery exited `0`, opened the recovery gate, made zero Provider calls,
and did not resume ordinary dispatch. Mock ENTRY, RETURN, and CLOSE counts
remained zero, and the terminal safe code was
`PROVIDER_DISPATCH_AMBIGUOUS`. The official Audit and Cleanup both passed, the
production database remained unchanged, and no Fixture or Harness residual
remained. The accepted packaged JAR SHA-256 is
`3920320145d41df253175dc0d40d5fa388d2dc18cdad1cc08c88680758728cef`.
Batch 1 is therefore complete at four of four scenarios.

```text
SIGINT_LIVE_ROOT_CAUSE=SIGINT_IGNORED_CONFIRMED
SIGINT_LAUNCH_REPAIR=env --default-signal=INT
SIGINT_FOCUSED_TESTS=63
SIGINT_BROADER_TESTS=83
B2_PREFLIGHT=PASS
PACKAGE_BUILD=PASS
B2_PACKAGED_HARNESS=PASS
RECOVERY_PROVIDER_CALLS=ZERO
INT_AFTER_SEND_STARTED_SAFE_CODE=PROVIDER_DISPATCH_AMBIGUOUS
CURRENT_JAR_SHA256=3920320145d41df253175dc0d40d5fa388d2dc18cdad1cc08c88680758728cef
ROUND 9C-B.2 FULL SERVER-LOCAL MAVEN SUITE:
DEFERRED_BY_OPERATOR
TERM_BEFORE_CLAIM=PASS
TERM_AFTER_CLAIM=PASS
TERM_DURING_NOT_SENT=PASS
INT_AFTER_SEND_STARTED=PASS
FORMALLY_PASSED_C3_BATCH1_SCENARIOS=4
ROUND9CC3_BATCH1_COMPLETE=YES
```
