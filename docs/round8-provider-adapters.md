# Round 8 Creation provider adapters

Round 8 adds internal provider contracts for the private workflow definitions delivered in Round 7. It does not add a Creation API, execute a saved workflow, persist generated results, or claim live provider validation.

## Provider-neutral boundary

Future Round 9 orchestration resolves and authorizes source material before constructing a `ProviderExecutionRequest`. The request contains only a safe correlation ID, exact workflow operation, exact stable provider code, and a typed `ProviderTextInput` or `ProviderImageInput`. Adapters never receive JPA entities, HTTP/controller objects, credentials, environment maps, arbitrary URLs, client paths, or storage keys.

`ProviderAdapterRegistry` is the authoritative operation/provider-to-adapter mapping and fails startup if it drifts from `WorkflowCapabilityRegistry`:

| Operation | Input | Output | Stable provider code | Adapter |
| --- | --- | --- | --- | --- |
| `TEXT_TO_PAINTING` | `TEXT_DESCRIPTION` | `PAINTING` | `seedream-5` | `SeedreamProviderAdapter` |
| `IMAGE_TO_PAINTING` | `IMAGE` | `PAINTING` | `seedream-5` | `SeedreamProviderAdapter` |
| `POEM_TO_PAINTING` | `POEM` | `PAINTING` | `qwen3vl-seedream5` | `QwenSeedreamCompositeProviderAdapter` |
| `PAINTING_TO_POEM` | `PAINTING` | `POEM` | `qwen3-vl-plus` | `QwenPaintingToPoemProviderAdapter` |
| `PAINTING_TO_MUSIC` | `PAINTING` | `AUDIO` | `auralink-vmm` | `AuralinkVmmProviderAdapter` |
| `PAINTING_TO_VIDEO` | `PAINTING` | `VIDEO` | `reserved-video` | none; reserved and disabled |

Missing provider codes are never inferred. Round 7 graph topology, modalities, empty parameter objects, ownership, canonicalization, CRUD, and validation are unchanged. Capability discovery still reports `executionAvailable=false`; implemented operations now use the accurate reason `CREATION_EXECUTION_DEFERRED_TO_ROUND_9`.

## Typed input and output

Text inputs are trimmed UTF-8 logical strings with a configured character limit and no NUL or unsupported control characters. Image inputs are replayable staged JPEG/PNG artifacts with detected MIME, positive dimensions, bounded length, and SHA-256. Painting input may carry only safe optional public metadata: Painting UUID, title, author, dynasty, category, subject, school, style, composition, artistic conception, official generated text, and official music-scene description. It cannot carry internal IDs, user data, favorite state, paths, storage keys, or provider configuration.

Text results contain the exact operation/provider/modality, request correlation ID, and validated structured poem. Binary results contain the same neutral identifiers plus one `ProviderArtifact`, detected MIME, length, SHA-256, and image dimensions when applicable. They contain no raw request, response, model ID, provider URL, credential, reasoning, or public/local path.

## Transient artifacts

`ProviderArtifactStagingService` creates a private configured staging root lazily, rejects symlink aliases, uses random direct-child names, applies owner-only POSIX permissions where supported, streams through configured byte limits, hashes with SHA-256, validates signatures/content, and atomically moves only a fully validated file into final transient placement. A `ProviderArtifact` is the single owner of that contained file, exposes only a replayable stream and safe metadata, verifies availability before replay, and deletes idempotently on close.

Round 9 will stream a validated artifact into the existing generated MediaAsset boundary and close it. Round 8 never creates a MediaAsset or database row. Provider URLs and VMM source paths are never retained.

## Seedream 5 adapter

Production configuration accepts only the reviewed HTTPS Ark root `https://ark.cn-beijing.volces.com/api/v3`, without userinfo, query, fragment, explicit port, alternate host, private address, or arbitrary path. The adapter appends only `/images/generations`. The stable workflow code remains `seedream-5`; the actual reviewed Ark model ID is private `SEEDREAM_MODEL` configuration and is neither substituted nor exposed.

The current Seedream 5.0 Pro text request contains exactly one configured model, one bounded prompt, `response_format=url`, configured fixed size/watermark, and `stream=false`. It does not send `image`, `output_format`, `sequential_image_generation`, `sequential_image_generation_options`, tools, callbacks, or a multiple-image count. `AURALINK_SEEDREAM_OUTPUT_FORMAT` remains accepted configuration but does not control the downloaded URL result; validated JPEG and PNG outputs are both accepted. Text-to-painting places untrusted text inside explicit source delimiters and requires grounded Chinese-painting composition and brush/ink language without unrelated subjects, UI, text, logo, or invented real-artist signature.

The current Seedream 5.0 Pro image-to-image request contains exactly `model`, `prompt`, one `image` string, `response_format=url`, configured fixed `size`/`watermark`, and `stream=false`. It does not send `sequential_image_generation`, `sequential_image_generation_options`, `output_format`, an image array, tools, callbacks, webhooks, or a multiple-image count. The image is one internally generated, bounded, validated JPEG/PNG Base64 Data URL; it never exposes a caller URL, internal asset URL, storage key, or filesystem path. The fixed instruction preserves subject identity/count, composition, and spatial relationships while changing only the visual language.

A successful envelope must contain exactly one URL result. The URL is passed without authentication/cookies to the existing DNS-pinned, redirect-revalidating, deadline- and size-bounded remote fetcher. Downloaded JPEG/PNG is signature, MIME, dimension, pixel, terminal-marker, and active-polyglot validated before staging. Signed URLs are transient and never returned or persisted.

## Qwen creation transport

Creation calls do not use Painting Guide Service. The OpenAI-compatible transport accepts only reviewed Alibaba Model Studio regional HTTPS roots (`dashscope.aliyuncs.com` or `dashscope-intl.aliyuncs.com` with `/compatible-mode/v1`) and appends only `/chat/completions`. The model must match the private reviewed `qwen3-vl-plus` configuration boundary. Requests are Bearer-authenticated, non-streaming JSON mode with `enable_thinking=false`, no tools, no web search, no `max_tokens` override, and no reasoning request or exposure.

Painting-to-poem sends one internally encoded bounded image Data URL plus safe optional Painting metadata. Its exact version-1 result requires string `schemaVersion="1"`, a nullable or bounded Chinese `title`, exactly four distinct nonblank Chinese-dominant `lines`, and `text` equal to those normalized lines joined by newlines. Unknown or duplicate fields, numeric schema versions, three/five-line variants, Markdown, HTML, leading/trailing explanation, URLs, reasoning/prompt leakage, AI self-reference, and malformed/overlong output are rejected. The parser does not strip fences, extract an object from prose, repair text, ignore unknown fields, or accept an alternate schema. The product asks only for a short Chinese classical-style poem and does not claim validated regulated verse.

A rejected Qwen response can carry an immutable typed safe diagnostic while its error category remains `PROVIDER_INVALID_RESPONSE`. Stable stages distinguish HTTP envelope, choices, message, content, JSON syntax/structure, poem schema, and poem semantics; stable enum codes identify the exact failed rule. The associated response-shape record contains only bounded counts, lengths, allowlisted value-type tokens, and booleans. It cannot contain the title, lines, combined poem, raw body/content, prompt, image data, endpoint, model value, credential, path, or exception text. This diagnostic is an internal operator-evidence boundary only and is not mapped through a controller or public API.

`message.reasoning_content` is optional provider metadata, not poem output. Missing, `null`, empty, and ASCII- or Unicode-whitespace-only values are ignored without retaining their value, then strict `message.content` and four-line poem validation proceed. A nonblank textual value is rejected at `MESSAGE` with `QWEN_CONTENT_REASONING_MARKER`; a non-null non-text value is rejected at `MESSAGE` with `QWEN_REASONING_CONTENT_TYPE_INVALID`. Safe diagnostics may report only presence, a safe value type, and nonblank status—never the reasoning value. This MESSAGE control is separate from the CONTENT-stage reasoning-marker filter, which still rejects forbidden reasoning material in `message.content`.

## Composite poem-to-painting

`POEM_TO_PAINTING` remains one workflow transform. Qwen first interprets only the delimited, untrusted poem and returns exact version-1 JSON fields: `schemaVersion`, `subject`, `scene`, `composition`, `colorPalette`, `brushwork`, `artisticConception`, and `finalPrompt`. Every content field must be bounded Chinese text without Markdown/HTML, URL, provider metadata, reasoning, system-prompt leakage, tool instructions, or unsupported claims of historical authorship.

Only a validated plan can build the bounded Seedream Chinese-painting prompt. Qwen failure or invalid JSON stops before Seedream; Seedream is invoked exactly once after a valid plan. Both stages retain the same safe request correlation ID, hold only their own provider bulkhead permit, perform no whole-composite retry, and write no persistent state.

## Existing VMM boundary

The active `VMM/app.py` source is authoritative. Round 8 uses its `POST /api/generate_with_image` request with Base64 Data URL field `image` and inherited `duration=30`. Successful code returns `success`, `fileName`, `full_path`, and a message; the adapter ignores `full_path` completely and trusts only a strict direct-child `.wav` filename.

`PAINTING_MUSIC_SERVICE_URL` is the canonical new-workflow VMM service root because it already belongs to the reviewed provider configuration group. Legacy `AURALINK_VMM_URL` and `GenerationService` behavior remain unchanged. The URL must be a loopback/private literal root. `AURALINK_VMM_OUTPUT_DIR` supplies the matching controlled output root. The adapter rejects traversal and symlinks, requires real-path containment directly beneath that root, streams and removes the contained source output, and validates size, `RIFF`, `WAVE`, and declared RIFF length before returning a transient `audio/wav` artifact. It does not start VMM, inspect health during capability discovery, load a model, transcode, or alter duration/algorithm/checkpoints/conditioning.

Code inspection recorded the current VMM routes as `GET /health` and `POST /api/generate_with_image`. The health response includes `status` and `model_ready`. The generation route accepts `image` or legacy `imageUrl` and optional `duration`; the new adapter deliberately sends only `image` and fixed duration. Error responses remain upstream-internal and are never exposed.

## Configuration and readiness

Provider adapters default off with `AURALINK_CREATION_PROVIDERS_ENABLED=false`. Safe non-secret configuration controls staging, input/output byte bounds, text length, connect/read timeouts, provider concurrency, and fixed Seedream size/format/watermark. Credentials, base roots, and external model identifiers remain in untracked `backend/.env` through `SEEDREAM_*` and `QWEN_*`; `.env.example` contains blanks or safe defaults only.

Readiness states are `ADAPTER_IMPLEMENTED`, `FEATURE_DISABLED`, `CONFIGURATION_MISSING`, `CONFIGURATION_INVALID`, `INTERNAL_SERVICE_NOT_VALIDATED`, `READY_FOR_CONTROLLED_EXECUTION`, and `RESERVED_DISABLED`. Inspection validates only flags, presence, approved URL/model shape, and local bounds. It never performs a paid call, probes an external provider, starts VMM, reveals whether a particular secret value is valid, or exposes model/base URL/port/path in workflow capability JSON.

## Errors, concurrency, and retry

Stable internal errors are `PROVIDER_FEATURE_DISABLED`, `PROVIDER_CONFIGURATION_MISSING`, `PROVIDER_CONFIGURATION_INVALID`, `PROVIDER_UNAVAILABLE`, `PROVIDER_TIMEOUT`, `PROVIDER_RATE_LIMITED`, `PROVIDER_REJECTED`, `PROVIDER_INVALID_RESPONSE`, `PROVIDER_OUTPUT_INVALID`, `PROVIDER_CAPACITY_EXCEEDED`, and `PROVIDER_INTERNAL_CONTRACT_ERROR`. Safe messages contain no raw upstream body, endpoint, path, secret, or exception class. Optional typed response diagnostics do not change the category and are not globally exposed by exception handlers.

Immediate-fail semaphores allow two Seedream, four Qwen creation, and one VMM call by default. There is no executor, waiting queue, database lock, Redis, or unbounded thread creation. Permits are released for every success/failure.

No request is retried after submission may have begun because that can duplicate paid image/Qwen work or VMM generation. The current implementation performs zero automatic application retries; the dedicated HTTP clients also disable transport retries and redirects. Workflow/job retry policy remains a Round 9 concern.

## Deferred validation and execution

Round 8 tests use only loopback HTTP fixtures, synthetic JPEG/PNG/WAV bytes, temporary staging, and temporary Flyway SQLite. They do not use real keys or start provider/model processes. Controlled real Seedream, Qwen creation, and VMM runtime contract validation belongs to Round 8.1. Resolving authorized Creation inputs, persisting snapshots/Creation/CreationStep/MediaAsset, asynchronous orchestration, and workflow-level retry belong to Round 9.
