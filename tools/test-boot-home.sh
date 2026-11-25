#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/boot-home-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/boot-home-tests \
  app/src/io/freemytv/BootHome.java tests/BootHomeTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/boot-home-tests io.freemytv.BootHomeTest
