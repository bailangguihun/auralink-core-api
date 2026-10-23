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
