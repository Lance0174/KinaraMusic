# Open-source notices

Kinara native code: Copyright (C) 2026 Kinara contributors, GPL-3.0. See `licenses/GPL-3.0.txt`.

Folia Lumiere, Sonnet and Tempera renderers: Copyright (c) 2026 chthollyphile, AGPL-3.0. Source: https://github.com/chthollyphile/folia-major, reviewed snapshot version 0.7.20, commit `1b39fd868e2ddda95788f1baa839c032f3e7a285`. See `visualizer/vendor/folia/LICENSE`, `UPSTREAM.json` and `visualizer/README.md` for provenance and modifications. The Kinara Folia bridge is AGPL-3.0. The combined distribution follows GPLv3 section 13 and applicable AGPL requirements; distributors must provide the complete corresponding source and build scripts alongside the APK. User-facing product branding is Kinara; upstream copyright and licenses remain in these notices and the APK's open-source license content.

PixiJS, Pretext and their bundled dependencies retain their respective licenses. Their complete notices are generated from the pinned npm packages into `app/src/main/assets/folia/THIRD-PARTY.txt` and are included in both APK and source releases. The `@pixi/colord` npm archive omits its MIT license text; a copy from the declared upstream repository, https://github.com/omgovich/colord/blob/master/LICENSE.md, is retained in `visualizer/vendor/licenses/colord.txt`.

AndroidX / Media3 / Compose are Apache-2.0; Kotlin and kotlinx.coroutines are Apache-2.0; Coil is Apache-2.0. Existing source copyright notices are preserved.

Miuix 0.5.1 (`top.yukonga.miuix.kmp:miuix-android`) is Copyright 2025 miuix-kotlin-multiplatform contributors, Apache-2.0. Source: https://github.com/compose-miuix-ui/miuix/tree/v0.5.1. Kinara uses its native theme, scaffold, cards, navigation, tabs, text fields, settings switches and selectors. The unmodified upstream license is included at `app/src/main/assets/licenses/miuix-Apache-2.0.txt` and in the app's open-source license reader.

ZXing is **not** a current dependency: the QR/scancode feature it served has been removed, and neither the Gradle files nor the Kotlin sources reference it any more. Its Apache-2.0 text is still shipped in `app/src/main/assets/licenses/` purely as historical attribution for releases that did bundle it, and is deliberately kept rather than deleted so earlier releases' attribution is not lost.
