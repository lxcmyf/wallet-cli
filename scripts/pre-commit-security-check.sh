#!/usr/bin/env bash
# pre-commit-security-check.sh
# Runs semgrep on staged Java files before commit.
# Install: cp scripts/pre-commit-security-check.sh .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
# Or:      ln -sf ../../scripts/pre-commit-security-check.sh .git/hooks/pre-commit

set -euo pipefail

# Collect staged Java files
STAGED_FILES=$(git diff --cached --name-only --diff-filter=ACM -- '*.java' || true)
if [ -z "$STAGED_FILES" ]; then
  exit 0
fi

# Check if semgrep is available
if ! command -v semgrep &>/dev/null; then
  echo "[security-review] semgrep not found, skipping pre-commit scan."
  echo "[security-review] Install: pip3 install semgrep"
  exit 0
fi

echo "[security-review] Scanning $(echo "$STAGED_FILES" | wc -l | tr -d ' ') staged Java file(s)..."

# Create temp dir for staged file contents
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

# Copy staged versions (not working tree) to temp dir
for f in $STAGED_FILES; do
  mkdir -p "$TMPDIR/$(dirname "$f")"
  git show ":$f" > "$TMPDIR/$f" 2>/dev/null || true
done

# Run semgrep on staged content
RESULT=$(semgrep --config=auto --severity=ERROR --severity=WARNING \
  --exclude='*/bin/*' --exclude='*/build/*' \
  --json --quiet "$TMPDIR" 2>/dev/null || true)

ISSUES=$(echo "$RESULT" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
except:
    sys.exit(0)
results = data.get('results', [])
errors = 0
warnings = 0
for r in results:
    sev = r.get('extra', {}).get('severity', 'UNKNOWN')
    msg = r.get('extra', {}).get('message', '')
    path = r.get('path', '?').replace('$TMPDIR/', '')
    line = r.get('start', {}).get('line', '?')
    rule = r.get('check_id', '?')
    if sev == 'ERROR':
        errors += 1
    else:
        warnings += 1
    print(f'  [{sev}] {path}:{line} - {rule}')
    print(f'         {msg[:120]}')
if errors > 0:
    print(f'\n  {errors} ERROR(s), {warnings} WARNING(s). Commit blocked.')
    sys.exit(1)
elif warnings > 0:
    print(f'\n  {warnings} WARNING(s). Commit allowed, but please review.')
    sys.exit(0)
" 2>/dev/null)

EXIT_CODE=$?

if [ -n "$ISSUES" ]; then
  echo "[security-review] Results:"
  echo "$ISSUES"
fi

if [ $EXIT_CODE -ne 0 ]; then
  echo ""
  echo "[security-review] Commit blocked due to ERROR-level findings."
  echo "[security-review] Fix the issues or use 'git commit --no-verify' to bypass."
  exit 1
fi

# Quick pattern check on staged files (no semgrep needed)
BLOCKED=0
for f in $STAGED_FILES; do
  CONTENT=$(git show ":$f" 2>/dev/null || true)

  if echo "$CONTENT" | grep -qn 'MessageDigest.getInstance.*"MD5"'; then
    echo "[security-review] BLOCKED: MD5 usage in $f"
    BLOCKED=1
  fi

  if echo "$CONTENT" | grep -qn '"DES/'; then
    echo "[security-review] BLOCKED: DES encryption in $f"
    BLOCKED=1
  fi
done

if [ $BLOCKED -ne 0 ]; then
  echo "[security-review] Commit blocked due to banned crypto patterns."
  exit 1
fi

echo "[security-review] Scan passed."
exit 0
