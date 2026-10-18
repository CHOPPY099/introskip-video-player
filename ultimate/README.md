# Ultimate Player

Independent native Android episodic video player, powered by the unmodified VideoLAN LibVLC Android library. Package `com.choppy.ultimateplayer` installs beside EpisodeFlow and IntroSkip. Existing apps, videos, settings and playlists are not overwritten. Private playlists are not automatically imported from another app.

## Preview 0.1.0

- Compact Player, Playlists, Library, History and Settings navigation with Android system-bar insets.
- Video remains visible inside the active playlist. Virtualized episode rows show progress and watched state.
- File picker and device scan, explicit playlist destination, mixed-filename series matching, numeric episode ordering and a confirmation checklist before adding matches.
- New-episode scans use the normal series position, not a temporary preview of a later episode.
- Saved intro offset for new videos; existing progress takes precedence. Per-video reset clears both progress and watched state.
- Service-owned queue, audio-only background playback, MediaSession seek/play/pause/previous/next and a playback-scoped CPU wake lock.
- Only decoder end-of-file events mark completion. Manual Next does not mark watched. Older completed entries leave the queue; the latest completed entry stays. Files are never deleted by completion.
- Explicit Preview episode and Return to series preserve your normal episode and position.
- Dual-audio selection remembered per series, embedded subtitle selection, speed and sleep timer.
- Double-tap left/right seeks ten seconds; center toggles fullscreen. Single tap shows controls. Landscape video fits the viewport. Picture-in-picture is explicit, separate from Audio only.
- Per-playlist source browser remembers the last visited page, uses real navigation history and confirms direct downloads through Android DownloadManager. It does not guess episode URLs, automate subscriptions, bypass restrictions or download protected streams.

This is not a VLC clone or a complete implementation of every VLC feature. No decompiled VLC application code or branding is shipped. See [REA investigation](../research/vlc-3.7.1/ANALYSIS.md) and [QA results](QA.md).

## Build

Requirements: JDK 17+, Android SDK platform 36, Gradle 8.14.3. The root build uses Android Gradle Plugin 8.10.1. Android 8.0+ is supported by the manifest; runtime testing currently targets Android 15. The APK includes arm64-v8a and x86_64.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$gradle = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat"
& $gradle :ultimate:assembleDebug :ultimate:compileDebugUnitTestJavaWithJavac :ultimate:lintDebug :ultimate:assembleDebugAndroidTest
& .\ultimate\test-policies.ps1
```

APK: `ultimate/build/outputs/apk/debug/ultimate-debug.apk`. Debug signing is for personal testing, not a production signing strategy.

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb -s emulator-5580 install -r ultimate/build/outputs/apk/debug/ultimate-debug.apk
& $adb -s emulator-5580 install -r ultimate/build/outputs/apk/androidTest/debug/ultimate-debug-androidTest.apk
& $adb -s emulator-5580 shell am instrument -w -r com.choppy.ultimateplayer.test/androidx.test.runner.AndroidJUnitRunner
```

Instrumentation tests use a generated twelve-second, dual-audio test pattern in the test APK only. They modify this app's emulator test data and must not be run against someone's real playlists. The policy test script works around a Gradle worker argument-file quoting failure observed with this Windows user's apostrophe-containing path.

## Source and Notices

The GitHub preview release includes matching application source, pinned LibVLC JNI and native-engine source archives, the published Java source artifact, and checksums. See [third-party source information](THIRD_PARTY_SOURCES.md) for revisions, upstream build scripts, dependency-source locations and replacement instructions. The distributed APK is the tested, unmodified preview binary.

## Preview Limitations

- Long Samsung power-saving/lock-screen playback and physical-device audio output require testing; an emulator is not equivalent.
- Test older Android versions, content-provider permission revocation, SD-card removal, large libraries and OEM PiP/lock-screen behavior.
- The module can be rebuilt against a replacement LibVLC library. Upstream notices and license texts are included in assets and source archives. A full transitive licensing review remains part of production-release preparation.
- Add release signing, store privacy/data-safety disclosures, translation resources and broader accessibility testing before Play Store submission.
- Browser downloads require an actual HTTP(S) downloadable file and access provided by the user. Older Android versions without storage access offer an external-browser fallback.
- MediaStore/document-picker aliases are reconciled when Android exposes a shared indexed-media identity; unrelated providers may still represent the same file with unresolvable IDs.
