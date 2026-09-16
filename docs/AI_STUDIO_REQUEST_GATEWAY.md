# AI Studio Request Gateway

Branch: `refactor/aistudio-request-gateway`
Baseline: `main` at `019099f4f37b3243af63cd4f4e6702e28c9ca514` (R18.30)

## Goal

Move non-Live AI Studio GenerateContent work away from fragile DOM/Run-button automation toward a browser-context transport:

`capture real request -> validate/decode wire -> rewrite known fields -> refresh proof -> replay XHR -> parse stream`

The Gemini Live audio path is explicitly out of scope for this migration and must remain unchanged.

## Safety rules

1. Never guess an unknown wire shape. Reject it and log only the structural fingerprint.
2. Raw request bodies, header values, cookies, and Google session credentials stay inside the WebView.
3. Never log BotGuard/snapshot values.
4. Preserve opaque wire slots and attachment parts when changing prompt/model.
5. Do not remove the existing R18 production path until device evidence proves capture and replay parity.
6. A replay requiring a new prompt must fail closed if a fresh proof cannot be generated.
7. File/video upload remains on the existing trusted file-chooser/upload path during the first migration stages.

## Added components

### `AiStudioWireCodec`

A conservative Kotlin codec for the observed AI Studio top-level wire array. It validates the known slots, reports a structural fingerprint, preserves unknown slots, and can rewrite model, the final user text part, and snapshot without deleting attachment/file parts.

### `AiStudioRequestGatewayScript`

Runs inside the authenticated AI Studio WebView. It:

- hooks fetch/XHR without exporting credential values to native code;
- captures valid GenerateContent request templates in memory;
- records only safe template metadata to diagnostics;
- discovers/hooks the AI Studio snapshot/proof function when available;
- can generate a fresh proof for a rewritten prompt;
- can replay a captured request through XHR with `withCredentials=true`;
- keeps full replay response text in WebView and exposes normalized model text/progress through a small result API;
- rejects unsupported body shapes.

### `AiStudioRequestGateway`

Kotlin facade over the WebView gateway. It installs the browser hook, exposes safe status metadata, supports replay/polling, partial output, cancellation, and template invalidation.

## Current integration stage

The gateway is installed before GenerateContent in:

- `AiStudioFileTranscribeClient`
- `AiStudioVideoDescriptionClient`

At this stage **the existing production submit path still sends the real request**. The new gateway observes/captures it and reports metadata after generation. This deliberately gives device evidence before replay is allowed to replace a working path.

Expected diagnostics after a successful request include:

- `JS_REQUEST_GATEWAY_INSTALLED`
- `JS_REQUEST_GATEWAY_TEMPLATE_CAPTURED`
- `REQUEST_GATEWAY_STATUS ... templateReady=true`
- `proofReady=true` when the snapshot service was successfully observed

No raw body, header values, cookie, or snapshot token should appear in those logs.

## Migration stages

### G0 - Baseline and passive capture

Status: implemented on this branch.

- Keep R18.30 behavior unchanged.
- Capture real request template.
- Validate structural fingerprint.
- Observe proof readiness.
- Add codec unit tests.

### G1 - Replay laboratory path

- Use `AiStudioRequestGateway.replayText` only from an explicit experimental path.
- Require the requested replay model to match the currently selected AI Studio model while R11 model rewriting remains installed.
- Compare HTTP status, model text, completion detection and latency against the UI-generated request.
- Do not fall back silently from a replay protocol error to another network request; surface the exact gateway phase in diagnostics.

### G2 - Video submit migration

- Keep current video upload/readiness logic.
- After attachment is ready, replace only the Run/native-tap submission with gateway replay.
- Preserve the attachment wire part while replacing the final user prompt.
- Keep the old submit path available only as a temporary branch-local comparison path until parity is proven.

### G3 - File transcription submit migration

- Keep the dedicated STT page and upload path.
- Determine from captured device requests whether the file-only STT request contains a replaceable text part, no text part, or a dedicated transcription configuration.
- Extend the codec only from captured evidence; do not infer the schema from another repository.
- Replace auto-Run only after the captured STT schema is understood.

### G4 - Structural stream parser

- Replace regex-only response interpretation with a stateful incremental parser based on actual captured AI Studio stream chunks.
- Preserve partial output behavior.
- Add fixtures for fragmented chunks and XSSI preamble.

### G5 - Remove obsolete UI submission layers

Only after device parity is established:

- remove native-tap submit retries that the replay path no longer needs;
- remove duplicate request rewrite/response extraction layers;
- keep UI automation only for operations that still require trusted browser UI interaction, notably file selection/upload if no safe protocol equivalent is established.

## Device evidence required before G2/G3

For video and STT separately capture:

- template model;
- structural fingerprint;
- body length;
- whether proof function is detected and proof is ready;
- HTTP status of the production request;
- response text/model text lengths;
- whether attachment readiness completed before GenerateContent.

If `REQUEST_GATEWAY_CAPTURE_REJECTED` appears, do not loosen validation blindly. Inspect that exact structural fingerprint first and update the codec only from the real request shape.
