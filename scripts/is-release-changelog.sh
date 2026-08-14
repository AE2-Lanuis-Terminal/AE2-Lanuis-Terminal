#!/usr/bin/env bash
# 检测相对对比基线的 CHANGELOG.md 是否新增发行节：## [X.Y.Z]
# 用法：./scripts/is-release-changelog.sh [BASE_REF]
# push 到 main 时传入 github.event.before
set -euo pipefail

BASE_REF="${1:-origin/main}"
CHANGELOG_PATH="${CHANGELOG_PATH:-CHANGELOG.md}"

if [[ ! -f "$CHANGELOG_PATH" ]]; then
  echo "missing $CHANGELOG_PATH" >&2
  exit 2
fi

if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  if grep -E -q '^## \[[0-9]+\.[0-9]+\.[0-9]+\]' "$CHANGELOG_PATH"; then
    echo "IS_RELEASE=true"
    echo "NOTE=base_ref_missing_scanned_file_only"
    exit 0
  fi
  echo "IS_RELEASE=false"
  echo "NOTE=base_ref_missing"
  exit 1
fi

DIFF="$(git diff --unified=0 "${BASE_REF}" HEAD -- "$CHANGELOG_PATH" 2>/dev/null || true)"
if echo "$DIFF" | grep -E -q '^\+## \[[0-9]+\.[0-9]+\.[0-9]+\]'; then
  VER="$(echo "$DIFF" | grep -E '^\+## \[[0-9]+\.[0-9]+\.[0-9]+\]' | head -1 | sed -E 's/^\+## \[([0-9]+\.[0-9]+\.[0-9]+)\].*/\1/')"
  echo "IS_RELEASE=true"
  echo "RELEASE_VERSION=$VER"
  exit 0
fi

echo "IS_RELEASE=false"
exit 1
