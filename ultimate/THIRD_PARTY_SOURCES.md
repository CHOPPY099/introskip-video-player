# Third-Party Source Information

Ultimate Player 0.1.0 preview uses the unmodified Maven artifact `org.videolan.android:libvlc-all:3.7.7`. No decompiled VLC application code, extracted VLC application binaries, or VLC artwork is used.

## Sources Supplied With the Release

The GitHub release `ultimate-player-v0.1.0-preview` contains:

- `Ultimate-Player-0.1.0-source.zip`: this project's application source, resources, tests, documentation and Gradle configuration at the release commit. The original apps remain available in the repository but are not required to inspect Ultimate Player.
- `libvlcjni-3.7.7-source.zip`: unmodified LibVLC Android bindings, JNI, patches and build scripts from revision `0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6`. Its `build.gradle` declares version 3.7.7.
- `vlc-84177b2273abc5c4300234e6e18b55a4d2dd5a03.tar.gz`: unmodified VLC native-engine source at the revision pinned by that JNI revision's `buildsystem/get-vlc.sh`. Includes native component copyright/license files, contrib dependency recipes, checksums and patches.
- `libvlc-all-3.7.7-sources.jar`: the exact Java source artifact published with the Maven dependency. This is not a substitute for the native source archives above.
- `SHA256SUMS.txt`: checksums of the APK and these source archives.

## Upstream References

- [LibVLC JNI pinned source](https://code.videolan.org/videolan/libvlcjni/-/tree/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6)
- [Native VLC pinned source](https://code.videolan.org/videolan/vlc/-/tree/84177b2273abc5c4300234e6e18b55a4d2dd5a03)
- [Maven Java source artifact](https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7-sources.jar)
- [Maven dependency metadata and declared license](https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7.pom)
- [VideoLAN dependency source mirror](https://download.videolan.org/contrib/)

Native dependency versions, source URLs, integrity checks and patches are recorded in the pinned VLC tree's `contrib/src/*/rules.mak`, `SHA512SUMS` and patch files. These recipes, not the current master branch, should be used when fetching dependency sources. External dependency tarballs are not repackaged inside the native-engine source archive.

## Rebuilding and Replacing LibVLC

Use the release application source and the build prerequisites in [README.md](README.md). The library is a separate Gradle dependency, not code merged into the application source. To use a rebuilt replacement AAR, replace the Maven `implementation` line in `ultimate/build.gradle` with `implementation files('libs/libvlc-replacement.aar')`, put the AAR there, retain any dependencies declared in the original POM, and rebuild the app. A rebuilt APK may require uninstalling the preview first if signed by a different key; export or otherwise preserve needed app data before doing so.

For the native library, consult the pinned JNI `README.md`, `buildsystem/compile-libvlc.sh`, `buildsystem/get-vlc.sh` and `buildsystem/gitlab/.gitlab-ci.yml`. The latter records the upstream build-container versions. The compiler supports selecting contrib licensing with `--license`; do not assume its default is the license configuration you intend to distribute. The preview itself uses the published Maven artifact unchanged, and no bit-for-bit native rebuild is claimed.

The application imposes no additional restriction on replacing the LGPL library or reverse engineering the application for debugging modifications to that library. Corresponding application source is provided to make rebuilding possible. Third-party source retains its upstream copyright and license terms.

## Notices

The APK includes `OPEN_SOURCE_NOTICES.txt`, `LGPL-2.1.txt`, `Apache-2.0.txt` and `Lucide-LICENSE.txt` under `assets/`. The JNI/native archives contain their own component notices and licenses. The upstream Maven POM declares LGPL 2.1 for LibVLC Android; individual native dependencies have their own terms.

AndroidX and Kotlin dependencies retain their Apache 2.0 terms. Interface icons come from Lucide revision `1ef60af944de207e0d5176905a0759ba12e6ee8d`, under its ISC and Feather-derived MIT notices. The test-only audiovisual fixture is an original generated test pattern, not a downloaded episode.

Ultimate Player is independent of VideoLAN and is not endorsed by it. This preview is not a certification of production-store or full transitive-license compliance.
