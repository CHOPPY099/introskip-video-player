# VLC Android 3.7.1: Focused REA Investigation

Inspected on 18 October 2026 to inform the independent Ultimate Player app. This is a targeted static investigation of queue/resume architecture, not a complete reconstruction or runtime audit of VLC.

## Artifact Identity

- Latest stable release in VideoLAN's official `vlc-android/last` and release index when checked: 3.7.1. Beta releases were intentionally excluded.
- Download: https://download.videolan.org/pub/videolan/vlc-android/3.7.1/VLC-Android-3.7.1-arm64-v8a.apk
- Published checksum: same URL with `.sha256` appended.
- SHA-256 verified locally: `bea9ea0cdecfd8f461ef305d2afe5d1ac1459cfb9156acce2f615788a486de74`.
- Android SDK `aapt dump badging` corroborated package `org.videolan.vlc`, version 3.7.1, version code 13070106, minimum SDK 17 and target SDK 36.
- APK was downloaded for inspection, not installed over the user's VLC installation.

## Questions and Evidence

1. **Package inventory: answered with a provider limitation.** REA `inspect_android_package` reported 15,261 classes and 2,618 resources. Evidence `ev_1b49a6ca5627eac750c14ac54e2b5f517ae086e004071ba6e1daf40a0239e546`. Its manifest summary returned null package/version and an incomplete-looking manifest despite reporting complete status. That summary is not used as authoritative package identity; the SDK's binary-manifest parser supplied corroboration above.
2. **Separation of playback and playlist state: partially answered.** Class search found `org.videolan.vlc.media.PlaylistManager`, evidence `ev_21bce6dad0bb29f17f907426aaa0c50dc311de86714540e552bddeec694112f9`. Its member inventory includes `PlaybackService`, `PlayerController`, current/next/previous indices, resume state, background-video state and a media-player event listener. Evidence `ev_523dac4c850656de676aa47f3567096b4e97543a5f9378b02e35e5dc4306f1de`. This establishes static structure, not live reliability.
3. **Resume initialization: answered within one method.** Decompiled `getStartTime` selects stored media time or a saved-time fallback, with a reset flag and clearing of temporary saved time. Evidence `ev_aca060b865830f679cfe5d1bc376fb4c74dbcc17f6aba90d2167e8be68042540`. Decompiled control-flow structure may be imperfect; no exact DEX descriptors are supplied by this provider.
4. **Durable resume fields: answered within one method.** `savePosition(boolean, boolean)` persists current queue index and media time; for video it also saves playback rate. Incognito mode skips persistence. Evidence `ev_1b90ec9804e83b35ba389c0b15474d2df05da52166498dc82e719caf70fe34fc`.
5. **Next-item bounds: answered within one method.** `next(boolean)` saves metadata for relevant media, updates the next index, stops on an invalid index, handles background-video state and dispatches continuation asynchronously. Evidence `ev_5ec57492e066942b167f64984ffbcd65e17c03edeadf7fba00c9cc7398a325c4`.
6. **Visual behavior and battery-saving reliability: unresolved by this investigation.** Static decompilation cannot establish touch ergonomics, timing, Samsung lock-screen behavior, or hours-long background playback. Those require runtime tests of the resulting app.

Complete retained tool evidence is in the local, gitignored `rea-evidence.json`; the APK and checksum are retained alongside it locally. Raw decompiler output and downloaded binaries are not published with the application source.

## Independent Design Decisions

- Use the published, unmodified LibVLC Android library as the decoder, not extracted APK native binaries or copied application code. Maven's newest non-EAP stable library was 3.7.7 when checked, which has a separate version schedule from VLC Android 3.7.1.
- Make the playback service own its queue, progress and completed-event state. UI browsing must not silently replace the playing queue.
- Keep Ultimate Player's episode-specific intro skip, seasonal episode ordering, explicit destination playlist, resume, completion-only watched handling, newest-completed anchor, source browser and new-episode matching.
- Add a separate preview state so looking ahead does not move normal series continuation or scan cutoffs.
- Provide a compact native UI with virtualized lists, usable system insets, one primary playback-control region, selected audio language, subtitles and a sleep timer.
- Retain separate app identities: `com.choppy.ultimateplayer` does not overwrite `com.choppy.episodeflow`, `com.introskip.player`, or `org.videolan.vlc`.

These are our implementation decisions informed by observed structure, the user's requirements, and the prior EpisodeFlow audit. They are not claims that every VLC feature has been copied or reproduced.

## Licensing Boundary

No decompiled VLC application code, trademarks, or artwork was pasted into the new app. REA evidence remains local investigation material. LibVLC is used through its public Android API as an unmodified dependency. Its upstream notices and license texts are bundled in the new app. The preview release provides pinned source archives and build information described in [third-party sources](../../ultimate/THIRD_PARTY_SOURCES.md). This is not a Play Store compliance review.
