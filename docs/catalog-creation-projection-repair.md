# Catalog Creation projection repair candidate

Gallery reads canonical `catalog_*` tables, while `CreationSourceResolver` queries
the JPA `paintings` table and its `media_assets` relation. A current ACTIVE/READY
catalog painting can therefore be listed and its image served while Creation
returns HTTP 422 / `CREATION_SOURCE_INVALID` before capability or VMM admission.
This is distinct from the existing catalog-only music restriction on uploads.

The observed deployed resolver remains unchanged. The narrow candidate fills an
entirely missing compatibility triple: media asset, painting and
`catalog_creation_painting_projection`. It preserves both canonical UUIDs, batch
and revision, the media digest and original public metadata. It neither copies
image files nor updates existing rows. Input materialization continues to use
the installed verified catalog-media resolver.

`scripts/catalog_creation_projection_repair.py` ONLY renders SQL for review. It
has no database connection, apply command, startup/OPEN approval, or provider
call. The snapshot must come from current authenticated catalog reads, current
canonical SQL and a verified GET of the actual image (digest, length and MIME).
The SQL rechecks current canonical membership/status, linkage, revision, digest
and metadata in the same transaction, using bounded locks. Any pre-existing or
partial projection, conflict, drift or injected failure refuses/rolls back the
transaction. Replaying a completed repair is rejected. No sequence is reset.

## Local verification

```bash
python3 -B -m unittest discover -s tests -p test_catalog_creation_projection_repair.py -v
mvn -B -ntp -Dtest=CatalogProjectionSourceContractTest test
python3 -B tests/run_catalog_projection_postgres.py \
  --fixture /absolute/new/disposable-fixture \
  --pg-bin /usr/lib/postgresql/16/bin \
  --pg-lib /usr/lib/x86_64-linux-gnu --port 55903
```

The PostgreSQL harness creates and stops its own loopback-only cluster. It uses
synthetic data, actual V006 compatibility-table DDL, actual PaintingRepository
JPA loading and the unchanged CreationSourceResolver. It exercises missing to
repaired lookup, historical-row preservation, replay, partial projection,
ineligible/stale canonical inputs, transaction rollback and synchronized lock
contention. There is no application server, model, worker or provider call.

## Required production path (not performed by this change)

Use the normal source PR to main and responsible-owner review. This patch does
not require replacing Core, Web, AI or the installed VMM consumer. Passing CI or
merging it does not authorize applying SQL. Data repair requires the existing
coordinated production transaction: fresh exact scope/digest approval, the
original shared deployment lock, disabled/STOPPED/CLOSED and drained baseline,
database/private-media consistency backup, immutable preimages and a reviewed
writer/manifest binding. Do not feed this SQL to an ad-hoc production psql shell
or reuse an earlier module/startup/business approval. The current installed
publisher has not been established to authorize this new data-write scope; a
reviewed bounded Deploy entry point is required before production application.

Re-read the actual image through its bound media root immediately before that
transaction. Refuse digest/size/MIME drift. Verify all three inserts and the
unchanged historical/accounting/consumption records after commit. A failed
transaction rolls back its new rows; a committed repair must not be undone by
restoring a historical database snapshot. Any post-commit recovery needs its
own dependency-aware approved plan. No generation or automatic retry is part of
this repair; any later website exercise needs fresh operating approvals.

An observed catalog UUID is not proof of a user's historical submitted UUID.
Retain missing browser request evidence as unknown; do not invent its timestamp,
workflow ID, payload, HTTP response or cause from title matching alone.

## Bulk scope and future publication gate

`scripts/catalog_projection_bulk.py coverage SNAPSHOT.json COVERAGE.json` is a
read-only, offline gate over a current repeatable-read snapshot. The snapshot
must include every current ACTIVE/READY canonical catalog record, all JPA
paintings/assets and every projection ledger row. Exit 2 means the catalog is
not fully Creation-ready. The report distinguishes VALID, FULLY_MISSING,
PARTIAL and CONFLICT; a source-key, storage-key or ledger alias is a conflict,
not permission to overwrite a row. Only FULLY_MISSING records may enter the
bulk repair manifest. Partial/conflicting records require separate diagnosis.

Gallery publication and image GET validation alone are insufficient. The
previous approved importer publishes `catalog_*` and advances its current head,
but its committed-snapshot verification does not cover the three compatibility
tables. The earlier projection command is an explicit operation, not an import
hook or database trigger. Reusing that import path can repeat the omission.
Every future import/activation must therefore run this coverage gate before it
is signed off as Creation-ready, then prepare/review any missing projection
scope and rerun coverage after repair. Do not patch a frozen historical importer,
silently add unapproved writes to its old approval, or enable the legacy CSV
startup importer as a substitute. This new gate must be adopted in the next
approved ingestion procedure; it is not retroactively installed by this PR.

`prepare MANIFEST.json REPAIR.sql` renders one atomic transaction for all sorted
records. Its `preservation` object binds the complete public-table inventory and
count/SHA-256 of every existing row. The transaction checks these preimages and
all canonical eligibility/media/revision/metadata predicates under bounded
locks, inserts three sets, verifies complete new relations and unchanged old
rows, then commits once. Changed scope, partial material, replay, canonical
drift, new tables or historical mutation abort the whole transaction. Identity
sequences may advance even on rollback; never reset or refund them.

Use `catalog_projection_transaction.py` from the separately reviewed Deploy
change for the coordinated execution. It reuses the installed CoreRuntime
lease, existing Java/watchdog quiescence, consistency backup and unchanged-code
resume. It does not install itself as a consumer module. Explicit approval binds
the complete plan, source code, SQL, manifest, actual media files and preserved
VMM records. A lost response is reconciled with READ ONLY pre/postimage checks;
there is no second SQL submission and no database snapshot restoration.

Additional targeted verification:

```bash
python3 -B -m unittest discover -s tests -p test_catalog_projection_bulk.py -v
python3 -B tests/run_catalog_projection_bulk_postgres.py \
  --fixture /absolute/new/bulk-fixture --pg-bin /usr/lib/postgresql/16/bin \
  --pg-lib /usr/lib/x86_64-linux-gnu --port 55903 --jpa
```

The optional `--manifest /private/reviewed-manifest.json` rehearses actual public
catalog metadata in the new disposable database. No credentials, production
rows unrelated to catalog metadata, model, application scheduler or provider
are used. The all-record JPA test checks every repaired UUID through the actual
unchanged repository and resolver. Preserve failed fixture logs; a passing
offline rehearsal is not production application or browser generation acceptance.
