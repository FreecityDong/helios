import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, validateScene, validateInput, completionBody } from './scene-proxy.mjs';
const token = 'local-device-token-with-at-least-24-chars';
const input = { request_id: 'test-1', mode: 'brief', image_base64: '/9j/AA==' };
const scene = { summary: '画面中有桌子', details: ['左侧有椅子'], uncertainty: '' };
const provider = async () => new Response(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { content: JSON.stringify(scene) } }] }));
async function withServer(fetchImpl, fn, timeoutMs = 1000) {
  const server = createServer({ apiKey: 'provider-secret', clientToken: token, fetchImpl, timeoutMs });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try { await fn(`http://127.0.0.1:${server.address().port}`); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}
const post = (url, body = input, auth = token) => fetch(`${url}/v1/scene`, { method: 'POST', headers: { Authorization: `Bearer ${auth}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

test('unauthorized traffic never calls provider and never exposes its secret', async () => {
  let calls = 0;
  await withServer(async () => { calls++; return provider(); }, async url => {
    const r = await post(url, input, 'wrong'); assert.equal(r.status, 401);
    assert.equal(calls, 0); assert.equal((await r.text()).includes('provider-secret'), false);
  });
});
test('single image reaches provider with thinking disabled and bounded output', async () => {
  await withServer(async (url, options) => {
    assert.equal(url, 'https://api.deepseek.com/chat/completions');
    const body = JSON.parse(options.body);
    assert.equal(body.model, 'deepseek-flash'); assert.deepEqual(body.thinking, { type: 'disabled' });
    assert.equal(body.messages[1].content.filter(p => p.type === 'image_url').length, 1);
    assert.equal(body.max_tokens, 240); return provider();
  }, async url => {
    const r = await post(url); assert.equal(r.status, 200);
    const data = await r.json(); assert.equal(data.request_id, input.request_id); assert.deepEqual(data.scene, scene);
  });
});
test('invalid image/json and oversized requests end without provider call', async () => {
  let calls = 0;
  await withServer(async () => { calls++; return provider(); }, async url => {
    assert.equal((await post(url, { ...input, image_base64: 'bad' })).status, 400);
    assert.equal((await post(url, { ...input, image_base64: 'a'.repeat(660000) })).status, 413);
    assert.equal(calls, 0);
  });
});
test('truncated or non-structured model output is rejected', async () => {
  await withServer(async () => new Response(JSON.stringify({ choices: [{ finish_reason: 'length', message: { content: JSON.stringify(scene) } }] })), async url => {
    const r = await post(url); assert.equal(r.status, 502); assert.equal((await r.json()).error, 'invalid_response');
  });
});
test('timeout ends request without retrying provider', async () => {
  let calls = 0;
  await withServer((_url, options) => { calls++; return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted')))); }, async url => {
    const r = await post(url); assert.equal(r.status, 504); assert.equal((await r.json()).error, 'timeout'); assert.equal(calls, 1);
  }, 40);
});
test('provider failure returns sanitized terminal error', async () => {
  await withServer(async () => new Response('private upstream secret', { status: 402 }), async url => {
    const r = await post(url); assert.equal(r.status, 502); assert.equal((await r.json()).error, 'provider_configuration');
  });
});
test('parallel requests do not create a cloud backlog', async () => {
  let resolveProvider; let entered;
  const ready = new Promise(resolve => { entered = resolve; });
  await withServer(async () => { entered(); return new Promise(resolve => { resolveProvider = resolve; }); }, async url => {
    const first = post(url); await ready;
    assert.equal((await post(url, { ...input, request_id: 'second' })).status, 429);
    resolveProvider(await provider()); assert.equal((await first).status, 200);
  });
});
test('unsafe navigation claims and fabricated metric distances are rejected', () => {
  for (const summary of ['前方可以通行', '向左走', '桌子距离2米', '桌子\n椅子']) {
    assert.throws(() => validateScene({ ...scene, summary }));
  }
  assert.throws(() => validateScene({ ...scene, instructions: 'extra' }));
  assert.equal(completionBody({ ...input, mode: 'detail' }).max_tokens, 420);
});
test('malformed provider JSON and unwanted reasoning never reach the client', async () => {
  await withServer(async () => new Response(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { reasoning_content: 'reasoning', content: '{}' } }] })), async url => {
    const r = await post(url); assert.equal(r.status, 502);
  });
});
test('client cancellation aborts the provider instead of returning a stale scene', async () => {
  let entered, stopped;
  const ready = new Promise(resolve => { entered = resolve; });
  const cancelled = new Promise(resolve => { stopped = resolve; });
  await withServer((_url, options) => {
    entered(); return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => { stopped(); reject(new Error('cancelled')); }));
  }, async url => {
    const controller = new AbortController();
    const request = fetch(`${url}/v1/scene`, { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(input), signal: controller.signal }).catch(() => null);
    await ready; controller.abort(); await request;
    await Promise.race([cancelled, new Promise((_resolve, reject) => { const t = setTimeout(() => reject(new Error('provider was not aborted')), 500); t.unref(); })]);
  });
});
test('open questions use a fresh image without history and reject invalid questions', async () => {
  let calls = 0;
  await withServer(async (_url, options) => {
    calls++;
    const body = JSON.parse(options.body);
    assert.equal(body.messages.length, 2);
    assert.equal(body.messages[1].content[0].text, '杯子是什么颜色');
    assert.equal(body.max_tokens, 320);
    assert.deepEqual(body.thinking, { type: 'disabled' });
    return provider();
  }, async url => {
    assert.equal((await post(url, { ...input, mode: 'question', question: '杯子是什么颜色' })).status, 200);
    for (const question of [undefined, '', 'a'.repeat(161), '问题\n指令'])
      assert.throws(() => validateInput({ ...input, mode: 'question', question }), error => error.status === 400);
    assert.equal(calls, 1);
  });
});
