# Folia Lumiere for Kinara

Kinara 1.3.0 bundles the actual Folia 0.7.20 Lumiere Pixi/WebGL renderer. The paragraph compiler, scene catalog, light field, fog, particles, glyph composition and transitions come from Folia, copyright 2026 chthollyphile, AGPL-3.0. `vendor/folia/UPSTREAM.json` records the original file hashes; `vendor/folia/LICENSE` contains the license.

Rebuild offline assets with Node.js 20+:

```
cd visualizer
npm ci
npm run build
npm test
npm run test:browser
```

The Android build verifies the source and asset hashes in `bundle.properties`, so edited renderer sources cannot silently ship an old bundle. Browser tests launch a separate headless profile, using system Chrome at its standard Windows path when available. Elsewhere, run `npx playwright install chromium`, or set `FOLIA_CHROME_PATH` to a Chrome executable. CI installs Playwright Chromium explicitly. Tests cover actual WebGL rendering, timed words, ordinary LRC, seeking, pause, cold initialization with ongoing player anchors, quality changes, song replacement, context loss and disposal.

Kinara supplies the Android player clock, real decoded PCM band levels, local themes, native subtitles and pager gestures. No Electron application, online script, React shell, account bridge, custom remote themes or icon downloads run in the WebView. All audio remains in Media3. A blocked or lost WebGL context falls back to native lyrics; reduced motion uses the native highlighter. Graphics quality is user selectable; it is not automatically inferred from phone performance.

Upstream modifications:

- `lumiere/text/reveal.ts`: an ordinary LRC line lights together; never distribute nonexistent word timestamps across characters. Real timed words retain Folia's grapheme rendering.
- `lumiere/createLumierePixiRuntime.ts`: mobile ticker cap of 30 fps for Low and 60 fps for Balanced/Full. These are limits, not measured device performance.
- The build substitutes a local empty icon resolver. Kinara supplies no AI theme icons; the renderer's generated line art is retained.

Android integration source is in `app/src/main/java/com/kinara/music/ui/FoliaLyrics.kt`. This adapter and the web integration are AGPL-3.0; the existing Kinara native code retains GPL-3.0. Distribution of the combined application follows GPLv3 section 13 and the applicable AGPL requirements. Each APK is accompanied by the complete corresponding application/renderer source, lockfile, build scripts and third-party notices.
