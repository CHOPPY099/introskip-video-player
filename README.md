# EpisodeFlow

EpisodeFlow is a native Android player for downloaded episodic video. It keeps IntroSkip's specialist playback behavior while presenting it through a cleaner library, playlist, history, player, and settings experience.

EpisodeFlow uses the application ID `com.choppy.episodeflow`, so it installs beside the original IntroSkip app (`com.introskip.player`) without replacing it.

## Core features

- Pick specific downloaded videos and choose the destination playlist.
- Scan Android's media library and search by filename.
- Match differently named releases from the same series.
- Extract episode numbers and sort episodes into watch order.
- Scan only for episodes newer than the current episode.
- Save a default intro-skip time and resume unfinished videos from their last position.
- Auto-play the next unwatched episode.
- Keep the most recently completed episode as the playlist anchor while removing older completed episodes.
- Continue as audio with the screen locked or while using other apps.
- Lock-screen seeking and previous, play/pause, and next controls.
- Picture-in-picture, fullscreen rotation, double-tap seek, playback speed, and control lock.
- Remember the selected audio language for later dual-audio episodes.
- Playlist source links, embedded browser navigation, download-link handoff, and download scanning.
- Per-video history reset and playlist-wide progress reset.

## EpisodeFlow 1.0.0 APK

[Download EpisodeFlow 1.0.0](https://github.com/CHOPPY099/introskip-video-player/releases/download/episodeflow-v1.0.0/EpisodeFlow-1.0.0.apk)

The APK is debug-signed for direct personal installation. A Play Store submission should use a private release signing key and complete the store listing and data-safety forms.

## Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat" assembleDebug
```

The generated APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Original IntroSkip app

The original source remains on the `main` branch. Its latest direct-install release is [IntroSkip 2.23](https://github.com/CHOPPY099/introskip-video-player/releases/download/v2.23/IntroSkip-debug.apk).
