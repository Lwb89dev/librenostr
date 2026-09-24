#!/usr/bin/env bash
#
# Builds libnostr_arti.so twice from scratch and compares the two, then compares the result with the
# library committed in app/src/main/jniLibs. Prints REPRODUCIBLE only when both builds are identical.
#
# Same prerequisites and same caveat as build-arti.sh: not yet run in this repository.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMMITTED="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libnostr_arti.so"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

"$SCRIPT_DIR/build-arti.sh" --out "$WORK/first" >/dev/null
"$SCRIPT_DIR/build-arti.sh" --out "$WORK/second" >/dev/null

first="$(sha256sum "$WORK/first/arm64-v8a/libnostr_arti.so" | awk '{print $1}')"
second="$(sha256sum "$WORK/second/arm64-v8a/libnostr_arti.so" | awk '{print $1}')"

if [ "$first" != "$second" ]; then
    echo "NOT REPRODUCIBLE: two clean builds differ"
    echo "  first : $first"
    echo "  second: $second"
    exit 1
fi
echo "REPRODUCIBLE: two clean builds are identical ($first)"

if [ -f "$COMMITTED" ]; then
    committed="$(sha256sum "$COMMITTED" | awk '{print $1}')"
    if [ "$committed" = "$first" ]; then
        echo "The committed library matches this rebuild."
    else
        echo "The committed library does NOT match this rebuild ($committed)."
        exit 1
    fi
else
    echo "No committed library to compare with ($COMMITTED)."
fi
