#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/weather-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/weather-tests \
  app/src/io/freemytv/Sun.java app/src/io/freemytv/WeatherCodes.java \
  tests/SunTest.java tests/WeatherCodesTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/weather-tests io.freemytv.SunTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/weather-tests io.freemytv.WeatherCodesTest
