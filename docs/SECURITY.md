# Security Notes

## Configuration boundary

The public repository contains protocol code and non-secret public endpoints. It must not contain Cloudflare API tokens, Bilibili cookies, Solara secrets, signing keys, APKs, or machine-specific configuration. Copy `local.properties.example` to `local.properties` for a local build and keep the copy untracked.

Solara and anonymous Bilibili Worker access are disabled when their build-time configuration is absent. Local builds may inject `kinara.solaraBaseUrl`, `kinara.solaraSecret`, and comma-separated `kinara.biliWorkerBases` through Gradle properties, `local.properties`, or environment variables. A configured APK necessarily contains its own local source configuration; do not redistribute that APK as a public source artifact. Remote builders must provide their own endpoints and credentials.

## Network rules

- Text APIs go through `OnlineTransport`, which runs on `Dispatchers.IO`, limits responses to 2 MiB, and closes connections on cancellation.
- Audio candidates are restricted to known provider CDN suffixes and the Bilibili Worker hosts configured in the current APK. Credentials and cookies are not forwarded to audio CDNs.
- The Worker accepts BVIDs rather than caller-provided upstream URLs, validates audio CDN redirects, preserves range semantics, and returns an explicit error for upstream challenges.
- Cover URLs are upgraded to HTTPS and rejected when they contain credentials or non-default ports.

## Reporting

Do not paste tokens, cookies, signed audio URLs, signing passwords, or full local paths into issues. Redact those values before attaching diagnostics. The Android diagnostic report intentionally records typed numeric events instead of exception messages and URLs.
