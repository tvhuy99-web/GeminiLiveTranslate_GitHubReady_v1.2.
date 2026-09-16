# AI Studio Request Gateway v1

This branch introduces a conservative, parallel request gateway for the authenticated AI Studio WebView path.

## Goals

1. Capture a real `GenerateContent` request template from the authenticated WebView.
2. Decode and rewrite the reverse-engineered wire array only when its shape is recognized.
3. Replay a caller-supplied body inside the same WebView so cookies and browser session state remain browser-owned.
4. Parse streaming array responses incrementally instead of depending only on response-text regexes.
5. Keep the existing Live Audio, video upload, STT upload, R18 production behavior, and native submit fallbacks untouched until the new path is verified on a device.

## New components

- `AiStudioWireCodec`: conservative wire-array inspection and rewrite. Unknown shapes fail closed.
- `AiStudioIncrementalStreamParser`: incremental parser for fragmented array streams and fragmented XSSI prefixes.
- `AiStudioRequestGatewayScript`: passive document-start hook that captures request templates and exposes replay primitives inside the WebView.
- `AiStudioRequestGateway`: Android wrapper for installing the hook, reading status, obtaining a captured body, starting replay, polling replay events, and aborting replay.
- Unit tests for codec shape validation, mutation isolation, snapshot replacement, fragmented streaming, and parser reset.

## Safety boundary for v1

The gateway is not yet connected to the production video/STT execution path. That is intentional. A replay body can require a content-bound BotGuard/snapshot proof, and the exact wire layout must be learned from requests captured by this app on a real AI Studio session before production routing is switched.

The first device-verification sequence should be:

1. Inject the gateway into the same authenticated WebView at document start.
2. Run one existing successful text/video/STT action through the current path.
3. Confirm `templateCount > 0` and record only sanitized shape metadata.
4. Compare the captured body shape with `AiStudioWireCodec.inspect`.
5. Replay an unchanged, non-attachment request first.
6. Only after unchanged replay succeeds, implement content-bound proof refresh and controlled body rewrite.

## Explicit non-goals

- No Python runtime.
- No bundled Chromium/CDP process.
- No raw cookie export.
- No replacement of the existing Live Audio WebSocket path.
- No deletion of R11-R18 production fallbacks before device verification proves an equivalent gateway path.
