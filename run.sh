#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$ROOT/build/classes"
mkdir -p "$BUILD_DIR"

javac --add-modules jdk.httpserver -d "$BUILD_DIR" \
  "$ROOT"/src/main/java/*.java

if [[ "${1:-}" == "test" ]]; then
  javac --add-modules jdk.httpserver -cp "$BUILD_DIR" -d "$BUILD_DIR" \
    "$ROOT"/src/test/java/*.java
  (cd "$ROOT" && java --add-modules jdk.httpserver -cp "$BUILD_DIR" CounterStoreTest)
else
  export STATE_PATH="${STATE_PATH:-$ROOT/distributed_state.txt}"
  export PAGE_PATH="${PAGE_PATH:-$ROOT/index.html}"
  cd "$ROOT"
  exec java --add-modules jdk.httpserver -cp "$BUILD_DIR" DistributedCounterServer
fi
