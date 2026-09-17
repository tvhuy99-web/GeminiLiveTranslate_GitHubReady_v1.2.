# R20 temporary forensic diagnostics

This branch intentionally enables very high-volume diagnostics for the AI Studio `gemini-3.8-live` screen-description route only.

Captured evidence includes:

- XMLHttpRequest open/send/abort lifecycle with request IDs and JavaScript call stacks.
- Request headers with credential-like values redacted.
- Request body chunks up to 512 KiB per body.
- Streaming XHR response deltas, response headers, readyState/status transitions, error/timeout/abort/loadend events.
- fetch request/response headers and bodies, rejection stacks, and AbortController calls.
- console output, window errors, unhandled promise rejections, and page lifecycle events.
- getUserMedia/getDisplayMedia calls, MediaStream/MediaStreamTrack settings and lifecycle, and track stop callers.
- resource timing for live/WebChannel/generative-language traffic.
- once-per-second snapshots of R14/R16/R17/R19 state when it changes.

The existing AppLogRepository redaction layer remains active for API keys, authorization/cookie headers, bearer tokens, and common session/CSRF identifiers. Other diagnostic content can include personal page/session data by design.

Temporary log retention is expanded to 30,000 in-memory entries and up to eight 16 MiB rotating log files so early transport evidence is not overwritten during a long diagnostic run.

After the root cause is isolated, remove R20 injection, delete the R20 source file and this document, and restore normal AppLogRepository limits.