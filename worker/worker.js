/**
 * Kinara Bilibili Audio Worker
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 默认使用公开接口；私有部署可在 Secret 中配置会话，只向官方 API 发送 Cookie。
 * 音频入口只接受 BVID，目标由 B 站详情决定，不接受用户提供的上游 URL。
 */
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';
const REFERER = 'https://www.bilibili.com/';
const MAX_JSON_BYTES = 2 * 1024 * 1024;
let cookieCache = null;
const audioCache = new Map();
let wbiCache = null;
const bvidPattern = /^BV[0-9A-Za-z]{10}$/;
class UpstreamError extends Error {
  constructor(message, status = 502, code = null) { super(message); this.status = status; this.code = code; }
}

async function getCookie() {
  if (cookieCache && cookieCache.expires > Date.now()) return cookieCache.value;
  let response;
  try {
    response = await fetch(REFERER, {
      headers: { 'User-Agent': UA, Accept: 'text/html' }, redirect: 'follow', signal: AbortSignal.timeout(5000),
    });
  } catch {
    // 首页预热失败不阻断公开 API；不伪造访客 cookie。
    return '';
  }
  const cookies = (response.headers.getSetCookie?.() || [])
    .filter(c => /^(buvid3|buvid4|b_nut)=/.test(c)).map(c => c.split(';')[0]);
  await response.body?.cancel();
  const value = cookies.join('; ');
  cookieCache = { value, expires: Date.now() + 600000 };
  return value;
}

/** HTML / 412 风控仅刷新一次访客 cookie；不无限重试，也不吞业务错误。 */
async function biliJson(url, session = '') {
  if (new URL(url).origin !== 'https://api.bilibili.com') throw new UpstreamError('API 目标不受信任');
  for (let attempt = 0; attempt < 2; attempt++) {
    const cookie = session || await getCookie();
    const response = await fetch(url, {
      headers: { 'User-Agent': UA, Referer: REFERER, Origin: 'https://www.bilibili.com',
        Accept: 'application/json', ...(cookie ? { Cookie: cookie } : {}) },
      redirect: 'manual', signal: AbortSignal.timeout(8000),
    });
    const contentType = response.headers.get('content-type') || '';
    const body = await readResponseText(response);
    let data;
    if (!contentType.includes('text/html')) { try { data = JSON.parse(body); } catch {} }
    const blocked = response.status === 412 || contentType.includes('text/html') || data?.code === -412 || data?.code === -352;
    if (blocked) {
      cookieCache = null;
      if (attempt === 0) continue;
      throw new UpstreamError('B站暂时限制当前访问，请稍后重试', 503, data?.code ?? response.status);
    }
    if (!response.ok) throw new UpstreamError(`B站上游 HTTP ${response.status}`);
    if (!data || typeof data.code !== 'number') throw new UpstreamError('B站响应格式无效');
    if (data.code !== 0 && !(url.endsWith('/nav') && data.code === -101 && data.data?.wbi_img))
      throw new UpstreamError('B站接口请求失败', data.code === -404 ? 404 : 502, data.code);
    if (data.data?.v_voucher) throw new UpstreamError('B站要求验证当前访问', 503, -352);
    return data;
  }
}

export async function wbiQuery(parameters, key, seconds) {
  const values = { ...parameters, wts: String(seconds) };
  const encode = value => encodeURIComponent(String(value).replace(/[!'()*]/g, ''));
  const query = Object.keys(values).sort().map(k => encode(k) + '=' + encode(values[k])).join('&');
  // MD5 is Bilibili's request-signing protocol, not an encryption or password hash.
  const bytes = await crypto.subtle.digest('MD5', new TextEncoder().encode(query + key));
  const signature = [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, '0')).join('');
  return query + '&w_rid=' + signature;
}

async function signedBili(path, parameters, session) {
  for (let attempt = 0; attempt < 2; attempt++) {
    if (!wbiCache || wbiCache.expires <= Date.now()) {
      const nav = await biliJson('https://api.bilibili.com/x/web-interface/nav', session);
      const keys = nav.data?.wbi_img;
      const raw = ['img_url', 'sub_url'].map(k => keys?.[k]?.split('/').pop()?.split('.')[0] || '').join('');
      if (!/^[a-f0-9]{64}$/i.test(raw)) throw new UpstreamError('暂时无法获取 B站请求签名');
      const order = [46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13];
      wbiCache = { key: order.map(i => raw[i]).join(''), expires: Date.now() + 600000 };
    }
    const query = await wbiQuery(parameters, wbiCache.key, Math.floor(Date.now() / 1000));
    try { return await biliJson('https://api.bilibili.com' + path + '?' + query, session); }
    catch (error) {
      if (error.code !== -403 || attempt > 0) throw error;
      wbiCache = null;
    }
  }
}

async function readResponseText(response) {
  const declared = Number(response.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > MAX_JSON_BYTES) {
    await response.body?.cancel();
    throw new UpstreamError('B站响应体过大', 502);
  }
  if (!response.body) return '';
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value?.byteLength || 0;
      if (total > MAX_JSON_BYTES) {
        await reader.cancel();
        throw new UpstreamError('B站响应体过大', 502);
      }
      if (value) chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return new TextDecoder().decode(bytes);
}

export function trustedAudioUrl(value) {
  try {
    const u = new URL(value);
    return u.protocol === 'https:' && !u.username && !u.password && (!u.port || u.port === '443') &&
      !u.hash && ['bilivideo.com', 'bilivideo.cn'].some(domain => u.hostname === domain || u.hostname.endsWith('.' + domain));
  } catch { return false; }
}

async function resolveAudio(bvid, cid, bitrate, session) {
  const cacheKey = `${bvid}:${cid}:${bitrate}:${session ? 'private' : 'public'}`;
  const cached = audioCache.get(cacheKey);
  if (cached && cached.expires > Date.now()) return cached;
  const view = await biliJson('https://api.bilibili.com/x/web-interface/view?bvid=' + bvid, session);
  const actualCid = cid ? Number(cid) : view.data?.cid;
  if (!Number.isSafeInteger(actualCid) || actualCid <= 0 || (cid &&
      actualCid !== view.data?.cid && !view.data?.pages?.some(p => p.cid === actualCid)))
    throw new UpstreamError('视频分集不存在或不可访问', 404);
  const parameters = { bvid, cid: actualCid, fnval: 16, fnver: 0, fourk: 0, platform: 'pc' };
  const play = session ? await signedBili('/x/player/wbi/playurl', parameters, session) :
    await biliJson(`https://api.bilibili.com/x/player/playurl?bvid=${bvid}&cid=${actualCid}&fnval=16&fnver=0&fourk=0`);
  const audio = [...(play.data?.dash?.audio || [])]
    .filter(a => /^mp4a\./i.test(a.codecs || '') || (!a.codecs && [30216, 30232, 30280].includes(a.id)))
    .sort((a, b) => bitrate === '128' ? a.bandwidth - b.bandwidth : b.bandwidth - a.bandwidth);
  for (const stream of audio) {
    const targets = [stream.baseUrl || stream.base_url, ...(stream.backupUrl || stream.backup_url || [])].filter(trustedAudioUrl);
    if (!targets.length) continue;
    const value = { targets, id: stream.id, bandwidth: stream.bandwidth, expires: Date.now() + 90000 };
    if (audioCache.size >= 128) audioCache.delete(audioCache.keys().next().value);
    audioCache.set(cacheKey, value);
    return value;
  }
  throw new UpstreamError('未找到可用的音频流', 404);
}

/** 所有重定向都重新校验主机；保留 Range/If-Range 和 206/416 语义。 */
async function fetchAudio(target, request) {
  for (let redirects = 0; redirects <= 3; redirects++) {
    if (!trustedAudioUrl(target)) throw new UpstreamError('音频目标不受信任', 502);
    const headers = { 'User-Agent': UA, Referer: REFERER };
    for (const name of ['range', 'if-range']) if (request.headers.has(name)) headers[name] = request.headers.get(name);
    const response = await fetch(target, { method: request.method, headers, redirect: 'manual', signal: AbortSignal.timeout(15000) });
    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get('location');
      await response.body?.cancel();
      if (!location) throw new UpstreamError('音频重定向缺少地址');
      target = new URL(location, target).href;
      continue;
    }
    return response;
  }
  throw new UpstreamError('音频重定向过多');
}

export default {
  async fetch(request, env = {}) {
    const url = new URL(request.url);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: {
      'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
      'Access-Control-Allow-Headers': 'Range, If-Range, X-Kinara-Key',
    } });
    if (!['GET', 'HEAD'].includes(request.method)) return json({ error: 'method not allowed' }, 405);
    try {
      if (url.pathname === '/health') return json({ ok: true, version: '1.1.0', ts: Date.now() });
      // A stored account must never turn this endpoint into an unauthenticated account proxy.
      if (env.BILI_COOKIE && !env.KINARA_CLIENT_TOKEN) return json({ error: '私有服务尚未配置访问密钥' }, 503);
      if (env.KINARA_CLIENT_TOKEN && !await validClientKey(request.headers.get('X-Kinara-Key'), env.KINARA_CLIENT_TOKEN) &&
          !await validStreamTicket(url, env.KINARA_CLIENT_TOKEN))
        return json({ error: '访问凭据无效' }, 401);
      const session = env.BILI_COOKIE || '';
      if (url.pathname === '/bili/search') {
        const kw = (url.searchParams.get('kw') || '').trim();
        if (!kw || kw.length > 120) return json({ error: 'invalid kw' }, 400);
        const j = session ? await signedBili('/x/web-interface/wbi/search/type', { search_type: 'video', keyword: kw, page: 1 }, session) :
          await biliJson('https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=' + encodeURIComponent(kw));
        if (!Array.isArray(j.data?.result)) throw new UpstreamError('B站搜索响应不完整');
        return json({ result: j.data.result.filter(v => bvidPattern.test(v.bvid)).slice(0, 30).map(v => ({
          bvid: v.bvid, title: (v.title || '').replace(/<[^>]*>/g, ''), author: v.author || '',
          pic: typeof v.pic === 'string' ? v.pic : '',
          duration: typeof v.duration === 'string' ? v.duration.split(':').reduce((seconds, part) => seconds * 60 + (Number(part) || 0), 0) : v.duration || 0,
        })) });
      }
      if (url.pathname === '/bili/resolve' || url.pathname === '/bili/stream') {
        const bvid = url.searchParams.get('bvid') || '';
        const cid = url.searchParams.get('cid') || '';
        const bitrate = url.searchParams.get('br') || '320';
        if (url.searchParams.has('u') || !bvidPattern.test(bvid)) return json({ error: 'invalid bvid' }, 400);
        if ((cid && !/^[1-9]\d{0,15}$/.test(cid)) || !['128', '192', '320', 'flac'].includes(bitrate)) return json({ error: 'invalid audio selection' }, 400);
        const range = request.headers.get('range');
        if (range && !/^bytes=(\d+-\d*|-\d+)$/.test(range)) return json({ error: 'invalid range' }, 400);
        const audio = await resolveAudio(bvid, cid, bitrate, session);
        if (url.pathname === '/bili/resolve') {
          let streamPath = streamIdentity(url);
          if (env.KINARA_CLIENT_TOKEN) {
            const expires = Math.floor(Date.now() / 1000) + 900;
            streamPath += '&expires=' + expires + '&ticket=' + await streamTicket(streamPath, expires, env.KINARA_CLIENT_TOKEN);
          }
          return json({ streamPath, url: url.origin + streamPath, id: audio.id, bandwidth: audio.bandwidth });
        }
        let response;
        for (const target of audio.targets) {
          try {
            response = await fetchAudio(target, request);
            const type = response.headers.get('content-type') || '';
            if (response.status === 416 || (response.ok && !/text\/html|json/i.test(type))) break;
            await response.body?.cancel();
          } catch {
            // A single unavailable CDN or rejected redirect must not hide healthy backups.
          }
          response = null;
        }
        if (!response || (!response.ok && response.status !== 416)) {
          audioCache.delete(`${bvid}:${cid}:${bitrate}:${session ? 'private' : 'public'}`);
          throw new UpstreamError('音频上游暂不可用，请重试');
        }
        const headers = new Headers({ 'Access-Control-Allow-Origin': '*',
          'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges', 'Cache-Control': 'no-store' });
        for (const name of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'etag', 'last-modified']) {
          if (response.headers.has(name)) headers.set(name, response.headers.get(name));
        }
        return new Response(request.method === 'HEAD' ? null : response.body, { status: response.status, headers });
      }
      return json({ error: 'not found' }, 404);
    } catch (error) {
      if (!(error instanceof UpstreamError)) console.warn('upstream_failure');
      return json({ error: error instanceof UpstreamError ? error.message : '上游连接失败，请稍后重试',
        ...(error.code != null ? { code: error.code } : {}) }, error.status || 502);
    }
  },
};

async function validClientKey(actual, expected) {
  if (!actual || actual.length > 256) return false;
  const digest = value => crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  const [a, b] = await Promise.all([digest(actual), digest(expected)]);
  const left = new Uint8Array(a), right = new Uint8Array(b);
  let difference = 0;
  for (let i = 0; i < left.length; i++) difference |= left[i] ^ right[i];
  return difference === 0;
}

function streamIdentity(url) {
  return '/bili/stream?bvid=' + encodeURIComponent(url.searchParams.get('bvid') || '') +
    (url.searchParams.get('cid') ? '&cid=' + encodeURIComponent(url.searchParams.get('cid')) : '') +
    (url.searchParams.has('br') ? '&br=' + encodeURIComponent(url.searchParams.get('br')) : '');
}

async function streamTicket(path, expires, secret) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const bytes = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(path + '\n' + expires));
  return [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, '0')).join('');
}

async function validStreamTicket(url, secret) {
  if (url.pathname !== '/bili/stream') return false;
  const expires = Number(url.searchParams.get('expires'));
  const now = Math.floor(Date.now() / 1000);
  const ticket = url.searchParams.get('ticket') || '';
  if (!Number.isSafeInteger(expires) || expires <= now || expires > now + 900 || !/^[a-f0-9]{64}$/.test(ticket)) return false;
  return validClientKey(ticket, await streamTicket(streamIdentity(url), expires, secret));
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: {
    'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store',
  } });
}
