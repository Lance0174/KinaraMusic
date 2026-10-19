import { test } from 'node:test';
import assert from 'node:assert/strict';
import { VISUAL_STYLES, resolveVisualStyle } from '../src/styles.mjs';
import { build } from 'esbuild';

test('automatic styles are stable per recording and use all three directors', () => {
  const modes = new Set();
  for (let i = 0; i < 30; i++) {
    const identity = `netease:${i}`;
    const mode = resolveVisualStyle('auto', identity);
    assert.equal(mode, resolveVisualStyle('auto', identity));
    modes.add(mode);
    for (const explicit of VISUAL_STYLES) assert.equal(resolveVisualStyle(explicit, identity), explicit);
  }
  assert.deepEqual([...modes].sort(), [...VISUAL_STYLES].sort());
});

test('ordinary LRC reveals the whole line; real word timing remains authoritative', async () => {
  const result = await build({ entryPoints: ['vendor/folia/src/utils/lyrics/graphemeTiming.ts'], bundle: true, format:'esm', write:false, platform:'node' });
  const { buildLineGraphemeTimeline } = await import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`);
  const line = { fullText:'光落在你眼底', startTime:4, endTime:8, words:[] };
  assert.ok(buildLineGraphemeTimeline(line).every(x => x.startTime === 4 && x.endTime === 4));
  const timed = buildLineGraphemeTimeline({...line, words:[{text:'光落在',startTime:4,endTime:5},{text:'你眼底',startTime:6,endTime:8}]});
  assert.equal(timed[0].startTime,4); assert.equal(timed[3].startTime,6); assert.equal(timed.at(-1).endTime,8);
});
