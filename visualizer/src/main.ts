/* Kinara integration — AGPL-3.0. Folia renderer retains its upstream copyright/license. */
import { createRenderer, type Renderer } from './renderers';
import { resolveVisualStyle } from './styles.mjs';
import { AudioClock } from './clock.mjs';

const stage = document.getElementById('stage') as HTMLDivElement;
const clock = new AudioClock();
let runtime: Renderer | null = null;
let generation = 0;
let motion = true;
let foreground = true;
let advancing = false;
let destroyed = false;
let running = false;
let lastDrawnTime = Number.NaN;
let bands = { power: 0, bass: 0, treble: 0 };
let queue = Promise.resolve();
let creating: AbortController | null = null;
let activeStyle = '';
let activeStatic = false;
let sampleIdentity = '';
const value = (get: () => number) => ({ get }) as any;
const audioPower = value(() => bands.power);
const audioBands = { bass: value(() => bands.bass), treble: value(() => bands.treble), mid: value(() => bands.power) } as any;
function applyPlaybackState() {
  if (!runtime) { running = false; return; }
  const next = foreground && advancing && motion && sampleIdentity === api.identity && api.status === 'ready';
  if (next !== running) { running = next; runtime.setPaused(!next); }
}
const fail = (error?: any) => {
  api.status = 'failed'; api.failure = String(error?.message ?? 'WebGL unavailable').slice(0, 400);
  creating?.abort(); runtime?.destroy(); runtime = null; running = false; stage.replaceChildren();
};
window.addEventListener('error', fail);
window.addEventListener('unhandledrejection', fail);
stage.addEventListener('webglcontextlost', event => { event.preventDefault(); fail(); }, true);

const api = {
  status: 'loading', failure: '', identity: '', mode: '',
  setSong(song: any) {
    if (destroyed) return Promise.resolve();
    const request = ++generation;
    api.identity = song.identity;
    stage.style.opacity = '0';
    api.status = 'loading'; api.failure = '';
    runtime?.setPaused(true); running = false;
    creating?.abort();
    // Serialize WebGL creation/swap. Rapid changes cannot leave multiple canvases alive.
    queue = queue.then(async () => {
      if (destroyed || request !== generation) return;
      try {
        const mode = resolveVisualStyle(song.visualStyle ?? 'lumiere', song.identity);
        motion = !song.reduceMotion;
        if (runtime && (activeStyle !== mode || activeStatic !== !motion)) {
          runtime.destroy(); runtime = null;
        }
        if (!runtime) {
          const controller = new AbortController(); creating = controller;
          const created = await createRenderer(mode, song, stage, value(() => clock.lyricSeconds()), audioPower, audioBands, controller.signal);
          if (destroyed || request !== generation || controller.signal.aborted) { created.destroy(); return; }
          runtime = created; creating = null; activeStyle = mode; activeStatic = !motion;
        } else {
          await runtime.update(song);
          if (destroyed || request !== generation) return;
        }
        api.mode = mode;
        runtime.renderOnce();
        api.status = 'ready';
        applyPlaybackState();
        stage.style.opacity = sampleIdentity && sampleIdentity !== api.identity ? '0' : '1';
      } catch (error) { if (!destroyed && request === generation) fail(error); }
    });
    return queue;
  },
  clock(sample: any) {
    clock.accept(sample);
    sampleIdentity = sample.identity;
    advancing = sample.advancing && sample.validForMs > 0;
    bands = sample.bands ?? { power: 0, bass: 0, treble: 0 };
    if (sample.identity !== api.identity) {
      stage.style.opacity = '0';
      runtime?.setPaused(true); running = false;
      return;
    }
    if (api.status === 'ready') stage.style.opacity = '1';
    applyPlaybackState();
    const time = clock.lyricSeconds();
    if (foreground && (!advancing || !motion) && time !== lastDrawnTime) runtime?.renderOnce();
    lastDrawnTime = time;
  },
  setForeground(visible: boolean) { foreground = visible; applyPlaybackState(); },
  destroy() {
    destroyed = true; generation++; creating?.abort(); runtime?.destroy(); runtime = null;
    running = false; stage.replaceChildren();
  },
};
(window as any).KinaraFolia = api;
document.addEventListener('visibilitychange', () => api.setForeground(!document.hidden));
