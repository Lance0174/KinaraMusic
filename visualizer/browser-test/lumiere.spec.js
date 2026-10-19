import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

let server, base;
const shots = resolve('../app/build/folia-screens');
const fixture = {
  identity: 'fixture:one', title: '光落在你眼底', artist: 'Kinara', album: '流光', durationMs: 60000,
  quality: 'balanced', industrial: false, reduceMotion: false,
  lines: Array.from({length: 12}, (_, i) => ({ startTime: i * 4, endTime: i * 4 + 3.6,
    fullText: ['光落在你眼底', '让每一个字都有回响', '沿着夜色慢慢靠近', '音乐继续流动'][i % 4],
    words: [{ text: ['光落在你眼底', '让每一个字都有回响', '沿着夜色慢慢靠近', '音乐继续流动'][i % 4], startTime: i * 4, endTime: i * 4 + 3.6 }] })),
};
async function tick(page, positionMs, extra = {}) {
  await page.evaluate(({positionMs, extra}) => window.KinaraFolia.clock({ positionMs, durationMs: 60000, speed: 1,
    identity: window.KinaraFolia.identity, advancing: false, delayMs: 0, sentAtMs: Date.now(), validForMs: 1000, ...extra }), { positionMs, extra });
}
test.beforeAll(async () => {
  await mkdir(shots, {recursive: true});
  server = createServer(async (req, res) => {
    const name = req.url === '/' ? 'index.html' : req.url.slice(1);
    if (!['index.html','lumiere.js'].includes(name)) { res.writeHead(404); res.end(); return; }
    res.setHeader('Content-Type', name.endsWith('.js') ? 'application/javascript' : 'text/html');
    res.end(await readFile(`../app/src/main/assets/folia/${name}`));
  }).listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
test.afterAll(() => server.close());
test('real WebGL renderer: words, seek, pause, quality and song replacement', async ({page}) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const external = [];
  page.on('request', request => { if (!request.url().startsWith(base)) external.push(request.url()); });
  await page.goto(base);
  await page.evaluate(song => window.KinaraFolia.setSong(song), fixture);
  expect(await page.evaluate(() => ({status:window.KinaraFolia.status, failure:window.KinaraFolia.failure}))).toEqual({status:'ready', failure:''});
  await expect(page.locator('canvas')).toHaveCount(1);
  await tick(page, 1400);
  const before = await page.screenshot({path: `${shots}/lumiere-portrait-early.png`});
  await tick(page, 3100);
  const after = await page.screenshot({path: `${shots}/lumiere-portrait-late.png`});
  expect(before.equals(after)).toBe(false);
  const paused = await page.screenshot();
  expect(after.equals(paused)).toBe(true);
  await tick(page, 18100);
  await page.screenshot({path: `${shots}/lumiere-seek.png`});
  for (const quality of ['low','full']) {
    await page.evaluate(song => window.KinaraFolia.setSong(song), {...fixture, quality});
    expect(await page.evaluate(() => window.KinaraFolia.status)).toBe('ready');
  }
  await page.setViewportSize({width: 740, height: 390});
  await page.evaluate(song => window.KinaraFolia.setSong(song), {...fixture, identity: 'fixture:two', industrial: true, reduceMotion: true});
  await tick(page, 6500);
  await page.screenshot({path: `${shots}/lumiere-landscape.png`});
  expect(await page.locator('canvas').count()).toBe(1);
  expect(errors).toEqual([]); expect(external).toEqual([]);
  await tick(page, 0, {identity: 'fixture:next'});
  await expect(page.locator('#stage')).toHaveCSS('opacity','0');
  await page.evaluate(() => window.KinaraFolia.destroy());
  await expect(page.locator('canvas')).toHaveCount(0);
});

test('clock arriving before renderer initialization starts playback and pause freezes it', async ({page}) => {
  await page.goto(base);
  // Media3 refreshes its anchor every 100 ms, including while cold WebGL shaders compile.
  // A single expiring anchor would correctly freeze before initialization on slow CI hosts.
  await page.evaluate(identity => {
    const started = performance.now();
    const heartbeat = () => window.KinaraFolia.clock({ positionMs: 1400 + performance.now() - started,
      durationMs: 60000, speed: 1, identity, advancing: true, delayMs: 0, sentAtMs: Date.now(), validForMs: 1000 });
    heartbeat();
    window.clockTimer = setInterval(heartbeat, 100);
  }, fixture.identity);
  await page.evaluate(song => window.KinaraFolia.setSong(song), fixture);
  const before = await page.screenshot();
  await page.waitForTimeout(180);
  const after = await page.screenshot();
  expect(before.equals(after)).toBe(false);
  await page.evaluate(() => clearInterval(window.clockTimer));
  await tick(page, 3100);
  const paused = await page.screenshot();
  await page.waitForTimeout(180);
  expect(paused.equals(await page.screenshot())).toBe(true);
});

test('line-only lyrics, empty lyrics and context loss have explicit behavior', async ({page}) => {
  await page.goto(base);
  await page.evaluate(song => window.KinaraFolia.setSong(song), {...fixture, lines: fixture.lines.map(line => ({...line,words:[]}))});
  await tick(page, 1500);
  expect(await page.evaluate(() => window.KinaraFolia.status)).toBe('ready');
  await page.screenshot({path: `${shots}/lumiere-lrc.png`});
  await page.evaluate(song => window.KinaraFolia.setSong(song), {...fixture, identity:'instrumental', lines:[]});
  await tick(page, 15000);
  expect(await page.evaluate(() => window.KinaraFolia.status)).toBe('ready');
  await page.evaluate(() => document.querySelector('canvas').dispatchEvent(new Event('webglcontextlost', {bubbles:true,cancelable:true})));
  expect(await page.evaluate(() => window.KinaraFolia.status)).toBe('failed');
  await expect(page.locator('canvas')).toHaveCount(0);
});
