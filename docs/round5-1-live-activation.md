# Auralink 2.0 Round 5.1 server-local activation

This runbook is for the one-time Round 5.1B activation of the inherited SQLite database and official Painting catalog. It is not a normal deployment script. Run it only from an interactive terminal on the AutoDL server after the Round 5.1A commit has been reviewed.

## Corrected activation context

The first production activation attempt stopped immediately after
`CONTROLLED_JAVA_ACTIVATION_STARTING`. It did not baseline, migrate, or import
the catalog. The activation command had used the full `Application` source with
`WebApplicationType.NONE`; broad application scanning still discovered the
explicit Spring MVC configuration, and MVC's `resourceHandlerMapping` failed
because a non-web context correctly has no `ServletContext`. The activation
tool then preserved its private failure evidence and completed its verified
automatic rollback to the inherited database state.

The corrected command uses a dedicated, explicitly imported, minimal non-web
Spring context. It contains only the datasource/JPA/transaction infrastructure,
four required repositories, catalog reader/importer, MediaAsset catalog
registration, and activation coordinator. It does not load MVC, controllers,
security filters, application runners, or an embedded server and binds no HTTP
port. The ordinary `Application` remains the unchanged servlet web application
for normal backend startup.

Before a second dry run or activation, check out and review the dedicated
activation-context fix commit. Supply that exact new 40-character commit hash
as `AURALINK_ROUND51_EXPECTED_COMMIT`; the earlier Round 5.1A commit is no
longer an approved activation target.

The activation must never be run through the SSHFS-mounted development path. The only accepted project root is:

```text
/root/autodl-tmp/auralink
```

The tool refuses any other real path, refuses an unreviewed Git commit, and never exposes `.env` contents.

## Preconditions

Before the dry run:

1. Open a terminal directly on the AutoDL server and change to `/root/autodl-tmp/auralink`.
2. Confirm the reviewed Round 5.1A commit is checked out exactly. The operator must supply that full commit SHA as `AURALINK_ROUND51_EXPECTED_COMMIT`; do not use a moving branch name.
3. Ensure the Spring backend **and its automatic restart policy** are stopped. Do not stop an unknown process blindly. The tool checks the configured backend port, related Java processes, and open handles to `backend/auralink.db`, and refuses activation if it detects a possible user of the database. During activation it also holds a private startup-maintenance lease that this reviewed backend build checks before Spring initializes; an attempted normal restart is refused before opening SQLite. A cross-language POSIX startup gate is held exclusively by activation/recovery and retained as a shared JVM-lifetime lock by every normal reviewed backend process. This fixed server-maintenance path is checked unconditionally by the reviewed backend, including copied-JAR or service-manager launches outside the project working directory. This closes the final marker-release/startup handoff window. A continuous process/port/database-handle monitor remains active from lease acquisition through verified success or rollback. Detection is fail-closed and never kills an unknown process.
4. Confirm `backend/auralink.db`, `backend/.env`, both Flyway migration files, `frontend/public/data/paintings.csv`, and `backend/picture/` are present under the server-local root.
5. Ensure enough free space exists both outside the project for the verified backup/failure snapshot/logs and beside the live database for SQLite journal/WAL activity plus an atomic restore copy. The tool checks both filesystems before and after packaging. Backups are created under `/root/auralink_activation_backups/<timestamp>/`.
6. Keep `backend/.env` private. Do not print, copy into the command line, or add it to Git. The confirmation phrase is a safety latch, not a secret, and does not belong in `.env`.
7. Leave Qwen, VMM, and external AI providers stopped. They are not needed for catalog activation or the optional Painting API smoke test.

The expected inherited database state is exactly seven `users`, 118 `generation_logs`, `integrity_check = ok`, no `flyway_schema_history`, and none of the nine Auralink 2.0 tables. A different or partially activated state is a stop condition, not a prompt for automatic repair.

## Dry run

Run the dry run first, using the full reviewed Round 5.1A commit SHA:

```bash
cd /root/autodl-tmp/auralink
AURALINK_ROUND51_EXPECTED_COMMIT=REPLACE_WITH_FULL_40_HEX_COMMIT \
  bash backend/scripts/activate-round5-catalog.sh --dry-run
```

The dry run is read-only. It verifies:

- the current directory and script location both resolve to the exact server-local root;
- the filesystem is not the SSHFS development mount;
- the checked-out commit and tracked working tree match the reviewed activation source;
- required database, environment, migration, CSV, and image-corpus paths exist and are safe;
- no backend/Java process or port listener can be using the database;
- the live database is either the exact inherited state or an already-activated healthy state;
- the migration resources and catalog sources are available;
- the intended external backup location and activation phase sequence.

It does not create a backup directory, acquire a mutation lock, baseline, migrate, import, update `.env`, start a service, or stop a service. Require `SERVER_LOCAL_ROOT_VERIFIED` and a successful dry-run result before continuing.

If the tool reports `BACKEND_SERVICE_MUST_BE_STOPPED`, identify and stop the correct service through the server's normal process manager, then rerun the dry run. The activation tool deliberately does not kill unknown processes.

## Activation

After reviewing the dry-run output, run activation with the same full commit SHA and the explicit confirmation latch:

```bash
cd /root/autodl-tmp/auralink
AURALINK_ROUND51_EXPECTED_COMMIT=REPLACE_WITH_FULL_40_HEX_COMMIT \
AURALINK_ROUND51_CONFIRM=ACTIVATE_AURALINK_2_0_CATALOG \
  bash backend/scripts/activate-round5-catalog.sh --activate --smoke
```

Do not run more than one activation process. Do not add a shell timeout: the initial official catalog import previously took approximately 71 minutes and can be healthy while producing no rapid result. Do not interrupt a healthy importer solely because it is long-running.

The controlled sequence is:

1. Recheck the server-local root, commit, tracked-tree state, required files, service-down condition, and inherited database state. Package the activation JAR from a private temporary `git archive` of that exact reviewed commit, not from mutable working-tree source, and recheck the checkout before mutation.
2. Privately create and exclusively hold `/root/auralink_activation_backups/.round51-startup-gate`, recheck that no service is running, then create `/root/auralink_activation_backups/<timestamp>/` with private permissions and lock the verified backup-directory inode (never a truncatable child lock path).
3. Create a consistent SQLite backup using the SQLite backup API, copy `backend/.env` privately without logging its content, write the pre-activation manifest, logically verify the database backup, and fsync the backup files/directories. The tool then creates `round51-recovery-binding.json`, which binds the exact backup directory, reviewed commit, database backup, `.env` backup, manifest, and verification evidence to the nonce-backed global maintenance marker. The binding is durable before the marker becomes visible. Only after that recoverable state is complete does mutation begin. Only the activation-owned Java/import and loopback smoke processes receive the ephemeral nonce; ordinary reviewed-build restarts fail before datasource initialization, while the continuous monitor detects any other process throughout the entire mutation window. The non-web activation JVM is never permitted to listen on the backend port; only the owned loopback smoke JVM may listen.
4. Explicitly baseline Flyway at version 1 with `baselineOnMigrate=false`. Flyway does not execute V1 against the inherited schema.
5. Apply V2 exactly once, validate migrations, and confirm a repeat migrate has zero pending work.
6. Recheck legacy counts, database integrity, foreign keys, and empty 2.0 catalog tables.
7. Invoke the already-tested catalog importer directly with normal startup import disabled. The first import reads the server-local CSV and image directory and does not copy official images.
8. Verify all expected production counts, unique public IDs/source keys, catalog MediaAsset policy, audit status, SQLite integrity, and foreign keys.
9. Invoke the unchanged import a second time and require a fingerprint-based `SKIPPED`/no-op result with stable Painting and MediaAsset public IDs.
10. Atomically update only the four controlled operational settings in private `backend/.env`: Flyway remains disabled, Hibernate remains `none`, and normal catalog synchronization plus fail-on-error become enabled. Existing provider credentials and all unrelated settings remain untouched.
11. If `--smoke` is selected, start only the tool-owned backend process, verify local health, legacy health compatibility, gallery, daily recommendation, safe MediaAsset content URLs, and unchanged-startup import skipping, then stop that exact process.
12. Write a private post-activation manifest and report success.

The activation must retain:

- `users = 7`;
- `generation_logs = 118` with no migration or dual-write;
- `paintings = 11,067`;
- catalog-reference `media_assets = 9,067`;
- `image_available = true` and `visible_in_gallery = true` for 9,067 rows;
- 2,000 missing-image Painting rows;
- two orphan source images, which are not imported as Paintings;
- 8,915 populated official `generated_text` annotations;
- 9,068 populated official `music_scene_description` annotations;
- a successful import audit followed by an unchanged-source skipped audit;
- `integrity_check = ok` and no `foreign_key_check` violations.

The 706 literal `generated_text` values equal to `0` are inherited source data. They must remain unchanged and do not make the activation fail.

## Success verification

A successful run must report the completed activation state and leave private evidence in its timestamped backup directory. Review the activation log and post-activation manifest without displaying `.env`.

The optional smoke phase retains the same maintenance lease, binds the temporary Spring process explicitly to `127.0.0.1`, checks only localhost routes, and needs no AI service:

- `GET /api/health`;
- a legacy health route;
- `GET /api/v1/paintings?page=0&size=1`;
- `GET /api/v1/paintings/daily`.

Gallery responses must contain a Painting, image URLs must use `/api/v1/assets/{uuid}/content`, and no absolute filesystem path may appear. The normal startup importer must recognize the unchanged fingerprint and skip. The temporary smoke backend must be stopped when the phase ends.

## Automatic rollback on controlled failure

After mutation begins, any controlled failure before final success triggers rollback. The maintenance marker remains held until either rollback is verified or activation and smoke verification finish. The tool:

1. confirms no backend process is using the database;
2. attempts to preserve the failed/partial database in the private activation directory for forensic review (if storage or I/O failure prevents that extra snapshot, verified restoration still takes priority and the warning is logged);
3. restores the verified pre-activation SQLite backup to `backend/auralink.db`;
4. restores private `backend/.env` if it was changed;
5. preserves the failed database's SQLite journal/WAL/SHM companions and removes only those exact stale sidecars before restoration while the service is confirmed down;
6. verifies seven users, 118 generation logs, inherited schema, and `integrity_check = ok`;
7. reports `ROLLBACK_COMPLETED`.

Do not delete the failed-state snapshot or backup directory. Do not repeatedly rerun activation until the cause has been reviewed. If automatic rollback cannot prove the restored state, leave the backend stopped and escalate for manual database recovery using the private pre-activation backup.

### Recovery after a host reset or uncatchable process termination

If the host resets or the activation shell receives `SIGKILL`, the durable maintenance marker intentionally blocks ordinary backend startup. The kernel startup-gate holder exits when its coordinator disappears, while the durable marker normally remains authoritative. Each durable orphan fence is cryptographically bound to the exact selected recovery binding, backup directory, reviewed commit, and marker-token digest; recovery refuses unrelated fences from another run. During the final release handoff, the tool first publishes a private hard-link copy of the exact marker nonce and a verified-release intent in the selected backup directory. If a crash occurs after the global marker is detached, the run-bound orphan fence still blocks Spring and the recovery command authenticates both the fence and released-marker evidence against that backup before rebuilding the global marker. Never remove that marker with `rm`; likewise, never manually remove released-marker evidence, a release intent, or an orphan fence. After stopping the backend and its restart policy, identify the exact timestamped activation directory from the interrupted run and invoke the dedicated recovery tool with that directory and the reviewed commit:

```bash
cd /root/autodl-tmp/auralink
AURALINK_ROUND51_EXPECTED_COMMIT=REPLACE_WITH_FULL_40_HEX_COMMIT \
AURALINK_ROUND51_RECOVERY_CONFIRM=RESTORE_AURALINK_ROUND51_PRE_ACTIVATION_BACKUP \
  bash backend/scripts/recover-round5-catalog-activation.sh \
  --backup-dir /root/auralink_activation_backups/REPLACE_WITH_EXACT_RUN_DIRECTORY
```

The recovery tool never auto-selects the latest directory. It requires an exact private direct child of the backup root, the free activation lock, a stopped service, a logically verified inherited database backup, the reviewed clean commit, and a `round51-recovery-binding.json` whose commit, file hashes, directory, and marker-token digest all match that exact interrupted run. If the global marker was already released, recovery accepts only private verified-release intents from activation/recovery and requires at least one retained marker copy to authenticate that same binding; extra stale or unrelated copies cannot authorize recovery. Choosing an older or different valid backup directory is refused before mutation. A continuous service monitor remains active throughout recovery. If the current database and `.env` independently pass every final activated-state check, recovery preserves that completed activation and only releases the stale marker. Otherwise it attempts to preserve the partial database and current `.env`, atomically restores the bound pre-activation database and `.env`, verifies the exact inherited schema/data and service-down state, and only then releases the marker. Extra failed-state snapshots are best-effort when storage itself is failing; restoration and strict verification of the bound backup remain authoritative. Any ambiguous or failed check leaves the startup fence in place. If durable marker re-establishment itself cannot be proved, the kernel gate holder deliberately survives its coordinator behind a private orphan-fence file and blocks Spring until an operator repairs the durable marker. The reviewed Spring startup guard also treats that private orphan-fence file as authoritative after a host reset, even when no kernel lock process survived. Never kill that holder or remove the orphan-fence file before the marker is safely restored and the selected recovery run has completed.

## Already-activated behavior

The tool is rerunnable for verification. If it finds a successful Flyway V1 baseline and V2 migration, all expected catalog/import counts and fingerprint state, and clean SQLite integrity/foreign keys, it does not baseline or import again. It reports:

```text
ALREADY_ACTIVATED_AND_HEALTHY
```

A partially activated or count-mismatched database is refused. An activated database whose four controlled startup settings are not correct is also refused as unhealthy. The tool does not guess, repair, rebaseline, or delete data.

## Normal startup after activation

After successful activation, private `backend/.env` keeps Flyway disabled and Hibernate schema mutation disabled. Normal startup catalog synchronization is enabled with fail-on-error so the startup runner computes the same source fingerprint and returns `SKIPPED` without repeating the long first import.

Start the backend through the server's normal service mechanism only after activation or rollback has finished. Do not enable `AURALINK_FLYWAY_ENABLED` for routine startup, and do not switch `AURALINK_JPA_DDL_AUTO` away from `none`.

Keep `/root/auralink_activation_backups/<timestamp>/` private and retained until the upgraded deployment has been reviewed and backed up through the normal operational process.
