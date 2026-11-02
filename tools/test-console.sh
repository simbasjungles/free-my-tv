#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/console-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/console-tests \
  app/src/io/freemytv/ConsoleShelf.java tests/ConsoleShelfTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/console-tests io.freemytv.ConsoleShelfTest
