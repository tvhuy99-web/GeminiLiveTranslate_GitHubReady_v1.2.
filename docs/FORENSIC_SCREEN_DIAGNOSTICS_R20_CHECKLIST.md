# R20 capture checklist

For a clean capture:

1. Clear the existing diagnostic log before reproducing.
2. Keep log level at Debug and file logging enabled.
3. Start only the real-time screen-description mode that uses `gemini-3.8-live`.
4. Reproduce until AI Studio shows the failure once. Extra retries are unnecessary unless the first attempt never reaches Start Live.
5. Export the diagnostic bundle immediately after the failure.

Expected markers for a useful forensic trace include `R19_FORENSIC_INSTALL`, `R19_FORENSIC_XHR_OPEN`, `R19_FORENSIC_XHR_SEND`, request-body chunks, XHR event/status transitions, and either response deltas or an explicit abort/reject/error chain. Media and state markers should appear alongside the transport timeline.
