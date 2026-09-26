#!/usr/bin/env bash
#
# Builds and tests the two-phase commit coordinator.
# Requires only a JDK (8 or newer): javac + java. No Maven/Gradle/JUnit.
#
set -euo pipefail
cd "$(dirname "$0")"

echo "==> Guard: System.currentTimeMillis must not appear in src/"
if grep -rn "System\.currentTimeMillis" src/ ; then
    echo "FAIL: src/ must read time exclusively from the injected Clock" >&2
    exit 1
fi

# Prefer --release 8 (JDK 9+); fall back to -source/-target on JDK 8.
if javac --help 2>&1 | grep -q -- '--release'; then
    RELEASE_FLAGS="--release 8"
else
    RELEASE_FLAGS="-source 8 -target 8"
fi

echo "==> Cleaning build/"
rm -rf build
mkdir -p build/main build/test

echo "==> Compiling src/ (javac ${RELEASE_FLAGS})"
find src -name '*.java' | sort > build/sources.txt
javac -encoding UTF-8 ${RELEASE_FLAGS} -d build/main @build/sources.txt

echo "==> Compiling tests/"
find tests -name '*.java' | sort > build/test-sources.txt
javac -encoding UTF-8 ${RELEASE_FLAGS} -cp build/main -d build/test @build/test-sources.txt

echo "==> Running tests"
java -cp "build/main:build/test" com.gsb.tpc.CoordinatorTest

echo "==> ALL GREEN"
