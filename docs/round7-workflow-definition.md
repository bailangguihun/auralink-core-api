# Round 7 private workflow definitions

Round 7 adds reusable, private workflow definitions. It does not execute a
workflow, create generation history, or contact Seedream, Qwen, VMM, the Guide
Service, or a video provider.

## Ownership and feature boundary

Every workflow owner is derived from the authenticated principal through
`CurrentUserService`. Requests cannot choose an owner. Listing, reading,
replacing, and deleting are scoped to that persisted user. A missing workflow
and a workflow owned by another user both return `WORKFLOW_NOT_FOUND` with HTTP
404.

`AURALINK_WORKFLOWS_ENABLED` defaults to `false`. When it is false, validation
and CRUD return `WORKFLOWS_DISABLED` with HTTP 503. Authenticated capability
discovery remains available and reports `featureEnabled: false`.

The other schema-one limits are:

- `AURALINK_WORKFLOW_SCHEMA_VERSION=1`
- `AURALINK_WORKFLOW_MAX_GRAPH_BYTES=65536`
- `AURALINK_WORKFLOW_MAX_NODES=16`
- `AURALINK_WORKFLOW_MAX_EDGES=15`
- `AURALINK_WORKFLOW_MAX_NAME_CHARS=120`
- `AURALINK_WORKFLOW_MAX_DESCRIPTION_CHARS=2000`

## Graph contract

The graph is a stricter form of DAG: one fully connected directed chain. It has
exactly one `SOURCE`, exactly one terminal node, and at least one `TRANSFORM`.
Every transform has one incoming edge. Every non-terminal has one outgoing
edge. Branching, merging, cycles, disconnected nodes, duplicate edges, and
self-edges are invalid.

Node IDs must match `[A-Za-z][A-Za-z0-9_-]{0,63}` and must be unique. Unknown
JSON fields are violations, including fields supplied with a JSON `null` value
where that field is forbidden for the node kind.

The top-level reusable definition has this shape:

```json
{
  "name": "我的国画音乐工作流",
  "description": "可选说明",
  "graph": {
    "schemaVersion": 1,
    "nodes": [
      {
        "id": "source",
        "kind": "SOURCE",
        "outputModality": "TEXT_DESCRIPTION"
      },
      {
        "id": "painting",
        "kind": "TRANSFORM",
        "operation": "TEXT_TO_PAINTING",
        "providerCode": "seedream-5",
        "inputModality": "TEXT_DESCRIPTION",
        "outputModality": "PAINTING",
        "parameters": {}
      }
    ],
    "edges": [
      {
        "from": "source",
        "to": "painting"
      }
    ]
  }
}
```

A source contains only `id`, `kind`, and `outputModality`. Its modality is one
of `TEXT_DESCRIPTION`, `POEM`, `IMAGE`, or `PAINTING`. Source values are not
part of a reusable definition: no text, poem, painting ID, asset ID, URL, path,
or Base64 data is stored.

A transform contains only `id`, `kind`, `operation`, `providerCode`,
`inputModality`, `outputModality`, and `parameters`. `parameters` is required
and must be the empty object in schema version 1. An edge contains only `from`
and `to`.

## Operation catalog

| Operation | Input | Output | Provider code | Definition | Execution |
|---|---|---|---|---|---|
| `TEXT_TO_PAINTING` | `TEXT_DESCRIPTION` | `PAINTING` | `seedream-5` | enabled | unavailable |
| `POEM_TO_PAINTING` | `POEM` | `PAINTING` | `qwen3vl-seedream5` | enabled | unavailable |
| `IMAGE_TO_PAINTING` | `IMAGE` | `PAINTING` | `seedream-5` | enabled | unavailable |
| `PAINTING_TO_MUSIC` | `PAINTING` | `AUDIO` | `auralink-vmm` | enabled | unavailable |
| `PAINTING_TO_POEM` | `PAINTING` | `POEM` | `qwen3-vl-plus` | enabled | unavailable |
| `PAINTING_TO_VIDEO` | `PAINTING` | `VIDEO` | `reserved-video` | disabled | unavailable |

The user must select the exact permitted `providerCode`; the backend does not
infer one. AUDIO, generated POEM, and VIDEO are terminal. VIDEO remains exposed
for editor discovery with reason `RESERVED_FOR_FUTURE_IMPLEMENTATION`, but the
validator rejects it. A generated PAINTING may be terminal or may feed one
painting transform.

## Capability and private APIs

All routes require authentication:

- `GET /api/v1/workflow/node-types` returns schema version, feature status,
  source modalities, all six operations, allowed definition providers, and a
  strict empty parameter schema. It never reads credentials or probes a
  provider.
- `POST /api/v1/me/workflows` creates an owned workflow and returns HTTP 201.
- `GET /api/v1/me/workflows` returns a stable page of summaries, ordered by
  `updatedAt DESC, publicId ASC`. Defaults are page 0 and size 20; maximum size
  is 100. Summaries omit the graph.
- `GET /api/v1/me/workflows/{workflowId}` returns an owned detail.
- `PUT /api/v1/me/workflows/{workflowId}` fully replaces metadata and graph
  while preserving owner and public UUID.
- `DELETE /api/v1/me/workflows/{workflowId}` deletes only the owned definition
  and returns HTTP 204.
- `POST /api/v1/me/workflows/validate` validates without persistence. Both a
  valid and an invalid well-formed request return HTTP 200. Invalid results set
  `canonicalGraph` to `null` and return stable, safe violation details.

Create and replace use the same `WorkflowValidator` as the validation endpoint.
Invalid create or replace requests return HTTP 422. Malformed JSON remains HTTP
400. Public timestamps are UTC ISO-8601 strings with exactly millisecond
precision and represent the same epoch milliseconds stored by SQLite.

## Canonical storage

After successful validation, canonicalization starts at the source and follows
the single chain to its terminal. Nodes and edges are stored in traversal order,
parameter keys are sorted, and JSON is UTF-8 without formatting whitespace.
Equivalent input array ordering therefore produces byte-identical `graph_json`.
The graph contains no user identity or timestamps. The existing V2
`user_workflows` table stores the public UUID, owner relationship, normalized
metadata, canonical `graph_json`, schema version 1, `ACTIVE` status, and
timestamps; no node or edge tables are added.

## Snapshot boundary

`WorkflowSnapshotFactory` creates a detached immutable value containing
`snapshotVersion`, public `workflowId`, `workflowName`,
`workflowSchemaVersion`, and the canonical graph. It excludes internal IDs,
owner data, timestamps, and secrets. Its canonical JSON remains unchanged if
the source workflow is later replaced or deleted.

Round 7 does not write this value to a Creation. Provider adapters and runtime
availability belong to Round 8. Creation snapshots, asynchronous execution,
steps, and retry belong to Round 9.
