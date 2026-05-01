# Nextcloud Podcast (Android)

Android podcast client for Nextcloud/Subsonic-compatible podcast endpoints.

## Features

- Server credential settings
- Load podcast shows and episodes
- Add/delete podcast feeds
- Stream playback
- Offline episode download
- Played/unplayed state
- Resume position
- Scrobble calls
- Podcast discovery/search (iTunes API)
- Google Cast support

## Project

- Package: `com.joe.podcast`
- Main source: `app/src/main/java/com/joe/podcast`
- Build scripts:
  - `scripts/dev-env.sh`
  - `scripts/build.sh`
  - `scripts/install-run.sh`
  - `scripts/run-emulator.sh`

## Build

```bash
./scripts/build.sh
```

Or directly:

```bash
./gradlew assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Release APKs

APK files are published in GitHub Releases.

## Security Notes

- This repository excludes local machine config and build artifacts.
- Do not commit tokens, keys, or signing materials.
- Rotate any credential that was ever shared in plain text.
