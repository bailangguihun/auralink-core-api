# ArtLIVE community foundation

Status: first isolated implementation candidate; not an installed production
service. Java Core classes, migrations V001–V016, VMM and existing data remain
unchanged.

The community-service directory is a separate Node process beside Core,
with an existing repository for contract review. It is not inside the JAR.
It does not run the supplied prototype or import its SQLite databases,
administrators, users, coins, posts or generated engagement.

## Implemented scope

- Verify existing tokens through Core's authenticated /api/user/profile.
  No second login, password table, copied signing secret or supplied actor ID.
- Authenticated posts, comments, explicit like/favorite states and owner lists.
- Gallery references retain the original UUID, validate at publication and
  revalidate for each viewer. Restricted sources hide their metadata.
- Phase one displays a text card linked to the original. Thumbnail copying,
  redistribution, AI rights and shared private uploads remain unimplemented.
- Atomic per-actor request journal. Same request returns one result;
  changed payload under the same key is rejected. Lost replies use GET
  reconciliation; no automatic resend.
- GET does not create accounts, wallets, rewards or settlements.
- No static /uploads mount. A reserved media primitive authorizes first and
  emits exactly one 200/206/416 response; its tests use real local HTTP.
- Capabilities disable uploads, rooms, voice, coins and AI.

Input archive SHA-256:
b5c3dda47c7cda3c74b25360babb49a8bec46071c12baaea19e36a6ab2627ecf

Room, quoted-post, tags, notifications, moderation, multimedia and wallet
logic remain tracked for subsequent phases, not silently declared migrated.

## API contract

Prefix: /api/v1/community. Each route verifies bearer identity through Core
and returns private/no-store JSON.

| Method/path | Meaning |
| --- | --- |
| GET /capabilities | Current actor and supported flags |
| GET /posts?page=0&size=12&view=all | all / mine / favorites |
| GET /posts/{uuid} | Current visible post and checked source |
| POST /posts | requestId, content (1–5000), optional galleryArtworkId |
| DELETE /posts/{uuid} | requestId; author-only soft delete |
| GET /posts/{uuid}/comments | page/size |
| POST /posts/{uuid}/comments | requestId, content (1–1000) |
| PUT /posts/{uuid}/like | requestId, enabled boolean |
| PUT /posts/{uuid}/favorite | requestId, enabled boolean |
| GET /operations/{requestId} | Owner-scoped COMMITTED or NOT_OBSERVED |
| GET /gallery-references/{uuid} | Checked platform metadata |

NOT_OBSERVED does not prove that an earlier request cannot commit.
Web retains the pending operation and offers only read-only reconciliation.
Only an operation ID, actor ID and kind survive in per-account sessionStorage;
no token, draft content or media is stored there by this module.

## Local tests

Use Node 22.13 or newer:

~~~bash
cd community-service
npm ci --ignore-scripts
npm test
~~~

The default tests use PGlite, the PostgreSQL engine in WASM, and a contract-only
Core HTTP fixture. They do not establish native multi-connection or actual
Spring/Core interoperability.

Native PostgreSQL test requires a fresh disposable loopback database named
exactly community_fixture:

~~~bash
export COMMUNITY_TEST_DATABASE_URL='postgres://community_fixture:disposable_fixture_only@127.0.0.1:5432/community_fixture'
npm run test:postgres
~~~

The test refuses an existing schema, never drops databases, and fails rather
than skips if its dependency is absent. The candidate CI supplies PostgreSQL
16. An unrun CI job is not a pass.

Browser test from the matching Web candidate:

~~~bash
cd /path/to/auralink-web
npm ci
NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:3199 npm run build
COMMUNITY_SERVICE_ROOT=/path/to/auralink-core-api/community-service \
COMMUNITY_BROWSER_OUTPUT=/path/outside/repositories/community-browser \
node tests/community.browser.mjs
~~~

Set PLAYWRIGHT_MODULE if Playwright is installed outside Web dependencies.
The harness starts built Next at 127.0.0.1:3177 and deterministic fixtures.
Browser writes go through the actual proxy/Node/PGlite path, never production.

## Runtime and migration

db/001_foundation.sql is an explicit migration, never executed on application
startup. It creates only community.* without cross-schema foreign keys.

Required private settings:

- COMMUNITY_DATABASE_URL: dedicated runtime role, not Core's DB identity.
- COMMUNITY_CORE_ORIGIN: explicit loopback HTTP origin of actual Core.
- COMMUNITY_WEB_ORIGIN: exact permitted browser origin.
- COMMUNITY_PORT: a verified free loopback port, 1024–65535.
- Web's server-only COMMUNITY_INTERNAL_ORIGIN: community loopback origin.
  Never expose it as NEXT_PUBLIC_*.

Startup refuses a runtime role with superuser, role/database creation, schema
creation or write privileges on other schemas. The migrator owns objects.
Runtime requires schema USAGE, SELECT on community tables, INSERT/UPDATE on
profiles/posts/operations, INSERT on comments, INSERT/DELETE on reactions.
It requires no DDL or deletion of post/comment/operation history.

Use npm start only after reviewed migration, private configuration and a
managed lifecycle are ready. No default credentials, seeds, providers or GPU
initialization are present.

## Remaining acceptance

Core JAR publication cannot install this Node process. Web deployment must
preserve its new server-only setting. The matching Deploy proposal describes
the missing service/configuration/migration/supervision binding.

Before production: actual Core login expiry/restrictions, native PostgreSQL
concurrency and role privileges, source revocation, process restart recovery,
managed lifecycle, and existing-data preservation must pass individually.

Phase two retains rooms and annotations. Knowledge access must follow the
user's requirement to query the data on AutoDL; this work does not enable
RAGFlow. Voice, coins, admin and remaining 2.0 features stay separate.
