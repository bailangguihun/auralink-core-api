# Managed VMM dispatch contract

This opt-in Core change supports the separately reviewed R25 managed VMM role. It does not start VMM, import its model, alter Guide/Critic, or perform health-triggered generation. This is a functional upgrade, not equivalent JAR repackaging. A future release needs a reviewed Core-only functional-upgrade binding for this exact artifact/configuration; never use the legacy equivalent-payload writer. The currently installed Gallery follow-up binding is single-candidate and does not already authorize this new JAR.

## Compatibility and private configuration

`auralink.vmm-managed.enabled` defaults to `false`. With that default the previous configuration-only VMM readiness and unauthenticated legacy adapter behavior remain unchanged. No token file, managed status endpoint or new schema is required. A later activation must explicitly enable the managed gate; enabling the listener alone is not sufficient. Guide, Critic, Assistant and other Creation providers are unaffected.

| Property | Environment reference | Purpose |
| --- | --- | --- |
| `auralink.vmm-managed.enabled` | `AURALINK_VMM_MANAGED_ENABLED` | Opt in to fail-closed managed readiness and dispatch checks |
| `auralink.vmm-managed.token-file` | `AURALINK_VMM_MANAGED_TOKEN_FILE` | Absolute private token file; no inline token or browser credential |
| `auralink.vmm-managed.acceptance-owner-id` | `AURALINK_VMM_ACCEPTANCE_OWNER_ID` | Trusted numeric account identity for the bounded acceptance window |
| `auralink.vmm-managed.acceptance-source-painting-id` | `AURALINK_VMM_ACCEPTANCE_SOURCE_PAINTING_ID` | Resolved internal catalog painting identity, not browser-provided metadata |
| `auralink.vmm-managed.acceptance-duration-seconds` | `AURALINK_VMM_ACCEPTANCE_DURATION_SECONDS` | Exact 3–30-second acceptance duration |

The existing `auralink.providers.painting-music.base-url` and `output-root` bindings remain authoritative; only an internal/private root URL is accepted. Existing Spring property precedence applies. Do not merge environment files or alter Guide/Critic model variables. Use a random 72-character URL-safe private token at mode 0600, a private traversable directory and the reviewed service ownership. Core additionally accepts 0640 where the independently reviewed service contract permits it; the current managed wrapper requires 0600. Neither side accepts symlink token files. The Core acceptance tuple and service permit must agree; missing or mismatched values close ACCEPTANCE admission.

## Non-generating status and final admission

`GET /internal/vmm/status`, header `X-ArtLIVE-VMM-Token`, must return the exact contract marker `artlive-vmm-managed-v1`, `mode` (`CLOSED`, `ACCEPTANCE`, `OPEN`) and JSON booleans `model_ready`, `dispatch_permitted`, `in_flight`, `uncertain`. Core permits capability only when ready, dispatch permitted, not busy, not uncertain and mode is ACCEPTANCE or OPEN. Configuration/output-directory availability alone no longer proves managed readiness. Absent listener, non-200/redirect, malformed/oversized/incompatible status and private-token errors fail closed. Responses are bounded to 4096 bytes. The status connection/read timeouts are each one second, with a two-second read deadline checked between reads (a final blocked read may add up to one second). No status result loads models.

The normal authenticated Creation submission resolves the actual owner and source before `VmmManagedAdmission`. In ACCEPTANCE, other users, other sources and other durations fail before inserting a new creation. The worker checks live readiness again and the adapter checks immediately before its one POST. These checks are not a replacement for the wrapper's atomic durable permit: a state change between a GET and POST must still be rejected by the service. The wrapper binds owner, source painting, exact image SHA-256 and duration and consumes the first qualifying request UUID. Simultaneous submissions cannot extend that one-request permit. A configured test owner ID is not a public allowlist policy.

## Core → VMM contract

Generation remains `POST /api/generate_with_image`, JSON `{ "image": "data:image/...;base64,...", "duration": 10 }`. Input JPEG/PNG validation, integer bounds 3–30, default 10, the existing concurrency setting and all generation connection/read timeouts are unchanged. No SDK/HTTP retries or redirect following are enabled.

Managed requests carry `X-ArtLIVE-VMM-Token`, `X-ArtLIVE-VMM-Request-ID`, `X-ArtLIVE-VMM-Owner-Id`, `X-ArtLIVE-VMM-Creation-Id`, `X-ArtLIVE-VMM-Step-Id`, `X-ArtLIVE-VMM-Retry-Version`, `X-ArtLIVE-VMM-Source-Painting-Id`. These identities are built from the claimed database record, never decoded from browser JSON. The existing `X-Auralink-Request-Id` correlation header remains. Internal numeric IDs stay between Core and the private service and are not public artwork links.

The existing Core transaction persists `SEND_STARTED` and a fresh random UUID in both the step and dispatch attempt before calling the provider. The managed success response must echo that UUID as `request_id`, with `success: true` and a safe leaf `fileName`. Existing output-root containment, no symlink, bounded actual WAV byte validation, private media persistence and duration matching remain in force. No arbitrary path or response URL is fetched.

Timeout, transport loss, malformed/identity-mismatched completion after POST are `PROVIDER_OUTCOME_UNCERTAIN`. A received refusal is still a refusal, but Core's pre-existing `SEND_STARTED` is conservatively not refunded or erased. The existing retry/recovery policy refuses ambiguous dispatches rather than resending them. The wrapper must preserve its own reservation and uncertain state across restart. Neither recovery, readiness nor operator process restart is authority to replay music generation. Stopping Core polling is not cancellation of the model.

## Activation and recovery dependency

This code remains disabled until a reviewed artifact/configuration transition binds the managed wrapper, token reference and tuple. Before opening a gate, inspect existing queued/running music tasks and unfinished dispatches read-only: do not assume a single manual click is the only caller. Keep mode CLOSED through service startup and model readiness. For separately authorized acceptance, provision exactly one matching permit, submit once through the existing authenticated Core workflow, then close dispatch on every outcome. An uncertain result consumes the request and blocks further generation pending manual diagnosis.

Publish compatible Core before the managed listener is opened. Prepare and review the next Core-only artifact/configuration binding against the actual installed controller, preserving its backup and transaction recovery path; the existing one-time Gallery follow-up cannot be replayed as authorization for this JAR. On failure close dispatch first and preserve all request records, unknown outcomes and media; application rollback must not restore an older database or remove uncertainty. Returning Core to legacy configuration while leaving the managed service open is not a supported rollback.

No migration is added. Durable initial-submission idempotency across refresh/tabs, catalog eligibility/current-revision and historical snapshot consumption are independent follow-ups. No VMM production launch, GPU readiness, real generation or playback acceptance is claimed by CPU tests.

## Focused validation

`mvn -B -ntp -Dtest=VmmManagedContractTest,VmmManagedConfigurationTest test` exercises actual loopback HTTP through the production RestClient/adapter: disabled compatibility; closed/not-ready/busy/uncertain state; authenticated submission owner/source/duration restriction; token permissions; exact context headers; synthetic PNG and actual 3/10/30-second RIFF/WAV staging and transient-output cleanup; invalid duration; unsafe filenames; response identity mismatch; timeout/malformed response and no automatic retry; existing interrupted `SEND_STARTED` recovery remains ambiguous. The fixture is test-only and is not a production backend option.
