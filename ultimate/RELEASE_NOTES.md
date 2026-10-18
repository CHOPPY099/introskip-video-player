# Ultimate Player 0.1.0 Preview

Download **Ultimate-Player-0.1.0-preview.apk** from the release assets. Android 8.0+ with arm64-v8a or x86_64 is required.

Installs as **Ultimate Player** beside EpisodeFlow and IntroSkip. Your existing apps are not replaced. Their private playlists and settings are not automatically imported; choose your video files and create playlists in the new app.

## Included

- LibVLC playback with a compact Player, Playlists, Library, History and Settings interface.
- Saved intro skipping, resume positions, automatic next episode and background audio.
- Episode ordering and series matching across different filename formats.
- Separate episode preview and return-to-series controls.
- Completion-only watched handling that keeps the newest completed episode; video files are never deleted automatically.
- Remembered audio-language selection, embedded subtitles, speed and sleep timer.
- Lock-screen MediaSession controls, fullscreen gestures, seeking and explicit picture-in-picture.
- Per-playlist source browser with remembered pages and confirmed direct-file downloads.

## Verification and Limits

42 policy tests and 13 Android integration tests passed on an Android 15 emulator. APK signature and ZIP alignment checks passed. Samsung lock-screen behavior and hours-long power-saving playback have not been verified on a physical phone.

This is a debug-signed comparison preview, not a Play Store production release. See the tagged source's `ultimate/QA.md` for detailed coverage and limitations.

Matching application source, pinned LibVLC source archives, build information and checksums are included. See `ultimate/THIRD_PARTY_SOURCES.md`. No decompiled VLC application code or VLC branding is included.
