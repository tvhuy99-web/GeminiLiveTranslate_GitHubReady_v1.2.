import crypto from 'node:crypto';
import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PROTOCOL = 'aistudio-build-executor-v1';
const DEFAULT_MODEL = 'gemini-3.1-flash-live-preview';
const GEMINI_ROOT = 'https://generativelanguage.googleapis.com/v1beta/models';
const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const GEMINI_API_KEY = String(process.env.GEMINI_API_KEY || '').trim();
const BRIDGE_TOKEN = String(process.env.BRIDGE_TOKEN || '').trim();
const BRIDGE_MOCK = /^(1|true|yes)$/i.test(String(process.env.BRIDGE_MOCK || ''));

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const app = express();
app.disable('x-powered-by');
app.use(express.json({ limit: '20mb' }));
app.use(express.static(path.join(__dirname, 'public'), { extensions: ['html'] }));

function requestId(value) {
  const raw = typeof value === 'string' ? value.trim() : '';
  return raw && raw.length <= 120 ? raw : `req_${Date.now()}_${crypto.randomBytes(4).toString('hex')}`;
}

function safeModel(value) {
  const model = String(value || DEFAULT_MODEL).trim();
  if (!/^[A-Za-z0-9._-]{1,160}$/.test(model)) throw new Error('Invalid model id');
  return model;
}

function constantTimeEqual(a, b) {
  const left = Buffer.from(String(a || ''), 'utf8');
  const right = Buffer.from(String(b || ''), 'utf8');
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function requireBridgeToken(req, res, next) {
  if (!BRIDGE_TOKEN) return next();
  if (!constantTimeEqual(req.get('x-bridge-token') || '', BRIDGE_TOKEN)) {
    return res.status(401).json({ ok: false, protocol: PROTOCOL, error: { code: 'BRIDGE_UNAUTHORIZED', message: 'Missing or invalid x-bridge-token' } });
  }
  next();
}

function geminiPayload(body) {
  if (body && Array.isArray(body.contents)) {
    const payload = { contents: body.contents };
    for (const key of ['systemInstruction', 'generationConfig', 'safetySettings', 'tools', 'toolConfig']) {
      if (body[key] != null) payload[key] = body[key];
    }
    return payload;
  }
  const prompt = String(body?.prompt || '').trim();
  if (!prompt) throw new Error('prompt or contents is required');
  const payload = { contents: [{ role: 'user', parts: [{ text: prompt }] }] };
  if (body?.system_instruction) payload.systemInstruction = { parts: [{ text: String(body.system_instruction) }] };
  if (body?.generation_config) payload.generationConfig = body.generation_config;
  return payload;
}

function extractText(json) {
  return (Array.isArray(json?.candidates) ? json.candidates : [])
    .flatMap((candidate) => candidate?.content?.parts || [])
    .map((part) => typeof part?.text === 'string' ? part.text : '')
    .filter(Boolean)
    .join('');
}

async function callGemini(model, operation, payload, signal) {
  if (BRIDGE_MOCK) {
    return new Response(JSON.stringify({ candidates: [{ content: { role: 'model', parts: [{ text: 'AIS_BUILD_BRIDGE_OK_20260901' }] } }] }), {
      status: 200, headers: { 'content-type': 'application/json' },
    });
  }
  if (!GEMINI_API_KEY) {
    const error = new Error('GEMINI_API_KEY is not configured in the AI Studio server runtime');
    error.code = 'GEMINI_KEY_MISSING';
    throw error;
  }
  const suffix = operation === 'streamGenerateContent' ? ':streamGenerateContent?alt=sse' : ':generateContent';
  return fetch(`${GEMINI_ROOT}/${encodeURIComponent(model)}${suffix}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-goog-api-key': GEMINI_API_KEY },
    body: JSON.stringify(payload),
    signal,
  });
}

async function errorBody(response) {
  try { return (await response.text()).slice(0, 64 * 1024); } catch { return ''; }
}

app.get('/api/bridge/health', (req, res) => {
  res.set('cache-control', 'no-store');
  res.json({
    ok: true,
    protocol: PROTOCOL,
    server_time: new Date().toISOString(),
    gemini_key_configured: Boolean(GEMINI_API_KEY),
    bridge_token_required: Boolean(BRIDGE_TOKEN),
    mock: BRIDGE_MOCK,
    default_model: DEFAULT_MODEL,
  });
});

app.post('/api/bridge/generate', requireBridgeToken, async (req, res) => {
  const id = requestId(req.body?.request_id);
  const started = Date.now();
  try {
    const model = safeModel(req.body?.model);
    const controller = new AbortController();
    req.on('close', () => controller.abort());
    const upstream = await callGemini(model, 'generateContent', geminiPayload(req.body), controller.signal);
    if (!upstream.ok) {
      return res.status(upstream.status).json({ ok: false, protocol: PROTOCOL, request_id: id, model, upstream_status: upstream.status, error: { code: 'GEMINI_UPSTREAM_ERROR', message: await errorBody(upstream) || upstream.statusText } });
    }
    const json = await upstream.json();
    res.json({ ok: true, protocol: PROTOCOL, request_id: id, model, elapsed_ms: Date.now() - started, text: extractText(json), response: json });
  } catch (error) {
    res.status(error?.name === 'AbortError' ? 499 : 500).json({ ok: false, protocol: PROTOCOL, request_id: id, error: { code: error?.code || 'BRIDGE_ERROR', message: String(error?.message || error) } });
  }
});

app.post('/api/bridge/stream', requireBridgeToken, async (req, res) => {
  const id = requestId(req.body?.request_id);
  const started = Date.now();
  const controller = new AbortController();
  req.on('close', () => controller.abort());
  res.status(200).set({ 'content-type': 'text/event-stream; charset=utf-8', 'cache-control': 'no-cache, no-transform', connection: 'keep-alive', 'x-accel-buffering': 'no' });
  res.flushHeaders?.();
  const send = (event, data) => { res.write(`event: ${event}\n`); res.write(`data: ${JSON.stringify(data)}\n\n`); };

  try {
    const model = safeModel(req.body?.model);
    const payload = geminiPayload(req.body);
    send('bridge-meta', { protocol: PROTOCOL, request_id: id, model });
    if (BRIDGE_MOCK) {
      for (const text of ['AIS_', 'BUILD_', 'BRIDGE_', 'OK_', '20260901']) send('chunk', { request_id: id, text });
      send('done', { request_id: id, elapsed_ms: Date.now() - started });
      return res.end();
    }

    const upstream = await callGemini(model, 'streamGenerateContent', payload, controller.signal);
    if (!upstream.ok || !upstream.body) {
      send('error', { request_id: id, upstream_status: upstream.status, code: 'GEMINI_UPSTREAM_ERROR', message: await errorBody(upstream) || upstream.statusText });
      return res.end();
    }

    const decoder = new TextDecoder();
    const reader = upstream.body.getReader();
    let buffer = '';
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() || '';
      for (const frame of frames) {
        const raw = frame.split(/\r?\n/).filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n');
        if (!raw) continue;
        try {
          const json = JSON.parse(raw);
          send('chunk', { request_id: id, text: extractText(json), response: json });
        } catch {
          send('raw', { request_id: id, data: raw.slice(0, 64 * 1024) });
        }
      }
    }
    send('done', { request_id: id, elapsed_ms: Date.now() - started });
    res.end();
  } catch (error) {
    if (!res.writableEnded) {
      send('error', { request_id: id, code: error?.name === 'AbortError' ? 'CLIENT_CLOSED' : (error?.code || 'BRIDGE_ERROR'), message: String(error?.message || error) });
      res.end();
    }
  }
});

app.use((req, res) => {
  if (req.path.startsWith('/api/')) return res.status(404).json({ ok: false, protocol: PROTOCOL, error: { code: 'NOT_FOUND', message: 'Unknown bridge route' } });
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.listen(PORT, HOST, () => console.log(`[BuildExecutor] ${PROTOCOL} listening on ${HOST}:${PORT}; key=${Boolean(GEMINI_API_KEY)} token=${Boolean(BRIDGE_TOKEN)} mock=${BRIDGE_MOCK}`));
