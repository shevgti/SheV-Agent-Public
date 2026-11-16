#!/usr/bin/env bash
set -euo pipefail

# The emulator-runner action invokes this file through bash so multiline
# control flow is parsed consistently instead of being truncated by sh -c.
for attempt in 1 2 3 4 5; do
  if adb shell cmd package list packages >/dev/null 2>&1; then
    break
  fi
  echo "Package Manager not ready (attempt ${attempt}); waiting..."
  sleep 10
done

adb shell cmd package list packages >/dev/null
adb shell getprop sys.boot_completed

# Run the real Android intent/Room integration tests against API 36.
(
  cd android
  gradle :shevchat:connectedDebugAndroidTest --stacktrace
)

# Keep a separate launch smoke test and screenshot after instrumentation tests.
adb install --no-streaming -r test-package/shevchat-debug.apk
adb shell am start -n com.shev.chat/.ChatActivity
sleep 5
adb shell pidof com.shev.chat
adb exec-out screencap -p > shev-chat-home.png
