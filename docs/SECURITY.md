# Security Notes

## Configuration boundary

The public repository contains protocol code and non-secret public endpoints. It must not contain Cloudflare API tokens, Bilibili cookies, Solara secrets, signing keys, APKs, or machine-specific configuration. Copy `local.properties.example` to `local.properties` for a local build and keep the copy untracked.

Public builds enable free public sources without private configuration. Solara and private Bilibili Worker access are disabled when their configuration is absent. Local builds may inject `kinara.solaraBaseUrl`, `kinara.solaraSecret`, comma-separated `kinara.biliWorkerBases`, and `kinara.biliWorkerToken` through Gradle properties, `local.properties`, or environment variables. The last value is a service access key, never a Bilibili Cookie. A configured APK contains its private service configuration; do not redistribute that APK as a public artifact. GitHub CI explicitly empties all four settings.

## Network rules

- Text APIs go through `OnlineTransport`, which runs on `Dispatchers.IO`, limits responses to 2 MiB, closes the active connection on cancellation, and only follows default-port redirects on the same host without an HTTPS downgrade.
- Player and Coil requests use a separate OkHttp client with automatic redirects disabled. Every hop is validated before connecting. Audio candidates are restricted to provider CDN suffixes and configured Bilibili Worker hosts. Cookie, Authorization, and the previous target's service key are stripped; a service key is attached only to its configured HTTPS origin and API path. Signed streams use a short-lived, recording-scoped HMAC ticket instead, including for system downloads.
- The Worker accepts BVIDs rather than caller-provided upstream URLs, validates audio CDN redirects, preserves range semantics, and returns an explicit error for upstream challenges.
- Cover URLs are upgraded to HTTPS, restricted to provider image domains, and rejected when they contain credentials, fragments or non-default ports. Offline artwork requests explicitly disable the network.
- The platform DownloadManager performs its own network requests after the application has checked the URL and audio header; the application cannot enforce its per-hop network policy inside that system service. A completed download is checked again before it is exposed as audio.

## Storage and controllers

The settings file may contain locally configured Solara credentials, so it is excluded from both cloud backup and device transfer. Bilibili account cookies exist only in the private Cloudflare Worker's Secret, not in Android storage or APKs. The application has no QR login flow and removes old encrypted session files and the old Keystore key on upgrade. The Worker refuses unauthenticated private requests, sends account cookies only to the official API, and does not forward them to clients or audio CDNs. Library data remains eligible for backup; settings must be reconfigured after migration.

External MediaSession controllers retain playback/seek controls but cannot replace or mutate the queue. Downloads retain the actual recording's online ID after source fallback, while preserving the original request for retry and deduplication. Private copies from non-persistable file providers are limited to 512 MiB and support cancellation between read chunks.

## Dependency provenance

Use official repositories and committed lockfiles. The Gradle distribution checksum, artifact checksums in `gradle/verification-metadata.xml`, and all GitHub Actions references are pinned. The current Kotlin plugin has a known build-cache deserialization advisory; Gradle build-cache reuse, Kotlin incremental compilation and CI Gradle cache restoration are disabled pending a compatible toolchain upgrade. These mitigations do not count as upgrading the affected plugin. Install npm dependencies with `npm ci --ignore-scripts`; inspect any necessary installation script before explicitly enabling it. Renderer changes must rebuild the bundle; CI independently rebuilds and compares it. See [the supply-chain review](SUPPLY_CHAIN_REVIEW.md) for source hashes, concrete findings and the limits of the audit.

## Reporting

Do not paste tokens, cookies, signed audio URLs, signing passwords, or full local paths into issues. Redact those values before attaching diagnostics. The Android diagnostic report intentionally records typed numeric events instead of exception messages and URLs.
