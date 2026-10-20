/* Actual Android WebView/Compose regression, Node 22+. AGPL-3.0. */
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdir, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import utilsBundle from 'playwright-core/lib/utilsBundle';
const {PNG} = utilsBundle;

// Explicit serial prevents accidentally running destructive test setup on a user's phone.
const serial = process.env.FOLIA_ANDROID_SERIAL;
if (!serial) throw new Error('Set FOLIA_ANDROID_SERIAL to a dedicated test emulator with the debug APK installed.');
const adbPath = process.env.ADB_PATH || 'adb';
const adb = (...args) => execFileSync(adbPath, ['-s', serial, ...args], {timeout: 15000, maxBuffer: 32 * 1024 * 1024, windowsHide: true});
const shell = (...args) => adb('shell', ...args).toString().trim();
shell('run-as', 'com.kinara.music', 'true'); // Refuse release APKs; never uninstall or clear app data.
const output = resolve('../app/build/folia-android');
await mkdir(output, {recursive: true});
const report = {android: shell('getprop', 'ro.build.version.release'),
  webview: shell('dumpsys', 'webviewupdate').split('\n').find(line => line.includes('Current WebView package'))?.trim(), cases: []};

async function connect() {
  let pid;
  for (let i = 0; i < 50; i++) {
    try { pid = shell('pidof', 'com.kinara.music'); } catch { /* Activity launch is asynchronous. */ }
    if (pid) break;
    await delay(200);
  }
  if (!pid) throw new Error('Kinara fixture process did not start');
  const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`).toString().trim();
  let target;
  for (let i = 0; i < 100; i++) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${port}/json`, {signal: AbortSignal.timeout(1000)})).json();
      target = targets.find(item => item.url === 'https://kinara.invalid/folia/index.html');
      if (target) break;
    } catch { /* WebView is still starting. */ }
    await delay(200);
  }
  if (!target) { adb('forward', '--remove', `tcp:${port}`); throw new Error('Kinara WebView did not start'); }
  const socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  let sequence = 0;
  const pending = new Map();
  socket.onmessage = event => {
    const message = JSON.parse(event.data), callback = pending.get(message.id);
    if (callback) { pending.delete(message.id); callback(message); }
  };
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 10000);
    pending.set(id, message => { clearTimeout(timeout); if (message.error) reject(new Error(JSON.stringify(message.error))); else resolve(message.result); });
    socket.send(JSON.stringify({id, method, params}));
  });
  return {call, async evaluate(expression) {
    const result = await call('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  }, async shot() {
    // Older Android WebViews omit the GPU surface from Page.captureScreenshot.
    // Crop Android's actual compositor screenshot, using the native WebView bounds.
    const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
    const bounds = JSON.parse(targets.find(item => item.id === target.id).description);
    const full = adb('exec-out', 'screencap', '-p');
    const source = PNG.sync.read(full), crop = new PNG({width: bounds.width, height: bounds.height});
    PNG.bitblt(source, crop, bounds.screenX, bounds.screenY, bounds.width, bounds.height, 0, 0);
    return {full, crop: PNG.sync.write(crop)};
  },
  close() { socket.close(); adb('forward', '--remove', `tcp:${port}`); }};
}

const cases = [
  {style: 'lumiere'}, {style: 'sonnet'}, {style: 'tempera'},
  {style: 'lumiere', orientation: 'landscape', playing: true},
  {style: 'sonnet', reduced: true, lrc: true}, {style: 'tempera', lrc: true},
];
for (const sample of cases) {
  const name = [sample.style, sample.orientation || 'portrait', sample.reduced ? 'reduced' : sample.lrc ? 'lrc' : 'words'].join('-');
  shell('am', 'force-stop', 'com.kinara.music');
  shell('am', 'start', '-W', '-n', 'com.kinara.music/.ui.FoliaProbeActivity',
    '--es', 'style', sample.style, '--es', 'orientation', sample.orientation || 'portrait',
    '--ez', 'playing', String(!!sample.playing), '--ez', 'lrc', String(!!sample.lrc), '--ez', 'reduced', String(!!sample.reduced));
  const cdp = await connect();
  try {
    let state;
    for (let i = 0; i < 100; i++) {
      state = await cdp.evaluate(`(() => {
        const api=window.KinaraFolia, stage=document.getElementById('stage'), canvas=stage?.querySelector('canvas');
        return {status:api?.displayStatus?.() ?? api?.status, failure:api?.failure, mode:api?.mode,
          width:stage?.clientWidth, height:stage?.clientHeight, viewport:innerHeight,
          canvasHeight:canvas?.clientHeight, canvases:document.querySelectorAll('canvas').length};
      })()`);
      if (state.status === 'ready' || state.status === 'failed') break;
      await delay(200);
    }
    assert.equal(state.status, 'ready', JSON.stringify(state));
    assert.equal(state.mode, sample.style);
    assert.equal(state.height, state.viewport);
    assert.ok(state.height > 0 && state.canvasHeight > 0 && state.canvases === 1);
    await delay(300); // Allow Android's compositor to present the first visible frame.
    const before = await cdp.shot();
    await delay(350);
    const after = await cdp.shot();
    assert.equal(before.crop.equals(after.crop), !sample.playing, 'Pause freezes the canvas; playback advances it');
    await writeFile(resolve(output, `${name}-webview.png`), after.crop);
    await writeFile(resolve(output, `${name}-android.png`), after.full);
    // Read actual GPU output in the same task as an explicit paused redraw. CSS size alone
    // cannot distinguish a visible canvas from a renderer that submitted only black pixels.
    const pixels = await cdp.evaluate(`(() => {
      const api=window.KinaraFolia;
      api.clock({identity:api.identity,positionMs:18123,durationMs:60000,speed:1,advancing:false,
        validForMs:1000,sentAtMs:Date.now(),delayMs:0});
      const sample=document.createElement('canvas');sample.width=64;sample.height=64;
      const ctx=sample.getContext('2d');ctx.drawImage(document.querySelector('#stage canvas'),0,0,64,64);
      const data=ctx.getImageData(0,0,64,64).data;let lit=0;const colors=new Set();
      for(let i=0;i<data.length;i+=4){if(data[i+3]>128&&Math.max(data[i],data[i+1],data[i+2])>90)lit++;
        colors.add(data[i]+','+data[i+1]+','+data[i+2]+','+data[i+3]);}
      return {lit,colors:colors.size};
    })()`);
    assert.ok(pixels.lit > 5 && pixels.colors > 20, `Blank GPU output: ${JSON.stringify(pixels)}`);
    report.cases.push({name, ...state, pixels, pausedFrameStable: !sample.playing});
    console.log(`PASS ${name}: ${state.width} x ${state.height}; ${pixels.lit} lit samples`);
  } finally { cdp.close(); }
}
await writeFile(resolve(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
console.log(`Android evidence: ${output}`);
