#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/order-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/order-tests \
  app/src/io/freemytv/VisibleOrder.java tests/VisibleOrderTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/order-tests io.freemytv.VisibleOrderTest
