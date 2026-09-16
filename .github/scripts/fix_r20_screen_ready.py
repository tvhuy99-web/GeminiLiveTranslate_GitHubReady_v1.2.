from pathlib import Path

p = Path('app/src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt')
s = p.read_text()

fn = '    private fun maybeDeliverSetup() {'
start = s.find(fn)
if start < 0:
    raise SystemExit('maybeDeliverSetup not found')
marker = '        val direct = runCatching { JSONObject(lastDirectState) }.getOrNull() ?: return\n'
pos = s.find(marker, start)
if pos < 0:
    raise SystemExit('direct readiness marker not found')
if 'transport=r19-video' not in s[start:pos]:
    insert = '''        if (screenDescription) {
            val screen = runCatching { JSONObject(lastScreenVideoState) }.getOrNull() ?: return
            val videoReady = screen.optBoolean("videoTrackReady", false)
            if (!videoReady) {
                logger.log(
                    2,
                    "AiStudioScreenVideo",
                    "WAITING_VIDEO_TRACK enabled=${screen.optBoolean("enabled", false)} " +
                        "gumVideoRequests=${screen.optLong("gumVideoRequests", 0L)} " +
                        "displayRequests=${screen.optLong("displayRequests", 0L)}",
                )
                return
            }
            main.postDelayed({
                if (closed.get() || setupDelivered.get()) return@postDelayed
                setupDelivered.set(true)
                lastProgressAt = SystemClock.elapsedRealtime()
                logger.log(
                    2,
                    "AiStudioLive",
                    "READY model=${targetLiveModel()} operation=$operationMode target=$targetLanguage " +
                        "transport=r19-video videoTrackReady=true gumVideoRequests=${screen.optLong("gumVideoRequests", 0L)} " +
                        "displayRequests=${screen.optLong("displayRequests", 0L)} hidden=false debugVisible=true isolatedLiveHost=true",
                )
                listener.onSetupComplete()
            }, ARM_SETTLE_MS)
            return
        }
'''
    s = s[:pos] + insert + s[pos:]

old_stale = '            if (!suppressTimeouts && setupDelivered.get() && lastProgressAt > 0L && now - lastProgressAt > LIVE_STALE_TIMEOUT_MS) {\n'
new_stale = '            if (!screenDescription && !suppressTimeouts && setupDelivered.get() && lastProgressAt > 0L && now - lastProgressAt > LIVE_STALE_TIMEOUT_MS) {\n'
count = s.count(old_stale)
if count == 1:
    s = s.replace(old_stale, new_stale, 1)
elif count == 0 and new_stale in s:
    pass
else:
    raise SystemExit(f'stale watchdog marker count={count}')

p.write_text(s)
print('R20 screen readiness patched')
