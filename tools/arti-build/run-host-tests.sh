#!/usr/bin/env bash
#
# Runs the wrapper's tests on the *host* (no Android toolchain needed): the pure protocol tests, then
# the engine tests that drive the real Arti client, including one that reaches the Tor network.
#
# Everything cargo writes goes to a directory you choose, so nothing lands in your home directory:
#
#   CARGO_HOME=/some/scratch/cargo-home CARGO_TARGET_DIR=/some/scratch/target ./run-host-tests.sh
#
# Without those variables cargo uses its defaults (~/.cargo and ./target).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "== protocol and mapping tests =="
cargo test --locked

echo "== engine tests (real Arti client, real Tor network; needs outbound access) =="
cargo test --locked -- --ignored --test-threads=1 --nocapture
