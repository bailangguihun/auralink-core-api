# Owned uploaded painting → music

The direct, single-transform `PAINTING_TO_MUSIC` workflow accepts either a resolved catalog painting or an authenticated user's own ACTIVE/PRIVATE/IMAGE/PAINTING/USER_UPLOAD asset. Generated-painting chains remain unavailable. Browser requests still carry exactly one existing public `paintingId` or `assetId`; there is no new client-supplied source-kind field and no manufactured catalog row.

Core resolves ownership before creation persistence, retains the actual source foreign key, and rechecks owner, status, type, visibility, semantic/source type and image bytes before SEND_STARTED. Uploaded images must match their stored SHA-256, length, MIME and dimensions. Existing private storage containment, image decoding, duration 3–30 seconds and provider concurrency remain in force. PAINTING_TO_POEM and other modalities are unchanged.

The production PostgreSQL upload service already writes `catalog_private_media_assets` and `media_assets` in one transaction, retaining the same public UUID and verified private/managed file copies. This feature does not change that writer, database schemas, or the completed catalog projection repair.

## Managed wire identity

Core derives `X-ArtLIVE-VMM-Source-Kind` from persisted, materialized source identity:

- `CATALOG_PAINTING`: only `X-ArtLIVE-VMM-Source-Painting-Id`, the actual internal painting row ID.
- `OWNED_UPLOAD`: only `X-ArtLIVE-VMM-Source-Asset-Id`, the actual internal media row ID. It is not a painting ID.

Owner/creation/step/retry/request IDs and the hash of the decoded image remain bound by the authenticated managed request. The VMM wrapper does not access the database or a user URL: Core is the ownership authority. The wrapper validates mutually exclusive namespaces and persists the source kind and source ID in its existing request ledger before any model call. Same request IDs remain consumed across namespace changes, restart, failure and UNKNOWN. Legacy catalog-only Core requests remain accepted without a source-kind header. Historical acceptance permits remain catalog-only.

The wrapper reports `source_kinds` in authenticated status. Core refuses upload admission/dispatch against a wrapper that does not advertise OWNED_UPLOAD. The Web accepts uploads only when Core reports `CATALOG_OR_OWNED_UPLOAD_PAINTING_SINGLE_TRANSFORM_ONLY`; older Core responses retain the upload restriction.

## Offline evidence

- `VmmManagedContractTest`: real Core HTTP adapter and old-wrapper refusal, typed headers, acceptance isolation, duration/UNKNOWN behavior.
- `CreationCatalogInputMaterializerTest`: actual private storage/image staging, owned upload and invalid owner/media/hash/size/dimensions/symlink rejection, unchanged catalog resolution.
- `OwnedUploadMusicSourceTest`: actual JPA resolver, submission and music-result persistence, real private audio storage/readback and cross-user denial, both SQLite and disposable PostgreSQL; no provider invocation.
- `VmmOwnedUploadBoundaryTest`: actual Core adapter → reviewed Deploy ManagedService over loopback HTTP, synthetic CPU WAV backend only; upload ledger, replay refusal and catalog success. Explicit `vmm.fixture.source` binds the peer test fixture.
- `tests/run_vmm_owned_upload_postgres.py`: disposable PostgreSQL, always stopped, explicit new fixture directory, no production connection.

## Release and approval boundary

Required application components: Core JAR, managed VMM service module (`vmm_service.py` through the reviewed module transaction), Web. AI services/model weights/database repair require no change. Existing startup/OPEN approvals remain consumed; deployment never authorizes initialization or generation.

**Current production publication blocker:** the installed `R25_MANAGED_VMM_CORE_SUCCESSOR_V1` is not a legal predecessor of `prepare-managed-vmm-successor`. The current module-revision chain also preserves Core policy/controller identities. Do not invoke the old followup/successor binder again, edit policy/hash files manually, or use a module-only plan to publish a Core JAR. A separately reviewed finite Core publication binding compatible with the current successor/module-revision proofs is required before an executable production plan can be approved. This feature PR does not implement that control-plane extension.

After normal main review and same-repository main→release promotion, preserve formal package/run/attempt/inventory evidence. The deployment preparation must resolve that binding blocker, keep VMM disabled/STOPPED/CLOSED, revalidate backups/data/history/proofs, and produce actual unsigned transaction plans. Sequence the reviewed VMM wrapper and compatible Core under maintenance, then Web; preserve all historical media, projection rows, approvals, consumption and UNKNOWN. Service/app rollback uses the documented transaction; never restore an old DB/ledger snapshot. Web release merge automatically triggers production deployment and must wait for its concrete approval.

Only after deployment verification and fresh CLOSED startup plus independent human OPEN may the user manually test their uploaded image. No automatic production sample or business POST is part of this change.
