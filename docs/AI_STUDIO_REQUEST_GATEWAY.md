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
4. Preserve opaque wire slots and attachment parts when changing known fields.
5. Do not remove the existing R18 production path until device evidence proves capture and replay parity.
6. A replay requiring rewritten content must fail closed if a fresh proof cannot be generated.
7. File/video upload remains on the existing trusted file-chooser/upload path during the first migration stages.
8. `BidiGenerateContent` is not handled by this gateway. The existing Live transport remains separate.
9. Video/STT production code must not call `replayText`; their gateway integration remains passive until their captured wire shapes are understood.

## Added components

### `AiStudioWireCodec`

A conservative Kotlin codec for the observed AI Studio top-level wire array. It validates known slots, reports a structural fingerprint, preserves unknown slots and attachment parts, and rewrites only explicitly supported semantic fields. Unsupported shapes fail closed.

### `AiStudioRequestGatewayScript`

Runs inside the authenticated AI Studio WebView. It:

- hooks `fetch`/XHR without exporting credential values to native code;
- captures valid `GenerateContent` request templates in memory, keyed by model;
- deliberately ignores `BidiGenerateContent`;
- records only safe template metadata to diagnostics;
- discovers/hooks the AI Studio snapshot/proof function when available;
- can generate a fresh proof for a rewritten simple-text request;
- can replay through XHR with `withCredentials=true`;
- keeps raw replay response text inside WebView and exposes normalized output/progress through a small result API;
- rejects unsupported body shapes and media-bearing templates on the text replay path.

The gateway script is appended to `AiStudioWebSessionLabScripts.DOCUMENT_START`. Because that document-start hook is registered before `AiStudioWebSessionR11RequestFix`, the gateway wraps the lower XHR layer first. R11 can then rewrite the request and call downward, allowing the gateway to observe the effective body that is actually sent instead of an earlier pre-rewrite body.

### `AiStudioRequestGateway`

Kotlin facade over the WebView gateway. It exposes safe status metadata, text-laboratory replay/polling, partial output, cancellation and template invalidation.

The JavaScript layer owns authoritative per-model template checks because it owns the complete template cache. Native status intentionally exposes only diagnostic metadata for the most recently selected template and therefore must not be used to decide whether another model is cached.

### `AiStudioIncrementalJsonArrayParser`

A fail-closed Kotlin parser for incremental AI Studio array-framed stream data. It handles:

- network fragments that split a JSON string;
- a fragmented XSSI prefix;
- escaped quotes/backslashes across fragments;
- brackets occurring inside quoted text;
- multiple frames inside the outer stream wrapper;
- malformed nesting and oversized active frames by resetting with an explicit error.

This parser is **not connected to production response handling yet**. The default frame depth is based on the currently observed GenerateContent shape and must be verified against sanitized device fixtures before replacing the existing response extractor.

## Current integration state

The gateway is available at document start for `AiStudioWebSessionExecutor`, and video/file-transcription clients also keep an idempotent current-page install/status handle for passive diagnostics.

At this stage **the existing production submit path still sends video and STT requests**. The new gateway observes/captures them. No production video or STT path calls `replayText`.

Expected diagnostics after a successful GenerateContent request include:

- `JS_REQUEST_GATEWAY_INSTALLED`
- `JS_REQUEST_GATEWAY_TEMPLATE_CAPTURED`
- `REQUEST_GATEWAY_STATUS ... templateReady=true`
- `proofReady=true` when the snapshot service was successfully observed

No raw body, header value, cookie or snapshot token should appear in those logs.

## Migration stages

### G0 - Baseline and passive capture

Status: implemented on this branch.

- Keep R18.30 behavior unchanged.
- Install capture at document start before the later R11 request rewriter.
- Capture the effective real request template.
- Validate structural fingerprint.
- Observe proof readiness.
- Add codec/unit/source-safety tests.

### G1 - Safe text replay laboratory

Status: foundation implemented, not used by production video/STT paths.

- `AiStudioRequestGateway.replayText` requires a nonblank requested model and proof readiness.
- Browser-side dispatch requires an exact template for that model.
- Browser-side dispatch requires an explicitly marked text-only replay.
- Captured template must contain exactly one simple user text part and no media/non-text parts.
- R11 model conflict is rejected while that compatibility layer remains installed.
- No silent protocol fallback sends a second network request.

Device verification is still required before treating this as production transport.

### G2 - Video submit migration

Status: pending device evidence.

- Keep current video upload/readiness logic.
- Inspect the captured video request shape and attachment slots from real-device diagnostics.
- Extend the codec only from that evidence.
- After attachment is ready, replace only Run/native-tap submission with gateway replay.
- Preserve the attachment wire part while replacing the final user prompt.
- Keep the old submit path only as a temporary branch-local comparison path until parity is proven.

### G3 - File transcription submit migration

Status: pending device evidence.

- Keep the dedicated STT page and upload path.
- Determine from captured device requests whether the file-only STT request contains a replaceable text part, no text part, or dedicated transcription configuration.
- Extend the codec only from captured evidence; do not infer the schema from another repository.
- Replace auto-Run only after the captured STT schema is understood.

### G4 - Structural stream parser

Status: parser and synthetic fragmentation tests implemented; production integration pending fixtures.

- `AiStudioIncrementalJsonArrayParser` exists independently of the current response path.
- Tests cover fragmented strings, fragmented XSSI, escaping, quoted brackets, multiple frames and fail-closed recovery.
- Next requirement is sanitized stream fixtures captured from the app itself.
- Only after fixture parity should the regex-only response interpretation be replaced.

### G5 - Remove obsolete UI submission layers

Status: not started.

Only after device parity is established:

- remove native-tap submit retries that replay no longer needs;
- remove duplicate request rewrite/response extraction layers;
- keep UI automation only for operations that still require trusted browser interaction, notably file selection/upload if no safe protocol equivalent is established.

## Device evidence required before G2/G3

For video and STT separately capture safe metadata for:

- template model;
- structural fingerprint;
- body length;
- simple-text/media safety classification;
- whether proof function is detected and proof is ready;
- HTTP status of the production request;
- response/model-text lengths;
- whether attachment readiness completed before GenerateContent.

If `REQUEST_GATEWAY_CAPTURE_REJECTED` appears, do not loosen validation blindly. Inspect that exact structural fingerprint and the sanitized structural diagnostics first, then update the codec only from the real request shape.
