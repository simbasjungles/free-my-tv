#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/theme-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/theme-tests \
  app/src/io/freemytv/Theme.java tests/ThemeTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/theme-tests io.freemytv.ThemeTest
