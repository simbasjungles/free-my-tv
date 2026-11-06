#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/sun-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/sun-tests \
  app/src/io/freemytv/Sun.java tests/SunTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/sun-tests io.freemytv.SunTest
