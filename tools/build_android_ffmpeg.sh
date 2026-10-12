#!/usr/bin/env bash
# Build the app-bundled FFmpeg command-line executable for Android.
# This intentionally runs only on Linux/macOS. Android's NDK documentation notes
# that Autoconf projects are generally not buildable on Windows; use an Ubuntu
# GitHub Actions runner, a Linux VM, or WSL2 (not WSL1).
set -euo pipefail

FFMPEG_VERSION="7.1.1"
FFMPEG_ARCHIVE="ffmpeg-${FFMPEG_VERSION}.tar.xz"
FFMPEG_URL="https://ffmpeg.org/releases/${FFMPEG_ARCHIVE}"
# SHA-256 of the official ffmpeg.org release tarball, pinned deliberately.
FFMPEG_SHA256="733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1"
ANDROID_API="${ANDROID_API:-28}"
ABIS="${ABIS:-arm64-v8a x86_64}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${FFMPEG_CACHE_DIR:-${ROOT}/.cache/ffmpeg}"
SOURCE_DIR="${CACHE_DIR}/ffmpeg-${FFMPEG_VERSION}"
OUT_JNILIBS="${ROOT}/app/src/main/jniLibs"

case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *) echo "This Autoconf build must run on Linux/macOS (on Windows use WSL2 or CI)." >&2; exit 2 ;;
esac

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "${NDK}" || ! -d "${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/bin" ]]; then
  echo "Set ANDROID_NDK_HOME to an Android NDK with ${HOST_TAG} LLVM tools." >&2
  exit 2
fi
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 2; }; }
need curl; need tar; need make
if command -v sha256sum >/dev/null 2>&1; then
  verify_sha256() { printf '%s  %s\n' "$FFMPEG_SHA256" "$1" | sha256sum --check --strict; }
elif command -v shasum >/dev/null 2>&1; then
  verify_sha256() { printf '%s  %s\n' "$FFMPEG_SHA256" "$1" | shasum -a 256 --check; }
else
  echo "Missing required SHA-256 tool (sha256sum or shasum)." >&2; exit 2
fi
mkdir -p "${CACHE_DIR}" "${OUT_JNILIBS}"

if [[ ! -f "${CACHE_DIR}/${FFMPEG_ARCHIVE}" ]]; then
  curl --fail --location --proto '=https' --tlsv1.2 --retry 3 \
    --output "${CACHE_DIR}/${FFMPEG_ARCHIVE}.part" "${FFMPEG_URL}"
  mv "${CACHE_DIR}/${FFMPEG_ARCHIVE}.part" "${CACHE_DIR}/${FFMPEG_ARCHIVE}"
fi
verify_sha256 "${CACHE_DIR}/${FFMPEG_ARCHIVE}"

# Always unpack from the verified immutable archive, rather than an old worktree.
rm -rf "${SOURCE_DIR}"
tar -xJf "${CACHE_DIR}/${FFMPEG_ARCHIVE}" -C "${CACHE_DIR}"

for ABI in ${ABIS}; do
  case "${ABI}" in
    arm64-v8a) TARGET="aarch64-linux-android"; FFMPEG_ARCH="aarch64" ;;
    x86_64) TARGET="x86_64-linux-android"; FFMPEG_ARCH="x86_64" ;;
    *) echo "Unsupported ABI: ${ABI}. Supported: arm64-v8a x86_64" >&2; exit 2 ;;
  esac

  BUILD_DIR="${CACHE_DIR}/build-${ABI}"
  rm -rf "${BUILD_DIR}"
  mkdir -p "${BUILD_DIR}" "${OUT_JNILIBS}/${ABI}"

  # Keep the build LGPL-only: all selected codecs are FFmpeg native codecs and
  # no --enable-gpl/nonfree or external codec libraries are used.
  pushd "${BUILD_DIR}" >/dev/null
  "${SOURCE_DIR}/configure" \
    --prefix="${BUILD_DIR}/install" \
    --target-os=android --arch="${FFMPEG_ARCH}" \
    --enable-cross-compile --cc="${TOOLCHAIN}/bin/clang --target=${TARGET}${ANDROID_API}" \
    --cxx="${TOOLCHAIN}/bin/clang++ --target=${TARGET}${ANDROID_API}" \
    --ar="${TOOLCHAIN}/bin/llvm-ar" --ranlib="${TOOLCHAIN}/bin/llvm-ranlib" \
    --strip="${TOOLCHAIN}/bin/llvm-strip" \
    --disable-shared --enable-static --enable-pic --enable-small \
    --disable-x86asm --disable-debug --disable-doc --disable-avdevice --disable-postproc \
    --disable-ffplay --disable-ffprobe --enable-ffmpeg \
    --disable-network --disable-everything --enable-protocol=file \
    --enable-demuxer=mov,matroska --enable-muxer=image2 \
    --enable-decoder=h264,hevc,vp9,av1,mjpeg \
    --enable-parser=h264,hevc,vp9,av1,mjpeg \
    --enable-encoder=mjpeg --enable-swscale \
    --enable-filter=buffer,buffersink,format,scale,transpose,hflip,vflip \
    --extra-cflags="-fPIC" \
    --extra-ldflags="-Wl,-z,max-page-size=16384"
  make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)" ffmpeg
  "${TOOLCHAIN}/bin/llvm-strip" --strip-unneeded ffmpeg
  install -m 0755 ffmpeg "${OUT_JNILIBS}/${ABI}/libffmpegexec.so"
  popd >/dev/null
done

echo "Built ${FFMPEG_VERSION} for: ${ABIS}"
echo "Output: ${OUT_JNILIBS}/<abi>/libffmpegexec.so"


mkdir -p "${ROOT}/app/src/main/assets/ffmpeg"
cp "${SOURCE_DIR}/COPYING.LGPLv2.1" "${ROOT}/app/src/main/assets/ffmpeg/COPYING.LGPLv2.1"
printf "FFmpeg %s; source SHA256 %s; LGPL configuration, no GPL/nonfree codecs. See docs/FFMPEG_BUILD.md and tools/build_android_ffmpeg.sh.\n" "$FFMPEG_VERSION" "$FFMPEG_SHA256" > "${ROOT}/app/src/main/assets/ffmpeg/BUILD.txt"
