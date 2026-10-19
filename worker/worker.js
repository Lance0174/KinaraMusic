/**
 * Kinara Bilibili Audio Worker
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 仅使用公开的未登录接口；上游风控失败明确返回错误，不假装为空结果。
 * 音频入口只接受 BVID，目标由 B 站详情决定，不接受用户提供的上游 URL。
 */
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';
const REFERER = 'https://www.bilibili.com/';
let cookieCache = null;
const audioCache = new Map();
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
async function biliJson(url) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const cookie = await getCookie();
    const response = await fetch(url, {
      headers: { 'User-Agent': UA, Referer: REFERER, Origin: 'https://www.bilibili.com',
        Accept: 'application/json', ...(cookie ? { Cookie: cookie } : {}) },
      redirect: 'manual', signal: AbortSignal.timeout(8000),
    });
    const contentType = response.headers.get('content-type') || '';
    const body = await response.text();
    let data;
    if (!contentType.includes('text/html')) { try { data = JSON.parse(body); } catch {} }
    const blocked = response.status === 412 || contentType.includes('text/html') || data?.code === -412 || data?.code === -352;
    if (blocked) {
      cookieCache = null;
      if (attempt === 0) continue;
      throw new UpstreamError('B站暂时限制匿名访问，请稍后重试', 503, data?.code ?? response.status);
    }
    if (!response.ok) throw new UpstreamError(`B站上游 HTTP ${response.status}`);
    if (!data || typeof data.code !== 'number') throw new UpstreamError('B站响应格式无效');
    if (data.code !== 0) throw new UpstreamError('B站接口请求失败', data.code === -404 ? 404 : 502, data.code);
    return data;
  }
}

export function trustedAudioUrl(value) {
  try {
    const u = new URL(value);
    return u.protocol === 'https:' && !u.username && !u.password && (!u.port || u.port === '443') &&
      ['bilivideo.com', 'bilivideo.cn'].some(domain => u.hostname === domain || u.hostname.endsWith('.' + domain));
  } catch { return false; }
}

async function resolveAudio(bvid) {
  const cached = audioCache.get(bvid);
  if (cached && cached.expires > Date.now()) return cached;
  const view = await biliJson('https://api.bilibili.com/x/web-interface/view?bvid=' + bvid);
  const cid = view.data?.cid;
  if (!Number.isSafeInteger(cid) || cid <= 0) throw new UpstreamError('视频不存在或不可访问', 404);
  const play = await biliJson(`https://api.bilibili.com/x/player/playurl?bvid=${bvid}&cid=${cid}&fnval=16&fnver=0&fourk=0`);
  const audio = [...(play.data?.dash?.audio || [])].sort((a, b) => b.bandwidth - a.bandwidth);
  for (const stream of audio) {
    const targets = [stream.baseUrl || stream.base_url, ...(stream.backupUrl || stream.backup_url || [])].filter(trustedAudioUrl);
    if (!targets.length) continue;
    const value = { targets, id: stream.id, bandwidth: stream.bandwidth, expires: Date.now() + 90000 };
    if (audioCache.size >= 128) audioCache.delete(audioCache.keys().next().value);
    audioCache.set(bvid, value);
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
  async fetch(request) {
    const url = new URL(request.url);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: {
      'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
      'Access-Control-Allow-Headers': 'Range, If-Range',
    } });
    if (!['GET', 'HEAD'].includes(request.method)) return json({ error: 'method not allowed' }, 405);
    try {
      if (url.pathname === '/health') return json({ ok: true, version: '1.0.2', ts: Date.now() });
      if (url.pathname === '/bili/search') {
        const kw = (url.searchParams.get('kw') || '').trim();
        if (!kw || kw.length > 120) return json({ error: 'invalid kw' }, 400);
        const j = await biliJson('https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=' + encodeURIComponent(kw));
        if (!Array.isArray(j.data?.result)) throw new UpstreamError('B站搜索响应不完整');
        return json({ result: j.data.result.filter(v => bvidPattern.test(v.bvid)).slice(0, 30).map(v => ({
          bvid: v.bvid, title: (v.title || '').replace(/<[^>]*>/g, ''), author: v.author || '',
          duration: typeof v.duration === 'string' ? v.duration.split(':').reduce((seconds, part) => seconds * 60 + (Number(part) || 0), 0) : v.duration || 0,
        })) });
      }
      if (url.pathname === '/bili/resolve' || url.pathname === '/bili/stream') {
        const bvid = url.searchParams.get('bvid') || '';
        if (url.searchParams.has('u') || !bvidPattern.test(bvid)) return json({ error: 'invalid bvid' }, 400);
        const range = request.headers.get('range');
        if (range && !/^bytes=(\d+-\d*|-\d+)$/.test(range)) return json({ error: 'invalid range' }, 400);
        const audio = await resolveAudio(bvid);
        if (url.pathname === '/bili/resolve') {
          const streamPath = '/bili/stream?bvid=' + bvid;
          return json({ streamPath, url: url.origin + streamPath, id: audio.id, bandwidth: audio.bandwidth });
        }
        let response;
        for (const target of audio.targets) {
          response = await fetchAudio(target, request);
          if (response.ok || response.status === 416) break;
          await response.body?.cancel();
        }
        if (!response || (!response.ok && response.status !== 416)) {
          audioCache.delete(bvid);
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
      if (!(error instanceof UpstreamError)) console.warn('upstream_failure', error.name,
        String(error.message || '').replace(/https?:\/\/\S+/g, '[upstream]').slice(0, 160));
      return json({ error: error instanceof UpstreamError ? error.message : '上游连接失败，请稍后重试',
        ...(error.code != null ? { code: error.code } : {}) }, error.status || 502);
    }
  },
};

function json(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: {
    'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store',
  } });
}
