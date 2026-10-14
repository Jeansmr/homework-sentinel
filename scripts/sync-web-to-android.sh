#!/usr/bin/env bash
# =====================================================================
#  把 app/ 里的网页资源同步到安卓工程的 assets/www（macOS / Linux）
#  网页版和安卓版共用同一份界面代码，避免两处改、改漏一处。
# =====================================================================
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SRC="$ROOT/app"
DST="$ROOT/android/app/src/main/assets/www"

[ -d "$SRC" ] || { echo "找不到网页资源目录: $SRC" >&2; exit 1; }

rm -rf "$DST"
mkdir -p "$DST"

count=0
# 复制时排除开发调试用的文件（_ 开头的临时页、界面自测页）
while IFS= read -r -d '' f; do
  rel="${f#"$SRC"/}"
  base="$(basename "$f")"
  case "$base" in
    _*) continue ;;
  esac
  case "$rel" in
    selftest.html) continue ;;
  esac

  mkdir -p "$DST/$(dirname "$rel")"
  cp -f "$f" "$DST/$rel"
  count=$((count + 1))
  echo "  - $rel"
done < <(find "$SRC" -type f -print0)

echo "已同步 $count 个文件到 assets/www"
