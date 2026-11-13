<div align="center">

# 🏷️ Retagger

**A simple tag editor for your music files on Android. Single files or whole albums at once.**

[![Build](https://github.com/aikitori/retagger/actions/workflows/android.yml/badge.svg)](https://github.com/aikitori/retagger/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/aikitori/retagger)](https://github.com/aikitori/retagger/releases/latest)
![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

[**Download APK**](https://github.com/aikitori/retagger/releases/latest/download/retagger.apk)

</div>

## Features

- **All common formats:** MP3 (ID3v1/v2), FLAC, Ogg Vorbis, M4A/M4B, WMA, WAV, AIFF, DSF.
- **Edit one file:** title, artist, album, album artist, year, track/total, disc/total, genre, composer, comment and cover.
- **Edit many files:** select an album and set the shared fields once. Fields you don't touch stay as they are.
- **Number tracks:** 1 to n in file name order, total included.
- **Suggestions:** values guessed from file and folder names (e.g. `Artist/Album (2004)/03 - Song.mp3`) appear as chips under the fields.
- **Tags from file names:** `%track% - %artist% - %title%` turns `03 - Band - Song.mp3` into tags, with a live preview.
- **Rename files from tags:** the same patterns the other way round.
- **Convert to MP3:** M4A, FLAC, OGG, WAV and more, one file or a whole selection. VBR or CBR, tags and cover are copied, originals can be deleted afterwards.
- **Covers:** pick any image from the gallery, large images are scaled down to 1000 px.
- **Only the folder you choose:** access through Android's folder picker, no storage permission needed.
- English and German.

## Usage

1. Tap **Choose music folder** and pick the folder with your music.
2. Tap a file to edit it, or long-press to select several and tap **Edit selected**.
3. More actions (tags from file name, rename) are in the ⋮ menu.

Placeholders: `%title%` `%artist%` `%album%` `%albumartist%` `%year%` `%track%` `%tracktotal%` `%disc%` `%disctotal%` `%genre%` `%composer%`.
Track and disc numbers are written with two digits when renaming; underscores in file names become spaces.

## Development

```
core/          tag reading/writing (jaudiotagger + own MP4 writer), file name patterns, suggestions. Plain JVM, unit tested
app/           Android app (Kotlin, Jetpack Compose), folder access via the Storage Access Framework
app/src/main/cpp/  LAME 3.100 MP3 encoder (unchanged, built as libmp3lame.so) and its JNI bridge
```

```sh
./gradlew :core:test            # works without the Android SDK
./gradlew :app:assembleDebug    # needs the Android SDK and NDK
```

Every push to `main` builds a signed APK and publishes it as a GitHub release.

## License

MIT. Tag handling by [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) (LGPL), MP3 encoding by [LAME](https://lame.sourceforge.io/) (LGPL, source in `app/src/main/cpp/lame`).
