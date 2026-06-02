# IntroSkip Android Video Player

Native Android APK for playing downloaded phone videos with a custom playlist.

## Features

- Scan the phone media library for videos.
- Pick specific video files with Android's file picker.
- Add only the videos you choose to the playlist.
- Search scanned phone videos by file name.
- Set a global intro skip time, such as 3 minutes, so every video starts after that point.
- Autoplay the next playlist video.
- Keep playing audio in the background with a foreground media service and lock-screen notification controls.

## APK

Built APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

This is a debug-signed APK, so Android may show an install warning. That is normal for a locally built APK.

## Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat" assembleDebug --offline
```
