#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/shelf-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/shelf-tests \
  app/src/io/freemytv/ShelfKeys.java tests/ShelfKeysTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/shelf-tests io.freemytv.ShelfKeysTest
