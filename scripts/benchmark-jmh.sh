#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
MVN="${MVN:-mvn}"
RESULT_DIR="${1:-target/benchmarks}"
mkdir -p "$RESULT_DIR"

"$MVN" --batch-mode test-compile dependency:build-classpath \
  -DincludeScope=test -Dmdep.outputFile=target/benchmark-classpath.txt
CLASSPATH="target/test-classes:target/classes:$(<target/benchmark-classpath.txt)"
"$JAVA_HOME/bin/java" -cp "$CLASSPATH" org.openjdk.jmh.Main \
  'io.github.shri299.wirefin.bench.*' -bm thrpt -prof gc -rf json -rff "$RESULT_DIR/throughput.json"
"$JAVA_HOME/bin/java" -cp "$CLASSPATH" org.openjdk.jmh.Main \
  'io.github.shri299.wirefin.bench.*' -bm sample -tu us -rf json -rff "$RESULT_DIR/latency.json"

{
  date -u '+timestamp_utc=%Y-%m-%dT%H:%M:%SZ'
  uname -a
  "$JAVA_HOME/bin/java" -version
  command -v lscpu >/dev/null && lscpu || true
  command -v sysctl >/dev/null && sysctl -n machdep.cpu.brand_string hw.ncpu hw.memsize 2>/dev/null || true
} >"$RESULT_DIR/environment.txt" 2>&1

echo "JMH results written to $RESULT_DIR"
