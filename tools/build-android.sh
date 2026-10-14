#!/usr/bin/env bash
# =====================================================================
#  macOS / Linux 下打包安卓 APK
#
#  用法：
#     bash tools/build-android.sh [任务名]
#     任务名默认 assembleRelease，也可以是 assembleDebug
#
#  前提：
#     - 已安装 JDK 17+，并设置 JAVA_HOME
#     - 已安装 Android SDK（platform 34 + build-tools 34.0.0），并设置 ANDROID_HOME
# =====================================================================
set -euo pipefail

TASK="${1:-assembleRelease}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "错误：请先设置 JAVA_HOME 指向 JDK 17 或更高版本。" >&2
  exit 1
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  echo "错误：请先设置 ANDROID_HOME 指向 Android SDK。" >&2
  exit 1
fi
SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

echo "JDK      : $JAVA_HOME"
echo "SDK      : $SDK"
echo "任务     : $TASK"
echo

# 写出 SDK 位置，Gradle 需要
printf 'sdk.dir=%s\n' "$SDK" > "$ANDROID_DIR/local.properties"

# 1) 同步网页资源
echo "--- 同步网页资源 ---"
"$ROOT/scripts/sync-web-to-android.sh" "$ROOT"

# 2) 打包
echo
echo "--- 执行 Gradle ---"
cd "$ANDROID_DIR"
if [ -x ./gradlew ]; then
  ./gradlew "$TASK" --no-daemon
else
  echo "未找到 gradlew，改用系统 gradle"
  gradle "$TASK" --no-daemon
fi

echo
echo "构建完成，产物："
find "$ANDROID_DIR/app/build/outputs/apk" -name '*.apk' -print 2>/dev/null || true
