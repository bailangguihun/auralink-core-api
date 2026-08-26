# Auralink 2.0 Round 6.1 live Qwen Guide validation

This runbook covers the one-time Round 6.1B validation of the reviewed AI
Painting Guide path against Qwen3-VL-Plus. Round 6.1A only prepares and tests
the tooling with temporary SQLite databases and a local mock provider: **no real
Qwen request is made and no production `painting_guides` row is written during
Round 6.1A**.

Run the live workflow only from an interactive terminal directly on the AutoDL
server. The only accepted project root is:

```text
/root/autodl-tmp/auralink
```

Do not run it from `/home/bailangguihun/mnt/autodl-auralink`, through SSHFS or
FUSE, from a symlinked substitute root, or through an unattended service. The
validation is local-CLI-only; no HTTP administration endpoint can trigger it.

## What the validation proves

The controlled path is:

```text
existing authenticated user
    -> loopback Spring backend
    -> loopback Guide Service
    -> reviewed Qwen3-VL-Plus OpenAI-compatible endpoint
    -> one validated, persistent standard Painting Guide
```

For one deterministic, annotation-rich official Painting, the first `POST`
must return `cacheStatus=GENERATED`, the second identical `POST` must return
`cacheStatus=HIT`, and `GET` must return the same persisted structured Guide.
The expected paid-provider budget is **one Qwen generation call**. The tool
does not retry the complete live workflow automatically.

The standard Guide is shared by every user. This workflow creates no Guide
browsing history, `generation_logs`, `Creation`, `CreationStep`, MediaAsset,
favorite, chat, audio, or user record.

## Preconditions

Before configuring or running anything:

1. Check out the exact reviewed Round 6.1A commit under
   `/root/autodl-tmp/auralink`. Use its full 40-character hash as
   `AURALINK_ROUND61_EXPECTED_COMMIT`; never use a moving branch name.
2. Keep the worktree clean. The guards reject a mismatched commit, tracked or
   untracked drift, the SSHFS development path, a symlinked project root, and
   an unexpected filesystem type.
3. Stop the normal Spring backend and Guide Service through their normal
   process manager. Disable automatic restart for the duration. Ports 5000 and
   5003 must be free. The tool identifies conflicts but never kills an unknown
   process.
4. Confirm `backend/.env` is a private, regular, non-symlink file. It is the
   **only** provider-secret source. Do not create `guide_service/.env` and do
   not shell-source `backend/.env`.
5. Confirm the activated SQLite database is healthy: seven users, 118 legacy
   generation logs, 11,067 Paintings, 9,067 public catalog MediaAssets, two
   successful Flyway history rows, no foreign-key violations, and initially no
   Painting Guide. A different or partially understood state is a stop
   condition.
6. Retain sufficient free space under
   `/root/auralink_guide_validation_backups/` for a verified SQLite backup,
   failure snapshot, private logs, and audit evidence.
7. Have credentials for an **existing** user available interactively. Do not
   register a validation user and do not change the `users` table.

## Provider and region configuration

Edit `backend/.env` only with a private local editor on the server. Never put a
Qwen API key, login password, internal token, or JWT in a command argument,
terminal transcript, ticket, chat, or documentation.

```bash
cd /root/autodl-tmp/auralink
umask 077
chmod 600 backend/.env
${EDITOR:-vi} backend/.env
```

Inside the editor, populate the existing `QWEN_API_KEY` entry and review the
`QWEN_BASE_URL` and `QWEN_MODEL` entries. Do not add a second key file. For the
initial validation, the model must be:

```properties
QWEN_MODEL=qwen3-vl-plus
```

Do not automatically substitute `qwen-plus`, `qwen-flash`, a local checkpoint,
a snapshot alias, or another provider. The initial validation tool accepts the
exact reviewed alias `qwen3-vl-plus` only and never rewrites it. Supporting a
snapshot alias requires a separate code/configuration review before a later
validation run.

The Base URL must use HTTPS, contain no user information, query, fragment, or
nonstandard request suffix, and end at the OpenAI-compatible API root
`/compatible-mode/v1` (an equivalent single trailing slash may be normalized).
The reviewed official forms are:

| Region | Allowed form |
| --- | --- |
| China / Beijing public | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Singapore public | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` |
| US / Virginia public | `https://dashscope-us.aliyuncs.com/compatible-mode/v1` |
| China / Beijing workspace | `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` |
| Singapore workspace | `https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1` |
| US / Virginia workspace | `https://{WorkspaceId}.us-east-1.maas.aliyuncs.com/compatible-mode/v1` |

`{WorkspaceId}` must be a valid reviewed DNS label; braces are descriptive and
must not appear in the real URL. Arbitrary third-party hosts, localhost,
literal private/link-local addresses, userinfo, pasted full
chat-completions URLs, and secret-bearing URLs are refused. Operator output
shows only a sanitized host and derived region, never credentials or an
unexpected full URL.

An API key does not encode a reliably inspectable region. The dry run therefore
checks the selected official endpoint but does not contact Qwen or test the
key. A key/region or model/region mismatch can be classified only from a safe,
sanitized provider error during Round 6.1B.

## Internal-token helper

Spring and the loopback Guide Service require the same
`AURALINK_GUIDE_INTERNAL_TOKEN`. First inspect the helper without mutation:

```bash
cd /root/autodl-tmp/auralink
AURALINK_ROUND61_EXPECTED_COMMIT='<reviewed-commit>' \
  bash backend/scripts/configure-round6-guide-token.sh --dry-run
```

If the token is blank, create it with the separate guarded helper:

```bash
cd /root/autodl-tmp/auralink
AURALINK_ROUND61_EXPECTED_COMMIT='<reviewed-commit>' \
  AURALINK_ROUND61_TOKEN_CONFIRM=GENERATE_AURALINK_GUIDE_INTERNAL_TOKEN \
  bash backend/scripts/configure-round6-guide-token.sh --ensure
```

The helper generates at least 256 bits of cryptographic randomness, first makes
a private backup of `backend/.env`, preserves every unrelated entry, writes the
token only when needed, and never prints it. If a nonblank token already exists,
the helper leaves it unchanged. Do not copy the token to another file.

## Dry run

Run the dry run after private provider and internal-token configuration:

```bash
cd /root/autodl-tmp/auralink
AURALINK_ROUND61_EXPECTED_COMMIT='<reviewed-commit>' \
  bash backend/scripts/validate-round6-live-guide.sh --dry-run
```

Require both:

```text
DRY_RUN_ZERO_MUTATION
DRY_RUN_OK
```

The dry run verifies, read-only:

- the exact real server-local root, non-FUSE filesystem, reviewed commit, and
  clean worktree;
- private `backend/.env` and all required reviewed code/scripts;
- nonblank provider key and internal token without displaying either;
- the official HTTPS Base URL, sanitized region, and exact reviewed model;
- Guide bind `127.0.0.1:5003`, Spring bind `127.0.0.1:5000`, free ports, and no
  conflicting Java or Guide process;
- live database integrity, foreign keys, expected row counts, Guide baseline,
  and Flyway state;
- exact SHA-256 and JSON shape of the two reviewed static poetry knowledge
  files used by the Guide context;
- if the private backup root already exists, that it is a non-symlink
  operator-owned directory with no group/other access (the dry run never
  creates or repairs it);
- sufficient private backup space;
- deterministic selection of an annotation-rich official Painting;
- that existing-user credentials will be requested only during validation;
- the intended backup, process, API, verification, cleanup, and rollback phases.

It does not create a backup directory, change `.env`, start or stop a process,
contact Qwen, log in, write SQLite, or create a Guide. The live confirmation
phrase is deliberately ignored as authority in dry-run mode.

## Deterministic Painting selection

By default the tool chooses exactly one active, image-available,
gallery-visible official Painting using a documented source-coverage score. It
rewards a meaningful non-`0` `generatedText`, nonblank
`musicSceneDescription`, artistic conception, composition, and brushwork or
ink-method context. A stable `sourceKey`/public-UUID tie-break makes the choice
repeatable.

Only these safe candidate fields are printed:

- Painting public UUID;
- title;
- author;
- dynasty;
- annotation-coverage score.

An operator may append `--painting-id=<UUID>` after reviewing a specific public
Painting UUID. The tool still rejects an inactive, hidden, missing-image, or
source-poor candidate. It never exposes the internal numeric ID or a filesystem
path.

## Live validation

After reviewing the dry-run output, run the separate confirmed command:

```bash
cd /root/autodl-tmp/auralink
AURALINK_ROUND61_EXPECTED_COMMIT='<reviewed-commit>' \
AURALINK_ROUND61_CONFIRM=GENERATE_ONE_LIVE_PAINTING_GUIDE \
  bash backend/scripts/validate-round6-live-guide.sh --validate
```

The tool prompts for an existing username if
`AURALINK_ROUND61_USERNAME` is absent. It always reads the password from an
interactive hidden prompt; a password CLI option is not supported. Supplying a
username through `AURALINK_ROUND61_USERNAME` is optional, but the password must
never be exported. Login uses `POST /api/auth/login`. The JWT remains only in
memory or a mode-0600 run-scoped file and is removed during cleanup; it is never
printed or written to the audit manifest.

The validation sequence is:

1. Recheck every dry-run guard and require the exact confirmation phrase.
2. Create `/root/auralink_guide_validation_backups/<timestamp>/` with mode 0700
   and private files with mode 0600.
3. Back up SQLite with Python's SQLite backup API, copy `backend/.env`
   privately, record a secret-free pre-validation manifest, and logically
   verify the backup's schema, counts, integrity, and foreign keys.
4. Select the deterministic Painting and record only its safe public metadata.
5. Apply process-local validation overrides for `AURALINK_GUIDE_ENABLED=true`,
   `GUIDE_AI_SERVICE_URL=http://127.0.0.1:5003`,
   `AURALINK_GUIDE_SERVICE_HOST=127.0.0.1`, and
   `AURALINK_GUIDE_SERVICE_PORT=5003` without changing `backend/.env`, Qwen
   settings, or any unrelated configuration entry.
6. Use the reviewed `backend/scripts/start-guide-service.sh start` lifecycle,
   retain its exact PID identity, and require its loopback `/health` response to
   report configured status without revealing secrets.
7. Run an offline `clean package`, then start one owned Spring process from an
   empty/sanitized child environment. Highest-precedence command-line
   properties pin the exact production SQLite path, disable Flyway and all
   Hibernate/SQL schema mutation, pin the reviewed catalog paths and startup
   synchronization, pin the loopback Guide URL, and bind Spring only to
   `127.0.0.1:5000`. Require `/api/health`, a public Painting gallery probe,
   and proof that this exact owned PID has the production database open.
   Normal catalog startup must see the unchanged fingerprint and record
   `SKIPPED`.
8. Authenticate the existing user. Login failure stops before any Guide
   provider call. Repeat the pinned static-knowledge fingerprint/shape check
   immediately before entering the Guide API sequence.
9. Call `POST /api/v1/paintings/{paintingId}/guide` once and require HTTP 200
   with `cacheStatus=GENERATED`.
10. Verify one valid `painting_guides` row, all unrelated-table invariants,
    SQLite integrity, foreign keys, and the structured public response.
11. Call the same `POST` again and require HTTP 200 with `cacheStatus=HIT`, no
    second row, unchanged source hash and generation timestamp, and the same
    public Guide result.
12. Call `GET /api/v1/paintings/{paintingId}/guide` and require the same
    persisted result. GET may describe the returned cache as `HIT`; it never
    triggers generation.
13. Complete deterministic content checks, retain the private public-response
    evidence, clean credentials, and stop only the exact owned processes.

The default one-time workflow leaves `backend/.env` byte-for-byte unchanged;
Guide enablement is process-local. It does not leave the Guide Service or
validation Spring process running. A future persistent enablement must be an
explicit, separately reviewed operator choice.

SQLite persists the Guide timestamps at millisecond precision, while the
immediate in-memory `GENERATED` response can contain finer Java nanoseconds.
The validator therefore compares the three public timestamps at SQLite
millisecond precision and separately requires the stored Guide row (including
`generated_at` and `updated_at`) to remain byte-stable across the HIT and GET.
An actual timestamp change still fails validation.

## Expected database effects

The successful first validation changes `painting_guides` from zero to one.
The selected row must have a valid public UUID, nonblank source hash, validated
canonical `result_json`, successful status, Painting relationship, and valid
generation/update timestamps.

Normal production startup catalog synchronization is already enabled. Starting
the validation Spring process therefore performs the required unchanged-source
check and is expected to add one `catalog_import_runs` row with status
`SKIPPED`. This operational audit increment is expected and must match the
unchanged catalog fingerprint; it is not a Guide generation-history write.

All of these remain unchanged:

- `users = 7`;
- `generation_logs = 118`;
- `paintings = 11,067`;
- catalog-reference MediaAssets `= 9,067`;
- `creations` count;
- `creation_steps` count;
- `painting_favorites` count;
- official Painting annotations and catalog source data;
- Flyway migration history.

`PRAGMA integrity_check` must remain `ok` and
`PRAGMA foreign_key_check` must return no rows. No provider call or database
change is accepted before successful existing-user authentication.

## Content-quality review

The tool validates that the public response has the expected Painting UUID and
schema version, a nonblank Chinese summary, the exact sections object, two to
five distinct highlights, and structurally valid knowledge references. It
rejects unknown fields, Markdown fences, HTML, source hashes,
provider/model/prompt metadata, reasoning content, obvious prompt leakage, and
AI self-reference. It records whether a music-association section is present;
it does not claim to prove that prose is factually grounded.

Consistency with the official artist/dynasty fields, semantic grounding of
`musicAssociation` in the official annotation, appropriate nulls for missing
evidence, and art-historical quality remain explicit operator-review duties.

A passing machine check is reported as:

```text
STRUCTURALLY_VALID
OPERATOR_REVIEW_REQUIRED
```

`OPERATOR_REVIEW_REQUIRED` is intentional. Automated validation cannot certify
subjective artistic quality or complete art-historical truth. Review the
private validated public response before enabling Guide generation for normal
users.

## Private evidence and secret-safe errors

The timestamped run directory retains the verified pre-validation database
backup, private `.env` backup, sanitized manifests, a fixed-field runtime
summary, the validator's secret-safe lifecycle log, and the validated public
Guide response. Raw Spring and Guide-service logs are used only while the run
is active and are removed before evidence is finalized, on both success and
rollback; the owned Guide runtime-log delta is truncated back to its recorded
pre-run length after the service stops. The directory must not retain an API key, internal token, password,
JWT, Authorization header, raw provider request, raw provider response, system
prompt, or full unredacted environment.

Provider failures are reduced to fixed safe classifications such as invalid or
missing configuration, key/region mismatch, unavailable model/region, 401/403,
429, timeout, provider 5xx, invalid JSON, invalid Guide structure,
internal-token mismatch, or Spring/Guide timeout. Do not paste provider bodies
into operator output because they may contain sensitive request content.

## Rollback on validation failure

If validation fails after mutation begins, the workflow:

1. stops only the Spring and Guide processes whose PID identity it owns;
2. removes raw service logs, preserves a fixed-field sanitized failure summary
   and lifecycle log, and preserves a failed-database snapshot;
3. restores the verified pre-validation SQLite backup;
4. restores the private pre-validation `backend/.env`;
5. removes only run-owned JWT/password temporary material and safe stale SQLite
   sidecars while every database process is confirmed stopped;
6. verifies the original counts, Painting Guide baseline, schema, integrity,
   and foreign keys;
7. reports `ROUND61_ROLLBACK_COMPLETED`.

Rollback also removes the expected startup `SKIPPED` audit row because the
entire pre-validation database is restored. Failure evidence is retained and
the workflow does not retry automatically. If restoration cannot be proved,
leave both services stopped and recover manually from the private backup.

## Success cleanup

On success, the workflow keeps the one validated Painting Guide, verified
database backup, sanitized manifests/fixed-field lifecycle evidence, and
validated public response. It
verifies that `.env` never changed, removes JWT/password temporary material,
stops only its owned Spring and Guide processes, releases its lock/PID state,
and confirms ports 5000 and 5003 are free.

Do not delete the successful Guide row. Do not start the ordinary backend or
Guide Service persistently until the result has received the required operator
review.

## Already-validated mode

If the selected Painting already has one current, valid standard Guide, the
tool does not call Qwen again. It verifies the row and database invariants,
starts the same owned loopback services, authenticates, and first calls the
cache-only `GET`. Only after GET proves the persisted Guide is current and
returns `HIT` does it call `POST`, which must also return `HIT` without a paid
provider operation. It then reports:

```text
ALREADY_VALIDATED_AND_HEALTHY
```

The Spring startup may still add the normal unchanged-catalog `SKIPPED` audit
record. A corrupt, stale, duplicate, differently selected, or otherwise
ambiguous Guide state is refused at the cache-only GET before any POST can
regenerate it.

## Process lifecycle and troubleshooting

The Guide Service uses the reviewed `backend/scripts/start-guide-service.sh`
launcher and the verified Python 3.9.21 `auralink-ai` environment. It binds only
to `127.0.0.1:5003`, enables no CORS, and writes private PID/log state outside
the project. Spring is likewise bound only to `127.0.0.1:5000` for validation.
Neither local Qwen `app.py`, VMM, Seedream, nor any other provider/model service
is started.

The orchestrator records PID plus process identity before treating a child as
owned. It sends a graceful stop only to a matching owned process. If either
port is occupied before the run, it reports safe process-identification details
and stops; it never kills an unknown listener. At successful or rolled-back
exit, both ports must again be free.

If the Guide health probe is unconfigured, recheck the private `.env` with an
editor and rerun the dry run; do not print the file. If login fails, verify the
existing user's credentials without creating a new user. If Qwen reports a
region/model/key error, review the selected official Base URL and its account
region before a separately confirmed retry. Do not change model or provider to
make the check pass.

## Optional reviewed Painting

To use a separately reviewed eligible Painting, append its public UUID only:

```bash
cd /root/autodl-tmp/auralink
AURALINK_ROUND61_EXPECTED_COMMIT='<reviewed-commit>' \
AURALINK_ROUND61_CONFIRM=GENERATE_ONE_LIVE_PAINTING_GUIDE \
  bash backend/scripts/validate-round6-live-guide.sh --validate \
  --painting-id=REPLACE_WITH_REVIEWED_PAINTING_UUID
```

The provider key, password, internal token, and JWT must never appear in this or
any other command.
