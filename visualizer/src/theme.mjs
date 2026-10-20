/* Kinara theme input for the original Folia visual directors — AGPL-3.0. */
const mix = (left, right, amount) => {
  const channels = hex => [1, 3, 5].map(start => parseInt(hex.slice(start, start + 2), 16));
  const a = channels(left), b = channels(right);
  return '#' + a.map((value, i) => Math.round(value + (b[i] - value) * amount).toString(16).padStart(2, '0')).join('');
};

// Album colors are decoded and bounded on Android. The WebView never fetches images or URLs.
export function songTheme(song) {
  const colors = (song.coverColors ?? []).filter(color => /^#[0-9a-f]{6}$/i.test(color));
  const accent = song.industrial ? '#efc647' : colors[0] ? mix(colors[0], '#ffffff', 0.35) : '#a9c7f5';
  return {name: 'Kinara', backgroundColor: colors[1] ? mix('#101522', colors[1], 0.22) : '#101522',
    primaryColor: '#fff6e5', secondaryColor: colors[2] ? mix(colors[2], '#c9d3e1', 0.55) : '#aea99e',
    accentColor: accent, fontStyle: 'sans', fontFamily: 'sans-serif',
    animationIntensity: 'normal', lyricsIcons: [], wordColors: []};
}
