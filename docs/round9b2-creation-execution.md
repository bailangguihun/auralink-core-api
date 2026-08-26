# ROUND 9B.2 — Creation execution engine

ROUND 9B.2 turns a persisted `QUEUED` Creation into one serial, snapshot-driven execution. It remains disabled by default through `AURALINK_CREATIONS_ENABLED=false`.

The scheduler claims at most one oldest `QUEUED` row (`created_at`, then `id`) with one conditional SQLite update. The update sets `RUNNING`, a UUID claim token, and a bounded lease. Every subsequent worker mutation requires the Creation internal ID, `RUNNING` status, and that exact token. Terminal rows clear the claim and lease.

The scheduler never calls an adapter. It hands a claim to a one-thread `ThreadPoolTaskExecutor` with a bounded handoff queue and `AbortPolicy`; rejected pre-dispatch work returns to `QUEUED` only when no Step is `RUNNING` or `SEND_STARTED`.

For each linear Step, short transactions persist `PENDING -> RUNNING`, increment the attempt once, and then persist `NOT_SENT -> SEND_STARTED` with an opaque local request key. The provider is invoked only after the second transaction commits and never inside a Spring transaction. There is no provider retry or fallback.

Input is materialized only from the persisted Creation source or a previous durable Step output. Text remains internal. Images are revalidated through the controlled MediaAsset/staging services. Official Painting input can include safe catalog metadata; generated intermediate Painting input must be an active private owner-owned generated asset. Intermediate poem input is parsed again with the strict four-field poem contract.

Painting results must be one validated JPEG/PNG artifact. `MediaAssetService.storeGeneratedAsset`, `CreationStep.outputAsset`, and a terminal `Creation.finalAsset` are committed together. Poems are revalidated and persisted only as canonical `schemaVersion`, `title`, `lines`, and `text` JSON. No raw envelope, prompt, request key, storage path, provider URL, model, reasoning, or base64 is persisted in public output fields.

Provider artifacts and input staging artifacts are always closed in `finally`. The underlying artifact implementation permits cleanup only for a direct child of its configured staging root and never touches managed MediaAsset storage or retained ROUND 8.1 files.

`PAINTING_TO_MUSIC` always reports `PAINTING_TO_MUSIC_DEFERRED_NOT_VALIDATED`; `PAINTING_TO_VIDEO` always reports `RESERVED_FOR_FUTURE_IMPLEMENTATION`. Only text/image/poem/painting transformations in the four approved operations can execute, and capability metadata additionally requires the feature, active engine, exact adapter binding, and `READY_FOR_CONTROLLED_EXECUTION` readiness.

ROUND 9C-B.1 adds owner-authorized retry and immutable attempt evidence; see `round9cb1-attempt-ledger-safe-retry.md`. ROUND 9C-B.2 remains deferred: stale-running/lease recovery, startup recovery, ambiguous-send recovery, favorites, production activation/migration, real provider validation, and frontend work. External exactly-once execution cannot be guaranteed across an uncatchable process crash.

## Packaged mock harness

After a server-local clean package, run:

```bash
cd /root/autodl-tmp/auralink/backend
bash src/test/scripts/round9b2-packaged-mock-harness.sh

# Round 5.1 production activation safety must remain fully released.
for control_path in \
  /root/auralink_activation_backups/.round51-maintenance \
  /root/auralink_activation_backups/.round51-startup-gate; do
  [[ ! -e "$control_path" ]] || { printf 'unexpected Round 5.1 control: %s\n' "$control_path" >&2; exit 1; }
done
if compgen -G '/root/auralink_activation_backups/.round51-activation-startup-gate-orphan-fence-*' >/dev/null; then
  printf 'unexpected Round 5.1 orphan fence\n' >&2
  exit 1
fi
pgrep -af 'Round51Activation(Command|Coordinator)|round51-packaged' && exit 1 || true
```

The harness starts an ephemeral local Spring server, uses a temporary V1+V2+V3+V4 SQLite database and temporary managed/staging roots, enables only the explicit in-process adapter, then closes the context and removes its owned temporary tree. It never reads `backend/.env`, opens `backend/auralink.db`, or contacts a provider.

## Required server-local validation

Run these commands only from `/root/autodl-tmp/auralink` after the operator has audited the production database before the run. They do not enable Creations in `backend/.env` and do not run Spring against the production database.

```bash
cd /root/autodl-tmp/auralink
git diff --check
git diff -- . ':(exclude)backend/.env' | \
  rg -n '(sk-(proj-)?[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16})' && exit 1 || true
mapfile -d '' -t untracked_files < <(git ls-files -z --others --exclude-standard)
if ((${#untracked_files[@]})) && rg -n \
  '(sk-(proj-)?[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16})' "${untracked_files[@]}"; then
  exit 1
fi
git diff --name-only
git ls-files --others --exclude-standard
cd backend
mvn -o test
mvn -o -DskipTests clean package
bash src/test/scripts/round9b2-packaged-mock-harness.sh
```

Use read-only immutable SQLite access for the production audit, before and after the validation:

```bash
cd /root/autodl-tmp/auralink
sha256sum backend/auralink.db
sqlite3 'file:backend/auralink.db?mode=ro&immutable=1' '
  PRAGMA integrity_check;
  PRAGMA foreign_key_check;
  SELECT "users", count(*) FROM users
  UNION ALL SELECT "generation_logs", count(*) FROM generation_logs
  UNION ALL SELECT "paintings", count(*) FROM paintings
  UNION ALL SELECT "catalog_media_assets", count(*) FROM media_assets WHERE owner_user_id IS NULL
  UNION ALL SELECT "painting_guides", count(*) FROM painting_guides
  UNION ALL SELECT "painting_favorites", count(*) FROM painting_favorites
  UNION ALL SELECT "user_workflows", count(*) FROM user_workflows
  UNION ALL SELECT "creations", count(*) FROM creations
  UNION ALL SELECT "creation_steps", count(*) FROM creation_steps
  UNION ALL SELECT "creation_favorites", count(*) FROM creation_favorites
  UNION ALL SELECT "catalog_import_runs", count(*) FROM catalog_import_runs;
  SELECT version, description FROM flyway_schema_history ORDER BY installed_rank;'
```
