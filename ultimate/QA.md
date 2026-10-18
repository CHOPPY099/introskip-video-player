# Ultimate Player Preview Verification

Date: 18 October 2026. Device: local Android 15 / API 35 x86_64 emulator, 1080x1920, three-button navigation. No physical phone was connected or modified.

## Build and Policy Tests

- `:ultimate:assembleDebug`, instrumentation APK compilation and `:ultimate:lintDebug` succeed.
- Android lint: 0 errors, 34 warnings. Remaining warnings include localization, unused resources, target-SDK currency and explicitly managed wake-lock lifetime. This is not a claim of Play Store readiness.
- All 42 JUnit policy tests pass: 26 filename/series/episode cases, 8 playback-ledger cases, 8 browser URL/header/filename cases.
- The standard Gradle unit-test worker cannot launch in this Windows environment because of an argument-file/classpath quoting problem with the apostrophe in the user directory. Compiled tests were run with JUnitCore using `test-policies.ps1`; no test assertions were bypassed.
- The unchanged EpisodeFlow `app` module still builds with the updated root build tools. `git diff -- app` is empty.
- Final preview APK passes Android APK Signature Scheme v2 verification and ZIP alignment checks with 16 KiB native-library alignment requested. This ZIP check alone is not an ELF-segment compatibility audit.
- APK SHA-256: `89b5e40419c265828d35fa46b28556ee0da3be9ebdb3e5d612cf8e187f7b57ec`.

## Android Integration Coverage

Final full-suite result: **OK (13 tests)** in 96.852 seconds on the packaged candidate. The 42 policy tests also pass.

The thirteen instrumented cases use an original generated twelve-second MKV test pattern with two language-labelled audio tracks and embedded subtitles. This fixture is packaged only in the test APK.

1. Browser source-link persistence survives a library save from an older store instance.
2. Different MediaStore files with the same name are not incorrectly collapsed.
3. Indexed picker and scanner aliases reuse one identity and history-bearing URI.
4. The user's two One Piece filename formats sort by episode, not resolution.
5. Nonblank, changing decoded video pixels in portrait; video inside the playlist; next-episode video; background audio then foreground video; landscape rotation.
6. Selected audio language follows the next episode; pausing during preparation prevents delayed playback.
7. Seeking, stable paused position, speed change while paused, MediaSession millisecond seeking and an advancing published timeline.
8. Manual Next does not mark watched; previewing a later episode preserves the normal series anchor and returns to its position.
9. Six consecutive audio-only natural completions apply intro offsets and leave only the latest completed anchor in the queue.
10. Portrait transport buttons and seek bar stay visible within the video area and do not overlap.
11. Clearing an active queue clears its identity before recreating a playlist with the same name.
12. Continue series moves forward from a completed middle episode rather than jumping to an earlier unwatched episode.
13. Picture-in-picture displays moving video without navigation chrome, and returning restores the normal view and video.

The initial ten-case suite was also run after clearing this emulator app's data, to exercise a cold font cache. It passed after moving font-cache preparation off the playback path.

The MediaController test waits up to three seconds for Android's asynchronous published playing state before checking its speed. An earlier immediate read raced delivery and saw the preceding paused state; the test still asserts an advancing timeline after publication.

## Defects Found and Corrected

- A cold VLC subtitle font scan delayed playback startup. Preparation now runs on a worker, with a temporary no-text renderer until fonts are ready.
- Rebuilding UI rows destroyed the video surface during next-episode startup. Logcat showed `video output creation failed`. The attached video container is now preserved across these renders; pixel-motion tests cover the transition.
- Unconstrained control rows hid the seek bar. Explicit row heights and a geometry test prevent this regression.
- PiP screen-layout changes could recreate the activity without restoring PiP presentation. The manifest and lifecycle now preserve/recover the correct mode, including video-only chrome and insets.
- Active playlist deletion/recreation, stale background queue rows, continuation direction and indexed duplicate identities received targeted fixes.
- API-26 metadata retrieval now uses `release()` and a compatible frame extraction fallback.

## Limits

- No hours-long playback test in Samsung power-saving mode, OEM lock-screen drag test, Bluetooth route test or physical-device audio listening test was possible. MediaSession state and seeking were exercised on the emulator, not a Samsung lock screen.
- Six short near-end transitions are not equivalent to playing six full episodes over several hours.
- Older Android releases, SD cards, revoked provider permissions and very large libraries still need runtime coverage.
- The embedded browser's pure policy and source persistence are tested. Subscription-site login, expiring authenticated download links, redirects and successful large downloads were not tested against a real account.
- The APK is debug-signed for comparison testing. Matching application and pinned LibVLC source are supplied with the GitHub preview. Production release signing, a full transitive licensing review and Play Store privacy/accessibility/localization work remain.

Local screenshots and emulator logs are retained in the gitignored `research/qa/` directory. `08-verified-video.png` shows rendered video after the surface fix; `12-final-pip.png` shows the corrected video-only PiP window. Earlier captures include defects found during testing, not the final behavior.
