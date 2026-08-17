#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
MVN="${MVN:-mvn}"
OUTPUT_DIR="${1:-target/benchmarks/jfr}"
mkdir -p "$OUTPUT_DIR"
"$MVN" --batch-mode test-compile dependency:build-classpath \
  -DincludeScope=test -Dmdep.outputFile=target/benchmark-classpath.txt
CLASSPATH="target/test-classes:target/classes:$(<target/benchmark-classpath.txt)"
"$JAVA_HOME/bin/java" -cp "$CLASSPATH" org.openjdk.jmh.Main '.*process.*' \
  -bm thrpt -wi 3 -i 5 -f 1 -prof "jfr:dir=$OUTPUT_DIR;configName=profile;stackDepth=128"
echo "JFR recordings written below $OUTPUT_DIR"
