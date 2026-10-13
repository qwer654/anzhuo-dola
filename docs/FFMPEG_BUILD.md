# FFmpeg Android CLI build and last-frame contract

## What is built

`tools/build_android_ffmpeg.sh` builds the **official FFmpeg 7.1.1** release source archive:

- URL: `https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz`
- SHA-256: `733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1`
- release signature: `https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz.asc`

The script refuses an archive whose pinned SHA-256 differs. It uses only FFmpeg's native LGPL components (no GPL/nonfree switches and no external codec libraries). Preserve the FFmpeg source offer, its `COPYING.LGPLv2.1`, and attribution in the APK's open-source notices before distribution. If build options change to include GPL components, the app distribution obligations change as well.

The resulting files are:

```
app/src/main/jniLibs/arm64-v8a/libffmpegexec.so
app/src/main/jniLibs/x86_64/libffmpegexec.so
```

Despite the `.so` package name, this is a PIE command-line executable. The name makes Gradle package it as an Android native library; `FfmpegLastFrame` executes its extracted path from `ApplicationInfo.nativeLibraryDir`.

The selected feature set is deliberately small: local-file protocol, MOV/MP4 and Matroska demuxing, H.264/HEVC/VP9/AV1/MJPEG decoders, MJPEG encoder, image2 muxer, and the scale/rotation filters. AV1 here is FFmpeg's native decoder; no unpinned external library is pulled in.

## Prerequisites and reproducible command

Run on an official Ubuntu runner (or Linux VM/WSL2) with Bash, curl, tar/xz, make, and an Android NDK. Android's NDK guide recommends the LLVM `--target=<triple><api>` form and documents the relevant ABI triples. This repository has `minSdk 28`, so the script defaults to API 28.

Example CI step (pin the runner's NDK action/version in the workflow that owns toolchain setup):

```bash
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT" # NDK r27+; r27c is a tested baseline
export ANDROID_API=28
export ABIS='arm64-v8a x86_64'
bash tools/build_android_ffmpeg.sh
./gradlew --no-daemon :app:assembleDebug
```

The build cache is `${FFMPEG_CACHE_DIR:-.cache/ffmpeg}`. It may be cached by CI, but the script verifies the archive hash on every use and unpacks a fresh source tree each run. For an arm64-only Xiaomi release use `ABIS=arm64-v8a`; retain `x86_64` for the Android 15 emulator.

## APK packaging requirement

The executable must be **extracted** from the APK, not memory-mapped from a compressed entry. The Gradle/application owner must set the equivalent of:

```groovy
android {
  packaging {
    jniLibs { useLegacyPackaging = true }
  }
}
```

(or the matching `android:extractNativeLibs="true"` setting for the installed AGP). Do not rename or load it with `System.loadLibrary`: it is launched as a process. Verify packaging before device tests:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'lib/.*/libffmpegexec.so'
adb shell run-as com.doulala.android.debug ls -l /data/app/*/lib/*/libffmpegexec.so
```

Use the package name of the selected variant for the second command. A successful result requires the extracted file to exist for the device ABI.

## Last-frame behavior

`FfmpegLastFrame.extract(context, stagedVideo, outputJpg, cancellation)` accepts only app-private staged files and runs this argument sequence (each token is passed via `ProcessBuilder`, never a shell):

```
libffmpegexec.so -i INPUT -map 0:v:0 ... -vf scale=... -c:v mjpeg \
  -f image2 -update 1 TEMP.jpg
```

`-update 1` overwrites the same temporary JPEG after each **decoded** frame. FFmpeg therefore decodes video from its beginning to EOF, commits only after exit code 0, and validates JPEG SOI/EOI plus minimum length. It deliberately does **not** use `-sseof`/seek-near-tail because such a seek can select a keyframe or miss the final decoded frame. FFmpeg autorotation is enabled by default; the output is bounded to a 1280-pixel edge while preserving aspect ratio.

This has linear decode cost and a 120-second default bound. Run it on a worker thread; use `CancellationSignal` for user cancellation. The wrapper drains at most 32 KiB of retained process diagnostic text while continuing to consume output, validates the exit code and JPEG, atomically commits in the same private directory, and cleans failed/cancelled temporary outputs.

## Device verification suggestion

1. Stage a short known video into `context.getCacheDir()` and call `FfmpegLastFrame.extract` off the UI thread.
2. Independently create an expected final frame on a trusted development machine by fully decoding to image2 with the same `-update 1` method (not a seek).
3. Compare decoded JPEG dimensions/orientation and use a tolerant pixel comparison (JPEG encoding is lossy).
4. Repeat on an arm64-v8a Xiaomi and an x86_64 Android 15 emulator; test cancellation and a corrupt/zero-video input.
5. Confirm the input and output never leave app-private directories, process exit is zero, and no temporary `.jpg` remains.

Sources: [FFmpeg download and signing guidance](https://ffmpeg.org/download.html), [Android NDK cross-compilation guide](https://developer.android.com/ndk/guides/other_build_systems).

## Source provenance and executable acceptance checks

The source location is FFmpeg's own release host, and the release's adjacent detached signature is retained above. The pinned digest is an independent build input: a cached archive cannot silently become a different source tree. Before deliberately changing `FFMPEG_VERSION` or its digest, a release owner must download both from `ffmpeg.org`, verify the detached release signature against FFmpeg's signing key obtained and fingerprint-checked through FFmpeg's official download/signing page, and review the new source license. Do not replace this with a prebuilt binary or a checksum supplied by an untrusted mirror.

After CI builds, record these checks as artifacts:

```bash
# Android PIE executable (ET_DYN is expected for a PIE executable).
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h \
  app/src/main/jniLibs/arm64-v8a/libffmpegexec.so | grep 'Type:'
# Ensure the build did not accidentally enable GPL/nonfree/external libraries.
grep -E '^(configuration:|FFMPEG_CONFIGURATION=)' .cache/ffmpeg/build-arm64-v8a/config.*
```

On each target ABI, launch the extracted executable **as the app UID** and check its runtime feature tables before calling the wrapper. Substitute the current package and actual native-library path displayed by the package manager:

```bash
adb shell run-as com.doulala.android.debug \
  /data/app/.../lib/arm64/libffmpegexec.so -hide_banner -decoders
adb shell run-as com.doulala.android.debug \
  /data/app/.../lib/arm64/libffmpegexec.so -hide_banner -muxers
adb shell run-as com.doulala.android.debug \
  /data/app/.../lib/arm64/libffmpegexec.so -hide_banner -filters
```

Acceptance is that the binary launches (rather than `Permission denied` or linker failure) and lists `h264`, `hevc`, `vp9`, `av1`, `mjpeg`, image2, and scale. If direct execution is blocked on a target image, do not fall back to a downloaded executable: fix the app's extracted-native-library packaging or add a deliberately reviewed native launcher.
