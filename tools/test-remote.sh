#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/remote-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/remote-tests \
  app/src/io/freemytv/SabrinaRemote.java app/src/io/freemytv/SkyworthRemote.java \
  tests/SabrinaRemoteTest.java tests/SkyworthRemoteTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/remote-tests io.freemytv.SabrinaRemoteTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/remote-tests io.freemytv.SkyworthRemoteTest
