#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

for path in \
    USB-Debug-Guard-Incident-2026-08-01/README.md \
    outputs/local-report.md \
    release/local-release.apk \
    .keystore/release-key.p12 \
    device-logs/logcat.txt \
    private-test-data/device.txt \
    bugreports/bugreport.zip; do
    git check-ignore -q "$path" || fail "private local path is not ignored: $path"
done

tracked_private=$(git ls-files | grep -E \
    '(^|/)(USB-Debug-Guard-Incident-[^/]*|outputs|release|\.keystore|device-logs|private-test-data|bugreports)(/|$)|\.(apk|aab|idsig|jks|keystore|p12|pfx|log)$' \
    || true)
[ -z "$tracked_private" ] || fail "private/generated files are tracked:\n$tracked_private"

tracked_markers=$(git grep -n -I -E \
    '/Users/[^/]+/|[A-Za-z]:\\Users\\[^\\]+\\|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|gh[opsu]_[A-Za-z0-9]{20,}' \
    -- . \
    ':(exclude)tools/check-public-tree.sh' \
    || true)
[ -z "$tracked_markers" ] || fail "local path, private key, or token marker found:\n$tracked_markers"

large_tracked=$(git ls-tree -rl HEAD | awk '$4 > 5242880 { print $4 " " $5 }')
[ -z "$large_tracked" ] || fail "tracked files exceed 5 MiB:\n$large_tracked"

if command -v gitleaks >/dev/null 2>&1; then
    gitleaks git --no-banner --redact --log-level error . || fail "gitleaks found a secret in Git history"
fi

echo "PASS: public Git tree excludes local incident data, device logs, release keys, artifacts, local paths, and detected secrets"
