# Folia visual directors for Kinara

Kinara 1.4.0 bundles Folia 0.7.20's actual Lumiere, Sonnet and Tempera Pixi/WebGL renderers, copyright 2026 chthollyphile, AGPL-3.0. These supply three independent lyric-PV directors: volumetric light and particles, camera-driven kinetic typography, and block/collage compositions with text inversion. The existing Lumiere-only adapter did not expose this range. `vendor/folia/UPSTREAM.json` records all 181 original file hashes and the immutable upstream commit; the 100 added files were verified against GitHub tree blob IDs before adaptation. `vendor/folia/LICENSE` contains the license.

In the Android UI these live under **动态视界 → 流光 / 动态排字 / 凝彩**. The automatic option selects a director deterministically per recording. It never changes on a seek or metadata refresh. Kinara is the application brand; the repository and bundled legal notices identify Folia's contribution.

Rebuild offline assets with Node.js 20+:

```
cd visualizer
npm ci --ignore-scripts
npm run build
npm test
npm run test:browser
```

The Android build verifies the source and asset hashes in `bundle.properties`, so edited renderer sources cannot silently ship an old bundle. Browser tests launch a separate headless profile, using system Chrome at its standard Windows path when available. Elsewhere, run `npx playwright install chromium`, or set `FOLIA_CHROME_PATH` to a Chrome executable. CI installs Playwright Chromium explicitly. Tests cover actual WebGL rendering, timed words, ordinary LRC, seeking, pause, cold initialization with ongoing player anchors, quality changes, song replacement, context loss and disposal.

`src/compat.mjs` supplies `Array.at` on older Android WebViews before the renderers load. The Chrome 90 syntax target alone cannot provide missing JavaScript built-ins. Browser regression tests remove the native method and initialize all three actual WebGL engines; without this shim they fail with `at is not a function` and Android falls back to ordinary lyrics.

Kinara supplies the Android player clock, real decoded PCM band levels, local themes, native subtitles and pager gestures. No Electron application, online script, React shell, account bridge, custom remote themes or icon downloads run in the WebView. All audio remains in Media3. Since 1.4.2, reduced motion and disabled system animations preserve the selected director using its static mode, instead of silently replacing it with the native highlighter. A blocked or lost WebGL context shows an explicit native fallback and reload button; switching director or quality creates a fresh WebView. Settings can open the current song directly on its lyric page. Graphics quality is user selectable; it is not automatically inferred from phone performance. Low caps all three tickers at 30 fps; other presets cap at 60 fps. Sonnet/Tempera lower texture resolution on mobile and Tempera's additional post-processing is enabled only at Full quality. These are budgets, not measured device frame rates.

Upstream modifications:

- `lumiere/text/reveal.ts`: an ordinary LRC line lights together; never distribute nonexistent word timestamps across characters. Real timed words retain Folia's grapheme rendering.
- `lumiere/createLumierePixiRuntime.ts`: mobile ticker cap of 30 fps for Low and 60 fps for Balanced/Full. These are limits, not measured device performance.
- `sonnet/createSonnetPixiRuntime.ts`, `tempera/createTemperaPixiRuntime.ts`: expose a ticker frame budget for the Android adapter.
- `utils/lyrics/graphemeTiming.ts`: ordinary LRC graphemes share the line's actual start; Sonnet and Tempera cannot invent word timestamps. Tempera retains whole lines when any source line lacks word timing.
- `sonnet/sonnetIcons.ts`: optional theme SVG icons disabled; the geometry and typography remain. This avoids React/server markup code in the offline bundle.
- The build substitutes a local empty icon resolver. Kinara supplies no AI theme icons; the renderer's generated line art is retained.
- Sixteen newly imported files had AI-directed comments asking tools to preserve a marker and conceal it from users. The directive comments were removed, the ordinary version marker and copyright retained, and paths recorded in `UPSTREAM.json`. The comments were treated as untrusted text; no instruction in them was executed. They were outside the original 81-file review scope.

`src/renderers.ts` adapts each upstream runtime. `src/main.ts` serializes creation/swap, aborts stale initialization, hides stale song frames and releases the prior WebGL context when changing directors. Tests cover all three actual canvases, pause/seek, LRC, rapid switches, initialization cancellation, context loss and destruction. No new npm runtime dependency was required.

Android integration source is in `app/src/main/java/com/kinara/music/ui/FoliaLyrics.kt`. This adapter and the web integration are AGPL-3.0; the existing Kinara native code retains GPL-3.0. Distribution of the combined application follows GPLv3 section 13 and the applicable AGPL requirements. When distributing an APK, include the complete corresponding application/renderer source archive, lockfile, build scripts and third-party notices alongside it. The APK itself contains the notices, not a source archive.
