# Explicit platform artwork context (isolated implementation)

This extension is **user selection → Core public/current lookup → one existing model execution**. It is not natural-language database search, native tools or an agent loop. No RAGFlow configuration or schema migration is added. Guide/Critic contracts are unchanged.

## API and source authority

Authenticated, `Cache-Control: no-store`:

- `GET /api/v1/assistant/artworks/page`: `keyword` (or equivalent `query`, max 200), `page` (zero based, max 100000), `size` (1–10), `sort=source|title|author|dynasty`, `direction=asc|desc`. Filters (max 512 each): `dynasty`, `category`, `author`, `subject`, `paintingSchool`, `style`, `artisticConception`, `paintingMaterial`, `collectionInstitution`, `collectionPlatform`. Unknown/repeated/conflicting parameters are rejected. Returns `{items,totalElements,totalPages,page,size}`; totals are matching works, never selected-source counts.
- `GET /api/v1/assistant/artworks/{canonical-uuid}`: current eligible safe public projection, or 404. Lookup remains available when generation is suspended/history-only, but not when Assistant foundation is disabled.
- Existing chat body retains `artworkRefs:[{artworkId,expectedBatchId,expectedRevision,includeImage:false}]`, up to **five different UUIDs**, plus `retrieval.artworks=true`. Display text from the browser never becomes authoritative context. Frontend submits `artworks=false` with no selection. For legacy clients, `artworks=true` without references yields explicit `EMPTY`, **no implicit keyword search**.

Gallery's canonical current batch/current painting revision and ACTIVE/READY eligibility are reused. Keyword/filter/sort expressions require a public annotation definition. Hidden/internal fields and denormalized title/author fallbacks are not searchable evidence. Collection filters are supported but collection fields are not newly added to the existing model projection. Unknown/zero/unverified values are preserved, never turned into inferred facts.

Each catalog snapshot uses the existing read-only JDBC service with a 5-second transaction deadline. Execution preparation also shares a monotonic 15-second deadline across initial/history lookups, selected-source snapshots, and final revalidation. Checks run before/after every catalog read and before model dispatch; at most one already-running catalog read can finish up to five seconds after that deadline. Expiry is a 504 `ASSISTANT_ARTWORK_TIMEOUT`, not a skipped-history success or a general-answer fallback. Admission keeps its separate single-batch five-second check. This bounds catalog-read multiplication; it does not claim to preempt an unrelated filesystem/SQL operation or provider execution. SQL cancellation/timeouts surface as 504; storage unavailability as 503. Exact UUID/revision checks happen both on admission and again immediately before dispatch; missing, changed or unavailable explicit selections do not silently generate a general answer. No unrestricted SQL, URLs, files, model-supplied identity or production schema changes.

## Bounded model context

The catalog preview retains the historical 2,000 UTF-16-unit value cap, avoiding a split surrogate pair; SQL also bounds retrieved text before Java materialization. The new context assembler limits values to **512 UTF-8 bytes**, source snapshots to **6,000 bytes** and five snapshots to **30,000 bytes**. Identity fields precede verbose annotations when the budget is exhausted. `contextTruncated` and `sourceTextPreviewLimit` disclose the two boundaries. It is a partial public projection, not the complete original annotation file.

Wire references are limited to 48,000 UTF-8 bytes, and branch text plus references plus an 8-KiB system-envelope allowance must fit 128 KiB. Existing message/image/request/output limits still apply. These are input-size controls, not exact token or monetary caps. No extra planning/model calls, automatic retries, fallback provider or automatic summaries are introduced.

AI receives only bounded fields and canonical source/version metadata. The Assistant-only prompt distinguishes records from interpretation, retains uncertainty and explicitly says text-only references are not visual inspection. The selection UI does not set `includeImage`; existing independently authenticated upload capability remains unchanged.

## Durable citations and lifecycle

V014 citation and V015 run JSON storage suffice. No V001–V016 bytes are changed.

An answer citation stores artwork UUID, batch/revision, projection/source-record identity, retrieval timestamp, current detail link, exact bounded excerpt/hash and the complete bounded-public-projection hash. The latter is used for pre-dispatch/history access revalidation; it is not confused with the clipped excerpt hash. Existing citations without the new hash retain their historical check.

Preparation saves retrieval state, but **does not publish answer citations yet**. A valid AI delta/usage/done event confirms model-stream observation; citations are then written under the same lifecycle/fence lock before client content is acknowledged. `deliveryEvidence=MODEL_STREAM_OBSERVED` does not prove model attention, truthfulness or billing. An ambiguous dispatch with no valid event keeps citations unpublished, even though the provider may have received a request. Explicit non-model retrieval records use `LOOKUP_ONLY`. Legacy citations remain visibly unverified for delivery; historical evidence is not rewritten.

Cancellation/deletion fences late citation writes as well as checkpoints/completion. Idempotency, dispatch intent, lease recovery, authenticated SSE and uncertain-run/no-auto-replay behavior are retained. Old answer sources are snapshots; opening `/gallery/{id}` reads current data under current access rules. Exports retain citation snapshots and provenance and never credentials.

## Compatibility / release boundary

Core and AI changes require normal source review and formal packaging; Web depends on the new page API. This is a functional business upgrade, not V1 equivalent repackaging. The V1-named build manifest only binds packaging provenance and does not authorize the legacy V1 production writer. Use the installed Assistant-capable coordinated release contract and its actual approved artifact/configuration bindings, shared lock, journals and c5ea ownership.

The final AI candidate can emit the private `heartbeat {kind:"provider_activity"}` event. New Core accepts both this event and the old AI event set; old Core rejects the new event. Make compatible Core ready first, then publish matching AI/configuration, and release Web last. If the coordinated procedure cannot maintain that order, suspend new Assistant generation through the supported control until both components are compatible. Preserve history/files and do not automatically replay uncertain runs. Review the concrete current controller/configuration bindings before production; this document is not installation approval.

The tested Flash route is the exact DashScope `qwen3.8-flash` with server-side `thinkingMode:"disabled"`, serialized as JSON boolean `enable_thinking:false`. This opt-in is not a default for VL, Guide, Critic or other routes. The accepted configuration retains 90-second application deadlines, 4096 output tokens, no retries/fallback and RAG disabled; the 105-second ceiling belongs to the isolated acceptance runner. Configuration digests must be rebound to future formal artifacts rather than copied from an isolated profile.

Isolated real acceptance passed for one synthetic Flash text/source case and one image-free VL text/source case, including persisted sources, re-login restoration and export. This is not VL visual acceptance, general factual-accuracy certification or Windows human acceptance (still pending). Historical failed calls remain failed. No RAGFlow, poem-graph citations, schema change or production enablement is introduced.
