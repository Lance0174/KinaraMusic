import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AudioClock } from '../src/clock.mjs';
test('audio timing remains anchored through bridge latency, rate changes, seeking and stale samples', () => {
  let now = 0, wall = 100;
  const clock = new AudioClock(() => now, () => wall);
  const sample = { positionMs: 1000, sentAtMs: 90, validForMs: 500, speed: 1, advancing: true, durationMs: 10000, delayMs: 200 };
  clock.accept(sample); assert.equal(clock.audioMs(), 1010);
  now = 100; assert.equal(clock.lyricSeconds(), .91);
  now = 5000; assert.equal(clock.audioMs(), 1500);
  clock.accept({...sample, positionMs: 500, advancing: false}); now += 200; assert.equal(clock.audioMs(), 500);
  clock.accept({...sample, positionMs: 250, speed: 2}); now += 100; assert.equal(clock.audioMs(), 470);
  clock.accept({...sample, positionMs: 9900, speed: 2}); now += 100; assert.equal(clock.audioMs(), 10000);
});
