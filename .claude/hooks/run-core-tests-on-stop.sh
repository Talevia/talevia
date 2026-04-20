#!/usr/bin/env bash
# Stop hook: run :core:jvmTest if this session touched core/.
#
# Triggers the suite when ANY of:
#   1. working tree has uncommitted/staged changes under core/
#   2. HEAD has commits beyond the trunk (origin/main or main) that touch core/
#   3. there are commits within the last hour that touch core/
#      (catches the post-merge case where the branch was just merged into
#       trunk, so 1+2 report nothing even though the agent worked on core)
#
# Exit codes:
#   0  → no core/ change OR tests passed
#   2  → tests failed; stderr is fed back to the model so the next turn
#        sees what broke and can fix it before stopping again
set -uo pipefail

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

tree_changed=$(git status --porcelain -- core/ 2>/dev/null | head -1)

trunk=""
for candidate in origin/main main; do
  if git rev-parse --verify --quiet "$candidate" >/dev/null 2>&1; then
    trunk="$candidate"
    break
  fi
done
commits_changed=""
if [ -n "$trunk" ]; then
  commits_changed=$(git diff --name-only "$trunk"...HEAD -- core/ 2>/dev/null | head -1)
fi

recent_changed=""
if [ -z "$tree_changed" ] && [ -z "$commits_changed" ]; then
  recent_changed=$(git log --since='1 hour ago' --pretty=format:%H -- core/ 2>/dev/null | head -1)
fi

if [ -z "$tree_changed" ] && [ -z "$commits_changed" ] && [ -z "$recent_changed" ]; then
  exit 0
fi

output=$(./gradlew :core:jvmTest 2>&1)
status=$?

if [ "$status" -ne 0 ]; then
  failed=$(echo "$output" | grep -E "FAILED$" | head -20)
  {
    echo "core jvmTest FAILED — fix before stopping."
    if [ -n "$failed" ]; then
      echo ""
      echo "Failing tests:"
      echo "$failed"
    fi
    echo ""
    echo "Full report: $PWD/core/build/reports/tests/jvmTest/index.html"
  } >&2
  exit 2
fi

exit 0
