# ROUND 8.1 controlled creation-provider validation

This procedure validates the internal provider adapters prepared in ROUND 8. It is an operator-only audit path, not a Creation API, workflow executor, health endpoint, or product media pipeline. It never writes `Creation`, `CreationStep`, `MediaAsset`, or `generation_logs` rows.

## Validation split

- ROUND 8.1A builds and tests the guarded coordinator, non-web Java CLI, private evidence format, Mock harness, and VMM static checks. It performs no real provider operation and loads no VMM model.
- ROUND 8.1B runs four separately confirmed external-provider validations: text-to-painting, image-to-painting, poem-to-painting, and painting-to-poem.
- ROUND 8.1C performs VMM static preflight, starts only an owned loopback VMM process when READY, runs one painting-to-music inference, and stops that process.

External paid validation and VMM model startup are intentionally separate. There is no paid `validate-all` mode and no automatic generation retry.

## Server-local and reviewed-commit boundary

Commands must run from the real server-local checkout:

```text
/root/autodl-tmp/auralink
```

The coordinator rejects SSHFS/FUSE, symlink aliases, any other working directory, a dirty worktree, a missing ignored `backend/.env`, a missing packaged JAR, and a nonmatching commit. The operator supplies the full 40-character reviewed commit in `AURALINK_ROUND81_EXPECTED_COMMIT`. Dry runs and live validations apply the same root, filesystem, commit, clean-tree, configuration, input, database, port, and non-mutating exclusive-process guards.

The packaged entry point is the production Boot JAR invoked with `PropertiesLauncher` and `loader.main=com.auralink.ops.round81.Round81ProviderValidationCommand`. Its Spring context uses `WebApplicationType.NONE` and an explicit provider-only bean graph. It contains no servlet server, controller, security chain, DataSource, JPA, Flyway, repository, catalog runner, or Guide client.

## Configuration without secret disclosure

Provider credentials and private deployment configuration remain only in untracked `backend/.env`; process environment values take precedence. The tool parses configuration without printing values. Safe preflight evidence is limited to boolean presence, provider family, approved host class/region, loopback/output-root configuration booleans, configuration readiness, and a one-way configuration identity fingerprint that excludes API-key bytes.

Required existing provider variables are:

- Seedream: `SEEDREAM_API_KEY`, reviewed `SEEDREAM_BASE_URL`, and `SEEDREAM_MODEL`.
- Qwen: `QWEN_API_KEY`, reviewed regional `QWEN_BASE_URL`, and `QWEN_MODEL`.
- VMM: loopback `PAINTING_MUSIC_SERVICE_URL` and safe `AURALINK_VMM_OUTPUT_DIR`.

The Seedream check requires the exact official Beijing Ark HTTPS API root already enforced by the adapter. The Qwen check requires one of the adapter's reviewed Alibaba Model Studio regional HTTPS roots. Neither tool infers region from a key, substitutes a model, switches region, calls a health endpoint, or prints a host URL/model value. API keys, Authorization, signed result URLs, request/response bodies, Base64, prompts, reasoning, and private paths are excluded from evidence.

## Fixed validation inputs

No validation command accepts a prompt, poem, image path, provider URL, model, or API key.

- `TEXT_TO_PAINTING` uses a committed compact Chinese spring-rain landscape description requiring distant mountains, a returning boat, ink-wash composition, and no text/logo.
- `POEM_TO_PAINTING` uses the fixed public-domain lines `空山新雨后，天气晚来秋。明月松间照，清泉石上流。`.
- Image-based modes use Painting UUID `00074dee-e790-4cf3-a1d9-1e2e784364fb` (`共饮一江水`, 叶浅予, 现代).

Painting resolution opens SQLite with `mode=ro&immutable=1`, verifies production hashes/counts/integrity, requires an ACTIVE gallery-visible Painting linked to an ACTIVE PUBLIC `CATALOG_REFERENCE` Painting image, enforces catalog-root real-path containment and no symlink, validates JPEG/PNG signature and dimensions, and checks size/SHA-256 against catalog metadata. Only a private 0600 input copy and safe metadata enter a live run; adapters receive no entity, internal ID, storage key, or source path.

## Per-operation call budget and confirmation

| Operation | Confirmation value | Seedream | Qwen | VMM |
| --- | --- | ---: | ---: | ---: |
| `TEXT_TO_PAINTING` | `VALIDATE_ONE_LIVE_TEXT_TO_PAINTING` | 1 | 0 | 0 |
| `IMAGE_TO_PAINTING` | `VALIDATE_ONE_LIVE_IMAGE_TO_PAINTING` | 1 | 0 | 0 |
| `POEM_TO_PAINTING` | `VALIDATE_ONE_LIVE_POEM_TO_PAINTING` | 1 | 1 | 0 |
| `PAINTING_TO_POEM` | `VALIDATE_ONE_LIVE_PAINTING_TO_POEM` | 0 | 1 | 0 |
| `PAINTING_TO_MUSIC` | `VALIDATE_ONE_LIVE_PAINTING_TO_MUSIC` | 0 | 0 | 1 |

The exact value is supplied in `AURALINK_ROUND81_CONFIRM`. Generic `YES`, `CONFIRM`, and cross-operation values are refused. The Java transport ledger records one execution entry and classifies only the three fixed adapter endpoint paths. Evidence requires exact counts, `retryHandlerInvoked=false`, and `outputCount=1`.

## Dry-run and live command shape

After building the reviewed JAR offline, set only the reviewed commit and run one mode:

```bash
cd /root/autodl-tmp/auralink
export AURALINK_ROUND81_EXPECTED_COMMIT='<full-reviewed-40-character-commit>'
backend/scripts/validate-round8-live-providers.sh --preflight-all
backend/scripts/validate-round8-live-providers.sh --dry-run --operation=text-to-painting
```

Dry run checks all prerequisites and prints `DRY_RUN_ZERO_MUTATION` and `DRY_RUN_OK`; it creates no paid request, private run, provider artifact, database record, or service process. Each live command additionally requires its exact confirmation, for example:

```bash
export AURALINK_ROUND81_CONFIRM='VALIDATE_ONE_LIVE_TEXT_TO_PAINTING'
backend/scripts/validate-round8-live-providers.sh --validate --operation=text-to-painting
unset AURALINK_ROUND81_CONFIRM
```

Run each operation separately. Review likely provider cost and the deterministic source before confirming; promotional pricing is deliberately not encoded here.

## Private evidence and duplicate-call avoidance

Live evidence is created under `/root/auralink_provider_validation_runs/<UTC timestamp>-<operation>/`. The root and run directory are 0700; retained files are 0600. A successful run retains only:

- sanitized preflight, exact-call-count, execution, cleanup, and final validation manifests;
- operation/provider/commit and deterministic input hash;
- configuration fingerprint excluding key bytes;
- final structurally validated image, poem JSON, or WAV;
- MIME, bytes, SHA-256, image dimensions or WAV structural metadata;
- fixed-millisecond UTC validation time and elapsed milliseconds;
- an incomplete operator-review checklist.

It never retains `.env`, a key/header, signed URL, raw request/response, Base64, prompt, Qwen plan/reasoning, private model value, provider output path, user credential, or database entity. Successful source-image copies are removed after the final output is verified. Results are private audit artifacts and are never exposed by API or inserted into `media_assets`.

Before a live call, the coordinator scans exact successful manifests. It prints `ALREADY_VALIDATED_AND_HEALTHY` and makes no call only when commit, operation, provider code, deterministic input hash, configuration fingerprint, output hash, current structural revalidation, and successful cleanup all match. It refuses multiple matching manifests as ambiguous and never selects a run merely because it is newest.

## Provider result boundary

Seedream modes invoke the adapter once, require exactly one JPEG/PNG artifact, verify signature/MIME/dimensions/bytes/SHA-256, retain one private image, close the transient artifact, and require an empty staging root. Composite poem-to-painting retains neither the Qwen plan nor prompt; invalid Qwen output stops before Seedream. Painting-to-poem retains only string schema version `"1"`, a nullable or bounded Chinese title, exactly four distinct nonblank Chinese-dominant lines, and text equal to those lines joined by newlines. The strict contract remains unchanged: numeric version `1`, alternate fields, three/five lines, fenced/prose-wrapped JSON, unknown fields, automatic text repair, and permissive normalization remain rejected.

Every successful result is marked `STRUCTURALLY_VALID` and `OPERATOR_REVIEW_REQUIRED`. Structural checks do not establish artistic or semantic quality; the operator must review generated images and poem grounding.

## Failure cleanup

On failure, Java closes any input/output `ProviderArtifact`, removes partial staging files, verifies staging emptiness, then writes a sanitized failure manifest. A provider rejection may retain only the numeric HTTP status, a bounded safe provider code token, a hashed upstream request ID, provider family, operation/provider code, local call counts, no-retry state, and cleanup state. For a Qwen `PROVIDER_INVALID_RESPONSE`, it may additionally retain one typed validation-stage token, one typed validation-code token, and an allowlisted response-shape object containing only bounded structural booleans, counts, lengths, and value-type tokens. Unavailable fields are omitted. The terminal prints only the stable provider category and, when present, the validation stage/code; it does not print the response-shape object.

Neither the exception diagnostic nor `failure.json` retains the poem title, any poem line, combined text, raw `message.content`, raw HTTP/provider body, request JSON, prompt, reasoning, Base64/image bytes, Authorization/API key, model/base/endpoint value, signed URL, path/storage key, exception class, or stack trace. These diagnostics are private 0600 operator evidence, never public API data. The shell/state guard removes only direct children of its dedicated controlled staging root, compares the complete read-only database snapshot, and retains sanitized diagnostics. Mock processes are owned and stopped by the harness. A failed VMM start or validation triggers bounded shutdown only when its PID and kernel process-start identity match the private launcher state; an unowned listener is reported and never killed. Previous successful evidence is not deleted and there is no automatic provider retry.

## ROUND 8.1A.4 painting-to-poem status

The first controlled `PAINTING_TO_POEM` call reached Qwen exactly once and returned through the provider transport, but the strict local result contract rejected the response as `PROVIDER_INVALID_RESPONSE`. Cleanup completed, staging was empty, and the database was unchanged. That pre-diagnostic run did not retain the structural rejection reason. ROUND 8.1A.4 adds the safe typed diagnostic path and validates it only with loopback Mock responses; it performs no real provider call and does not claim the painting-to-poem chain is successful.

After this reviewed commit is present server-locally, exactly one newly confirmed `PAINTING_TO_POEM` retry is required to capture either a successful private poem for operator review or the exact safe structural mismatch. Compatibility changes remain deferred until that evidence exists. In particular, do not accept numeric schema versions, change the line count, strip Markdown/prose, repair text, rename fields, ignore unknown fields, weaken Chinese dominance, alter the prompt/model/`response_format`/`enable_thinking`, or add retry/repair calls based only on the earlier generic failure.

## ROUND 8.1A.5 optional Qwen reasoning field handling

`reasoning_content` is optional message metadata and is never retained. Missing, `null`, empty, and ASCII- or Unicode-whitespace-only values are ignored so strict `message.content` and poem validation can proceed. Nonblank textual reasoning remains a controlled `MESSAGE` rejection with `QWEN_CONTENT_REASONING_MARKER`; a present non-null non-text value is a controlled `MESSAGE` rejection with `QWEN_REASONING_CONTENT_TYPE_INVALID`. Safe failure evidence may include only `reasoningContentPresent`, `reasoningContentType`, and (for textual values) `reasoningContentNonblank`; it never includes the field value. This boundary is distinct from the existing CONTENT-stage body filter, which continues to reject reasoning markers, Markdown, HTML, explanations, and all other forbidden material in final `message.content`.

After this commit is reviewed and installed server-locally, exactly one newly confirmed `PAINTING_TO_POEM` live retry is required. This compatibility correction does not claim real-provider success.

## VMM static and live boundary

`start-vmm-service.sh preflight` performs no Python model import. It checks the reviewed Python interpreter, `VMM/app.py`, local Audiocraft and CLIP package-source resolution, CLIP cache, text-encoder/T5 files, MusicGen files, `final_model.pth`, configured output root, loopback service configuration, and bounded `nvidia-smi -L`. Fixed failures include `VMM_PYTHON_MISSING`, `VMM_PACKAGE_PATH_INVALID`, `VMM_CLIP_ASSET_MISSING`, `VMM_TEXT_ENCODER_ASSET_MISSING`, `VMM_MUSICGEN_ASSET_MISSING`, `VMM_CHECKPOINT_MISSING`, `VMM_OUTPUT_ROOT_INVALID`, `VMM_GPU_NOT_VISIBLE`, and `VMM_CONFIGURATION_MISSING`.

Only a READY preflight may start the owned service. The launcher uses the inherited Python 3.9 environment and existing local source/weights, sets Hugging Face/Transformers offline mode, does not inherit provider keys or proxy configuration, binds `127.0.0.1:5001`, records a PID plus kernel process-start identity, and waits for `model_ready=true`. It does not install/download, edit VMM code, or alter model, checkpoint, duration, conditioning, or generation quality.

ROUND 8.1C then runs one separately confirmed `PAINTING_TO_MUSIC` adapter call. The output must be one bounded RIFF/WAVE artifact with SHA-256. It is marked `STRUCTURALLY_VALID` and `OPERATOR_AUDIO_REVIEW_REQUIRED`; listening review is required and no semantic quality claim is automatic. The owned launcher must be stopped afterward and port 5001 verified released.

## ROUND 9 handoff

These tools validate adapters only. Creation ownership/source resolution, `WorkflowSnapshot` copy, Creation/CreationStep persistence, generated MediaAsset storage, asynchronous orchestration, and workflow retry remain ROUND 9 work.
