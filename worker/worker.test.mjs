import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
// Workers Web Crypto supports the protocol's MD5 digest; Node Web Crypto does not.
const nativeDigest = crypto.subtle.digest.bind(crypto.subtle);
crypto.subtle.digest = (algorithm, data) => algorithm === 'MD5' ?
  Promise.resolve(Uint8Array.from(createHash('md5').update(data).digest()).buffer) : nativeDigest(algorithm, data);
let serial = 0;
const BV = 'BV1xx411c7mD';
async function worker(t, fetch) {
  const old = globalThis.fetch;
  globalThis.fetch = fetch;
  t.after(() => { globalThis.fetch = old; });
  return (await import(`./worker.js?test=${serial++}`)).default;
}
const json = value => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });
const req = (path, init) => new Request('https://kinara.example' + path, init);
function upstream(url) {
  if (url === 'https://www.bilibili.com/') return new Response('', { headers: { 'set-cookie': 'buvid3=guest; Path=/' } });
  if (url.includes('/view?')) return json({ code: 0, data: { cid: 123 } });
  if (url.endsWith('/nav')) return json({ code: 0, data: { wbi_img: {
    img_url: 'https://i.test/7cd084941338484aae1ad9425b84077c.png',
    sub_url: 'https://i.test/4932caff0ff746eab6f01bf08b70ac45.png',
  } } });
  if (url.includes('/playurl?')) return json({ code: 0, data: { dash: { audio: [{ id: 30280, bandwidth: 192000, baseUrl: 'https://cn-test.bilivideo.com/audio.m4s' }] } } });
  throw new Error('unexpected upstream: ' + url);
}

test('reject arbitrary URL, malformed BVID, methods and ranges before any fetch', async t => {
  const w = await worker(t, () => { throw new Error('must not fetch'); });
  for (const path of ['/bili/stream?u=https://example.com/', '/bili/stream?bvid=evil', '/bili/search?kw='])
    assert.equal((await w.fetch(req(path))).status, 400);
  assert.equal((await w.fetch(req('/bili/stream?bvid=' + BV, { headers: { Range: 'bytes=0-5,10-15' } }))).status, 400);
  assert.equal((await w.fetch(req('/health', { method: 'POST' }))).status, 405);
});

test('CDN whitelist rejects credentials, lookalike suffixes, cleartext and alternate ports', async () => {
  const { trustedAudioUrl } = await import('./worker.js');
  for (const url of ['https://evilbilivideo.com/a', 'https://bilivideo.com.evil.test/a', 'http://cn.bilivideo.com/a', 'https://user@cn.bilivideo.com/a', 'https://cn.bilivideo.com:8443/a', 'https://127.0.0.1/'])
    assert.equal(trustedAudioUrl(url), false, url);
  assert.equal(trustedAudioUrl('https://cn.bilivideo.com/a?deadline=42'), true);
  assert.equal(trustedAudioUrl('https://cn.mcdn.bilivideo.cn/a'), true);
});

test('HTML challenge retries once with refreshed guest cookie and returns an honest failure', async t => {
  let apis = 0, cookies = 0;
  const w = await worker(t, async url => {
    if (url === 'https://www.bilibili.com/') { cookies++; return new Response(''); }
    apis++; return new Response('<html>challenge</html>', { headers: { 'content-type': 'text/html' } });
  });
  const r = await w.fetch(req('/bili/search?kw=test'));
  assert.equal(r.status, 503); assert.equal(apis, 2); assert.equal(cookies, 2);
  assert.match((await r.json()).error, /限制/);
});

test('JSON risk code never becomes a successful empty list', async t => {
  let apis = 0;
  const w = await worker(t, async url => url === 'https://www.bilibili.com/' ? new Response('') : (++apis, json({ code: -412 })));
  const r = await w.fetch(req('/bili/search?kw=test'));
  assert.equal(r.status, 503); assert.equal(apis, 2); assert.equal((await r.json()).code, -412);
});

test('oversized upstream JSON is rejected before parsing', async t => {
  let apis = 0;
  const huge = 'x'.repeat(2 * 1024 * 1024 + 1);
  const w = await worker(t, async url => url === 'https://www.bilibili.com/' ? new Response('') : (++apis,
    new Response(huge, { headers: { 'content-type': 'application/json' } })));
  const r = await w.fetch(req('/bili/search?kw=test'));
  assert.equal(r.status, 502);
  assert.equal(apis, 1);
  assert.match((await r.json()).error, /响应体过大/);
});

test('bounded retry can recover, strips tags and parses mm:ss duration', async t => {
  let apis = 0;
  const w = await worker(t, async url => {
    if (url === 'https://www.bilibili.com/') return new Response('');
    if (++apis === 1) return json({ code: -412 });
    return json({ code: 0, data: { result: [{ bvid: BV, title: '<em>Song</em>', duration: '04:09' }] } });
  });
  const r = await w.fetch(req('/bili/search?kw=test'));
  assert.equal(r.status, 200);
  assert.deepEqual((await r.json()).result[0], { bvid: BV, title: 'Song', author: '', pic: '', duration: 249 });
});

test('resolve binds to this Worker; streaming forwards Range/If-Range and preserves 206', async t => {
  let cdnCalls = 0;
  const w = await worker(t, async (url, init) => {
    if (!url.includes('cn-test.bilivideo.com')) return upstream(url);
    cdnCalls++;
    assert.equal(init.headers.range, 'bytes=8-11'); assert.equal(init.headers['if-range'], 'etag-a');
    assert.equal(init.headers.Referer, 'https://www.bilibili.com/'); assert.equal(init.redirect, 'manual');
    return new Response('abcd', { status: 206, headers: { 'Content-Range': 'bytes 8-11/100', 'Content-Length': '4', 'Accept-Ranges': 'bytes', 'Content-Type': 'audio/mp4' } });
  });
  const parsed = await (await w.fetch(req('/bili/resolve?bvid=' + BV))).json();
  assert.equal(parsed.url, 'https://kinara.example/bili/stream?bvid=' + BV);
  const r = await w.fetch(req(parsed.streamPath, { headers: { Range: 'bytes=8-11', 'If-Range': 'etag-a' } }));
  assert.equal(r.status, 206); assert.equal(r.headers.get('Content-Range'), 'bytes 8-11/100');
  assert.equal(await r.text(), 'abcd'); assert.equal(cdnCalls, 1);
});

test('external redirect never reaches an untrusted host', async t => {
  const calls = [];
  const w = await worker(t, async url => {
    calls.push(url);
    if (url.includes('cn-test.bilivideo.com')) return new Response(null, { status: 302, headers: { Location: 'https://example.com/private' } });
    return upstream(url);
  });
  assert.equal((await w.fetch(req('/bili/stream?bvid=' + BV))).status, 502);
  assert.ok(!calls.some(u => u.includes('example.com')));
});

for (const failure of ['network', 'html', 'redirect']) test(`CDN ${failure} failure uses the next trusted backup`, async t => {
  const calls = [];
  const w = await worker(t, async url => {
    calls.push(url);
    if (url.includes('/playurl?')) return json({ code: 0, data: { dash: { audio: [
      { id: 30250, codecs: 'ec-3', bandwidth: 400000, baseUrl: 'https://wrong.bilivideo.com/audio' },
      { id: 30280, codecs: 'mp4a.40.2', bandwidth: 192000, baseUrl: 'https://first.bilivideo.com/audio',
        backupUrl: ['https://second.bilivideo.com/audio'] },
    ] } } });
    if (url.includes('first.bilivideo.com')) {
      if (failure === 'network') throw new Error('CDN unavailable');
      if (failure === 'html') return new Response('<html>challenge</html>', { headers: { 'content-type': 'text/html' } });
      return new Response(null, { status: 302, headers: { location: 'https://untrusted.test/audio' } });
    }
    if (url.includes('second.bilivideo.com')) return new Response('audio', { headers: { 'content-type': 'audio/mp4' } });
    return upstream(url);
  });
  const response = await w.fetch(req('/bili/stream?bvid=' + BV));
  assert.equal(response.status, 200); assert.equal(await response.text(), 'audio');
  assert.ok(calls.some(u => u.includes('second.bilivideo.com')));
  assert.ok(!calls.some(u => u.includes('wrong.bilivideo.com') || u.includes('untrusted.test')));
});

test('preserves unsatisfiable Range response and HEAD has no body', async t => {
  const w = await worker(t, async (url, init) => {
    if (!url.includes('cn-test.bilivideo.com')) return upstream(url);
    return new Response(null, { status: init.method === 'HEAD' ? 200 : 416, headers: { 'Content-Range': 'bytes */100' } });
  });
  const r = await w.fetch(req('/bili/stream?bvid=' + BV, { headers: { Range: 'bytes=999-' } }));
  assert.equal(r.status, 416); assert.equal(r.headers.get('Content-Range'), 'bytes */100');
  const head = await w.fetch(req('/bili/stream?bvid=' + BV, { method: 'HEAD' }));
  assert.equal(head.status, 200); assert.equal(await head.text(), '');
});


test('homepage warmup failure does not prevent a public API request', async t => {
  const w = await worker(t, async url => {
    if (url === 'https://www.bilibili.com/') throw new Error('homepage offline');
    return json({ code: 0, data: { result: [] } });
  });
  const r = await w.fetch(req('/bili/search?kw=test'));
  assert.equal(r.status, 200); assert.deepEqual((await r.json()).result, []);
});

test('private session requires a service key before any upstream request', async t => {
  const w = await worker(t, () => { throw new Error('must not fetch'); });
  const env = { BILI_COOKIE: 'SESSDATA=fixture-only', KINARA_CLIENT_TOKEN: 'test-client' };
  assert.equal((await w.fetch(req('/bili/search?kw=test'), env)).status, 401);
  assert.equal((await w.fetch(req('/bili/search?kw=test', { headers: { 'X-Kinara-Key': 'wrong' } }), env)).status, 401);
  assert.equal((await w.fetch(req('/bili/search?kw=test'), { BILI_COOKIE: env.BILI_COOKIE })).status, 503);
});

test('private cookie stays on official API and never appears in responses or CDN headers', async t => {
  const env = { BILI_COOKIE: 'SESSDATA=fixture-only', KINARA_CLIENT_TOKEN: 'test-client' };
  const w = await worker(t, async (url, init) => {
    assert.equal(init.headers['X-Kinara-Key'], undefined);
    if (url.startsWith('https://api.bilibili.com/')) {
      assert.equal(init.headers.Cookie, env.BILI_COOKIE);
      return upstream(url);
    }
    assert.equal(init.headers.Cookie, undefined);
    assert.equal(init.headers.Authorization, undefined);
    return new Response('audio', { headers: { 'Content-Type': 'audio/mp4', 'Set-Cookie': 'should=not-forward' } });
  });
  const options = { headers: { 'X-Kinara-Key': env.KINARA_CLIENT_TOKEN } };
  const resolve = await w.fetch(req('/bili/resolve?bvid=' + BV + '&br=128'), env);
  assert.equal(resolve.status, 401);
  const result = await (await w.fetch(req('/bili/resolve?bvid=' + BV + '&br=128', options), env)).json();
  assert.equal(JSON.stringify(result).includes('fixture-only'), false);
  assert.ok(result.streamPath.includes('&br=128&expires='));
  assert.ok(result.streamPath.includes('&ticket='));
  // System downloads use a short-lived, recording-scoped ticket, never the long-lived service key.
  assert.equal((await w.fetch(req(result.streamPath.replace(BV, 'BV1xx411c7mE')), env)).status, 401);
  assert.equal((await w.fetch(req(result.streamPath.replace(/expires=\d+/, 'expires=1')), env)).status, 401);
  assert.equal((await w.fetch(req(result.streamPath.replace('/bili/stream', '/bili/search')), env)).status, 401);
  const response = await w.fetch(req(result.streamPath), env);
  assert.equal(response.status, 200); assert.equal(await response.text(), 'audio');
  assert.equal(response.headers.get('Set-Cookie'), null);
});

test('requested CID is verified and low quality selects an independent stream', async t => {
  let selected = '';
  const w = await worker(t, async url => {
    if (url.includes('/playurl?')) {
      assert.ok(url.includes('cid=123'));
      return json({ code: 0, data: { dash: { audio: [
        { id: 30280, bandwidth: 192000, baseUrl: 'https://high.bilivideo.com/audio' },
        { id: 30216, bandwidth: 64000, baseUrl: 'https://low.bilivideo.com/audio' },
      ] } } });
    }
    if (url.includes('.bilivideo.com')) { selected = url; return new Response('audio'); }
    return upstream(url);
  });
  assert.equal((await w.fetch(req('/bili/stream?bvid=' + BV + '&cid=999'))).status, 404);
  assert.equal((await w.fetch(req('/bili/stream?bvid=' + BV + '&cid=123&br=128'))).status, 200);
  assert.equal(selected, 'https://low.bilivideo.com/audio');
});

test('WBI official signing example matches without leaking cookie into query', async () => {
  const { wbiQuery } = await import('./worker.js');
  assert.equal(await wbiQuery({ foo: '114', bar: '514', baz: '1919810' }, 'ea1db124af3c7062474693fa704f4ff8', 1702204169),
    'bar=514&baz=1919810&foo=114&wts=1702204169&w_rid=6149fdadf571698ca7e6a567265cd0ee');
});
