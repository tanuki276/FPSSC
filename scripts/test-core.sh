#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
mapfile -t fixtures < <(find tests/java -name '*.java' -print)
# Invoking the compiler module also supports JDK images without a javac launcher.
java -m jdk.compiler/com.sun.tools.javac.Main -d "$build_dir" \
    android/app/src/main/java/com/tanuki276/fpssc/FramePixels.java \
    android/app/src/main/java/com/tanuki276/fpssc/AgentCore.java \
    android/app/src/main/java/com/tanuki276/fpssc/CombatNavigator.java \
    android/app/src/main/java/com/tanuki276/fpssc/ControlLocator.java \
    android/app/src/main/java/com/tanuki276/fpssc/FrameTiming.java \
    android/app/src/main/java/com/tanuki276/fpssc/TouchProfile.java \
    android/app/src/main/java/com/tanuki276/fpssc/BrawlerProfile.java \
    android/app/src/main/java/com/tanuki276/fpssc/PracticeOpponentModel.java \
    android/app/src/main/java/com/tanuki276/fpssc/PracticeTacticalPlanner.java \
    android/app/src/main/java/com/tanuki276/fpssc/PracticeCombatPolicy.java "${fixtures[@]}"
java -cp "$build_dir" com.tanuki276.fpssc.AgentCoreTest
java -cp "$build_dir" com.tanuki276.fpssc.CombatRegressionTest
java -cp "$build_dir" com.tanuki276.fpssc.TouchProfileTest
java -cp "$build_dir" com.tanuki276.fpssc.FrameTimingTest
java -cp "$build_dir" com.tanuki276.fpssc.FramePixelsTest
java -cp "$build_dir" com.tanuki276.fpssc.ControlLocatorTest
java -cp "$build_dir" com.tanuki276.fpssc.PracticeCombatPolicyTest
java -cp "$build_dir" com.tanuki276.fpssc.PracticeCombatBenchmarkTest
java -cp "$build_dir" com.tanuki276.fpssc.PracticeOpponentModelTest
java -cp "$build_dir" com.tanuki276.fpssc.PracticeTacticalPlannerTest
java -cp "$build_dir" com.tanuki276.fpssc.PracticeCombatStressTest
if [[ $# -gt 0 ]]; then
    java -cp "$build_dir" com.tanuki276.fpssc.ScreenshotRegressionTest "$@"
fi
