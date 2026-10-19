/* Kinara integration — AGPL-3.0. Folia renderer retains its upstream copyright/license. */
import { LumierePixiRuntime } from '../vendor/folia/src/components/visualizer/lumiere/createLumierePixiRuntime';
import { compileLumiereProgram } from '../vendor/folia/src/components/visualizer/lumiere/lumiereProgram';
import { DEFAULT_LUMIERE_TUNING } from '../vendor/folia/src/types';
import { AudioClock } from './clock.mjs';

const stage = document.getElementById('stage') as HTMLDivElement;
const clock = new AudioClock();
let runtime: LumierePixiRuntime | null = null;
let generation = 0;
let motion = true;
let foreground = true;
let advancing = false;
let destroyed = false;
let running = false;
let lastDrawnTime = Number.NaN;
let bands = { power: 0, bass: 0, treble: 0 };
const value = (get: () => number) => ({ get }) as any;
const audioPower = value(() => bands.power);
const audioBands = { bass: value(() => bands.bass), treble: value(() => bands.treble), mid: value(() => bands.power) } as any;
function applyPlaybackState() {
  if (!runtime) { running = false; return; }
  const next = foreground && advancing && motion;
  if (next !== running) { running = next; runtime?.setPaused(!next); }
}
const fail = (error?: any) => { api.status = 'failed'; api.failure = String(error?.message ?? 'WebGL unavailable').slice(0, 400); runtime?.destroy(); runtime = null; stage.replaceChildren(); };
window.addEventListener('error', fail);
window.addEventListener('unhandledrejection', fail);
stage.addEventListener('webglcontextlost', event => { event.preventDefault(); fail(); }, true);

const api = {
  status: 'loading',
  failure: '',
  identity: '',
  async setSong(song: any) {
    const request = ++generation;
    api.identity = song.identity;
    stage.style.opacity = '0'; // Never draw previous-song lyrics over a new audio identity.
    api.status = 'loading';
    try {
      motion = !song.reduceMotion;
      const program = compileLumiereProgram(song.lines, song.identity, {}, { duration: song.durationMs / 1000, seamless: true });
      const theme = { name: 'Kinara', backgroundColor: '#070b12', primaryColor: '#fff6e5', secondaryColor: '#aea99e',
        accentColor: song.industrial ? '#efc647' : '#a9c7f5', fontStyle: 'sans', fontFamily: 'sans-serif',
        animationIntensity: 'calm', lyricsIcons: [], wordColors: [] } as any;
      const tuning = { ...DEFAULT_LUMIERE_TUNING, renderQuality: song.quality, themeIcons: false, overlayFrame: false };
      if (!runtime) {
        const created = await LumierePixiRuntime.create({ host: stage, song: { seed: song.identity, program, theme },
          tuning, currentTime: value(() => clock.lyricSeconds()), audioPower, audioBands,
          staticMode: !motion, showText: song.lines.length > 0, paused: true,
          metadata: { title: song.title, artist: song.artist, album: song.album } });
        if (destroyed || request !== generation) { created.destroy(); return; }
        runtime = created;
      } else {
        // Pause makes same-song and new-song replacement atomic, including while seeking.
        runtime.setPaused(true);
        running = false;
        await runtime.swapSong({ seed: song.identity, program, theme });
        if (destroyed || request !== generation) return;
        runtime.setSongMetadata({ title: song.title, artist: song.artist, album: song.album });
        runtime.setTuning(tuning);
        runtime.setShowText(song.lines.length > 0);
        runtime.setStaticMode(!motion);
      }
      runtime.renderOnce();
      applyPlaybackState();
      stage.style.opacity = '1';
      api.status = 'ready';
    } catch (error) { if (request === generation) fail(error); }
  },
  clock(sample: any) {
    clock.accept(sample);
    advancing = sample.advancing && sample.validForMs > 0;
    bands = sample.bands ?? { power: 0, bass: 0, treble: 0 };
    if (sample.identity !== api.identity) {
      stage.style.opacity = '0';
      runtime?.setPaused(true); running = false;
      return;
    }
    applyPlaybackState();
    // Paused scrubbing and reduced motion still display the exact current lyric time.
    const time = clock.lyricSeconds();
    if (foreground && (!advancing || !motion) && time !== lastDrawnTime) runtime?.renderOnce();
    lastDrawnTime = time;
  },
  setForeground(visible: boolean) {
    foreground = visible;
    applyPlaybackState();
  },
  destroy() { destroyed = true; generation++; runtime?.destroy(); runtime = null; stage.replaceChildren(); },
};
(window as any).KinaraFolia = api;
document.addEventListener('visibilitychange', () => api.setForeground(!document.hidden));
