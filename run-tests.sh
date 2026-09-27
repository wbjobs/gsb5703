#!/usr/bin/env bash
# Builds and tests the two-phase commit coordinator.
# Requirements: a JDK (javac/java) on PATH. No Maven/Gradle/JUnit, JDK 8 syntax only.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
  echo "ERROR: javac/java not found on PATH. Install a JDK (8 or later); no Maven/Gradle needed." >&2
  exit 1
fi

# Enforce JDK 8 language level: use --release 8 when supported (JDK 9+),
# fall back to -source/-target on a plain JDK 8.
if javac --release 8 -version >/dev/null 2>&1; then
  RELEASE_FLAGS=(--release 8)
else
  RELEASE_FLAGS=(-source 8 -target 8)
fi

echo "==> Checking for forbidden APIs in src/"
if grep -rn "System\.currentTimeMillis" src/; then
  echo "ERROR: System.currentTimeMillis is forbidden; use the injected Clock" >&2
  exit 1
fi

echo "==> Compiling src/ (com.gsb.tpc, JDK 8 syntax)"
rm -rf build
mkdir -p build/classes build/test-classes
find src -name '*.java' | sort > build/sources.txt
javac "${RELEASE_FLAGS[@]}" -d build/classes @build/sources.txt

echo "==> Compiling tests"
find test -name '*.java' | sort > build/test-sources.txt
javac "${RELEASE_FLAGS[@]}" -cp build/classes -d build/test-classes @build/test-sources.txt

echo "==> Running tests"
java -cp build/classes:build/test-classes com.gsb.tpc.test.TestRunner
