/* Kinara WebView compatibility — AGPL-3.0. */
// esbuild lowers syntax, not built-ins. Android 12 can still have a pre-Chrome-92 WebView.
export function arrayAt(index) {
  if (this == null) throw new TypeError('Array.at requires an array-like receiver');
  const object = Object(this);
  const length = Math.min(Math.max(Math.trunc(+object.length) || 0, 0), Number.MAX_SAFE_INTEGER);
  const number = +index;
  const relative = Number.isNaN(number) ? 0 : Math.trunc(number);
  const offset = relative >= 0 ? relative : length + relative;
  return offset < 0 || offset >= length ? undefined : object[offset];
}
if (typeof Array.prototype.at !== 'function') {
  Object.defineProperty(Array.prototype, 'at', { value: arrayAt, writable: true, configurable: true });
}
