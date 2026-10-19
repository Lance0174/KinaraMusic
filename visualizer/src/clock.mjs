// All visual time comes from Media3. The renderer never owns an independent playback timer.
export class AudioClock {
  constructor(now = () => performance.now(), wall = () => Date.now()) {
    this.now = now; this.wall = wall; this.sample = null; this.received = 0;
  }
  accept(sample) {
    const transit = Math.min(500, Math.max(0, this.wall() - sample.sentAtMs));
    this.sample = { ...sample, positionMs: sample.positionMs + (sample.advancing ? Math.min(transit, sample.validForMs) * sample.speed : 0),
      validForMs: Math.max(0, sample.validForMs - transit) };
    this.received = this.now();
  }
  audioMs() {
    const s = this.sample;
    if (!s) return 0;
    const elapsed = s.advancing ? Math.min(Math.max(0, this.now() - this.received), s.validForMs) * s.speed : 0;
    return Math.max(0, Math.min(s.durationMs > 0 ? s.durationMs : Infinity, s.positionMs + elapsed));
  }
  lyricSeconds() { return (this.audioMs() - (this.sample?.delayMs || 0)) / 1000; }
}
