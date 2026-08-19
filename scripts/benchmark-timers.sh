#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
MVN="${MVN:-mvn}"
RESULT_DIR="${1:-target/timer-benchmark}"
mkdir -p "$RESULT_DIR"

"$MVN" --batch-mode test-compile dependency:build-classpath \
  -DincludeScope=test -Dmdep.outputFile=target/benchmark-classpath.txt
CLASSPATH="target/test-classes:target/classes:$(<target/benchmark-classpath.txt)"
"$JAVA_HOME/bin/java" -cp "$CLASSPATH" org.openjdk.jmh.Main \
  'io.github.shri299.wirefin.bench.TimerScanBenchmark' -bm avgt -tu ms -prof gc \
  -rf json -rff "$RESULT_DIR/timer-scan.json"
{
  date -u '+timestamp_utc=%Y-%m-%dT%H:%M:%SZ'
  git rev-parse HEAD
  uname -a
  "$JAVA_HOME/bin/java" -version
  command -v lscpu >/dev/null && lscpu || true
} >"$RESULT_DIR/environment.txt" 2>&1
echo "Timer scan results written to $RESULT_DIR"
