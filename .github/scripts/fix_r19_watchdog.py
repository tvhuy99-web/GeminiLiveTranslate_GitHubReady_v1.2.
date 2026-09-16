from pathlib import Path

p = Path('app/src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt')
s = p.read_text()
old = '''        val now = SystemClock.elapsedRealtime()
        lastInputAt = now
        lastProgressAt = now
        val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
'''
new = '''        lastInputAt = SystemClock.elapsedRealtime()
        val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
'''
count = s.count(old)
if count != 1:
    raise SystemExit(f'watchdog block count={count}')
p.write_text(s.replace(old, new, 1))
print('R19 watchdog remains network-grounded')
