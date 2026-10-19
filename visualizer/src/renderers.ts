/* Kinara renderer adapters — AGPL-3.0. Upstream Folia copyright/license retained. */
import { LumierePixiRuntime } from '../vendor/folia/src/components/visualizer/lumiere/createLumierePixiRuntime';
import { compileLumiereProgram } from '../vendor/folia/src/components/visualizer/lumiere/lumiereProgram';
import { SonnetPixiRuntime } from '../vendor/folia/src/components/visualizer/sonnet/createSonnetPixiRuntime';
import { compileSonnetProgram } from '../vendor/folia/src/components/visualizer/sonnet/sonnetProgram';
import { TemperaPixiRuntime } from '../vendor/folia/src/components/visualizer/tempera/createTemperaPixiRuntime';
import { compileTemperaProgram } from '../vendor/folia/src/components/visualizer/tempera/temperaProgram';
import { DEFAULT_LUMIERE_TUNING, DEFAULT_SONNET_TUNING, DEFAULT_TEMPERA_TUNING } from '../vendor/folia/src/types';

export interface Renderer {
  setPaused(paused: boolean): void;
  renderOnce(): void;
  destroy(): void;
  update(song: any): Promise<void>;
}

function settings(mode: string, song: any) {
  const resolution = song.quality === 'low' ? 1 : song.quality === 'full' ? 1.5 : 1.25;
  if (mode === 'sonnet') return { ...DEFAULT_SONNET_TUNING, textureResolution: resolution,
    showBackgroundDecor: false, outerFrameMode: 'none' };
  if (mode === 'tempera') return { ...DEFAULT_TEMPERA_TUNING, textureResolution: resolution,
    postProcessEnabled: song.quality === 'full', showCornerMarks: false,
    wholeLineLyrics: song.lines.some((line: any) => !line.words.length) };
  return { ...DEFAULT_LUMIERE_TUNING, renderQuality: song.quality, themeIcons: false, overlayFrame: false };
}
function theme(song: any) {
  return { name: 'Kinara', backgroundColor: '#070b12', primaryColor: '#fff6e5', secondaryColor: '#aea99e',
    accentColor: song.industrial ? '#efc647' : '#a9c7f5', fontStyle: 'sans', fontFamily: 'sans-serif',
    animationIntensity: 'normal', lyricsIcons: [], wordColors: [] } as any;
}
function context(mode: string, song: any) {
  const program = mode === 'sonnet' ? compileSonnetProgram(song.lines, song.identity)
    : mode === 'tempera' ? compileTemperaProgram(song.lines, song.identity, {
      wholeLineLyrics: song.lines.some((line: any) => !line.words.length) })
    : compileLumiereProgram(song.lines, song.identity, {}, { duration: song.durationMs / 1000, seamless: true });
  return { seed: song.identity, program, theme: theme(song), coverColors: [] } as any;
}
export async function createRenderer(mode: string, song: any, host: HTMLDivElement,
  currentTime: any, audioPower: any, audioBands: any, signal: AbortSignal): Promise<Renderer> {
  const ctx = context(mode, song);
  const tuning = settings(mode, song) as any;
  const options = { host, songSeed: song.identity, ...ctx, tuning, currentTime, audioPower, audioBands,
    lyricsFontScale: 1, staticMode: !!song.reduceMotion, transparentBackground: false, paused: true,
    songTitle: song.title, songArtist: song.artist, songAlbum: song.album, signal };
  const instance = mode === 'sonnet' ? await SonnetPixiRuntime.create(options)
    : mode === 'tempera' ? await TemperaPixiRuntime.create(options)
    : await LumierePixiRuntime.create({ ...options, song: ctx, showText: song.lines.length > 0,
      metadata: { title: song.title, artist: song.artist, album: song.album } });
  const fps = (next: any) => {
    if (instance instanceof SonnetPixiRuntime || instance instanceof TemperaPixiRuntime)
      instance.setMaxFps(next.quality === 'low' ? 30 : 60);
  };
  fps(song);
  return {
    setPaused: paused => instance.setPaused(paused),
    renderOnce: () => instance.renderOnce(),
    destroy: () => instance.destroy(),
    async update(next) {
      instance.setPaused(true);
      await instance.swapSong(context(mode, next));
      instance.setSongMetadata({ title: next.title, artist: next.artist, album: next.album });
      instance.setTuning(settings(mode, next) as any);
      if (instance instanceof LumierePixiRuntime) {
        instance.setShowText(next.lines.length > 0);
        instance.setStaticMode(!!next.reduceMotion);
      }
      fps(next);
    },
  };
}
