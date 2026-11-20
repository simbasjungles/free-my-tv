#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/net-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/net-tests \
  app/src/io/freemytv/NetPicture.java tests/NetPictureTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/net-tests io.freemytv.NetPictureTest
