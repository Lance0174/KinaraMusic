export const VISUAL_STYLES = ['lumiere', 'sonnet', 'tempera'];

// Stable per recording: seeking, restarting and metadata refresh keep the same director.
export function resolveVisualStyle(style, identity = '') {
  if (VISUAL_STYLES.includes(style)) return style;
  let hash = 2166136261;
  for (const char of identity) hash = Math.imul(hash ^ char.codePointAt(0), 16777619);
  return VISUAL_STYLES[(hash >>> 0) % VISUAL_STYLES.length];
}
