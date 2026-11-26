#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/cvte-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/cvte-tests \
  app/src/io/freemytv/CvteSources.java tests/CvteSourcesTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/cvte-tests io.freemytv.CvteSourcesTest
