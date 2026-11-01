#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
mapfile -t fixtures < <(find tests/java -name '*.java' -print)
# Invoking the compiler module also supports JDK images without a javac launcher.
java -m jdk.compiler/com.sun.tools.javac.Main -d "$build_dir" \
    android/app/src/main/java/com/tanuki276/fpssc/AgentCore.java "${fixtures[@]}"
java -cp "$build_dir" com.tanuki276.fpssc.AgentCoreTest
