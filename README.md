# CineFlow TV

Native Android TV / Fire TV client for Stremio accounts.

- Native Kotlin UI (no WebView in normal navigation).
- Account linking through Stremio QR login.
- Addons, library, continue watching, catalog rows and native metadata.
- Native ExoPlayer playback with torrent support.
- Channels and EPG guide.
- GitHub release updates from inside the app.

## Build

The Android project is in `tv/`.

```bash
cd tv
./gradlew assembleRelease
```

Release APKs are published by the `CineFlow TV release` workflow and attached as
`CineFlow.apk`. The app checks the latest GitHub release on launch and from
**Settings → Buscar actualizaciones**.

The update APK must always be signed with the same key as the installed app.
The signing keystore is stored in the repository's `CINEFLOW_KEYSTORE_B64`
secret; its password is in `CINEFLOW_KEYSTORE_PASSWORD`.

## Versioning

The version lives in `tv/app/build.gradle.kts` as `cineflowVersion`. Publish a
GitHub release tagged `vX.Y.Z`; the workflow builds and attaches the APK.
