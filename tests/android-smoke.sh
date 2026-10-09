#!/usr/bin/env bash
# Only run against the disposable CI emulator, never a user's phone.
set -euo pipefail
collect_diagnostics() {
  local out=app/build/smoke-diagnostics
  mkdir -p "$out"
  adb pull /sdcard/Android/data/com.doulala.android.debug/files/smoke "$out/checkpoints" || true
  adb logcat -d -t 2000 > "$out/logcat.txt" 2>&1 || true
  adb shell uiautomator dump /sdcard/doulala-smoke.xml >/dev/null 2>&1 && adb pull /sdcard/doulala-smoke.xml "$out/ui.xml" || true
  adb shell screencap -p /sdcard/doulala-smoke.png && adb pull /sdcard/doulala-smoke.png "$out/screen.png" || true
}
trap collect_diagnostics EXIT
gradle --no-daemon --stacktrace connectedDebugAndroidTest
