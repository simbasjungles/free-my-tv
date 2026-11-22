#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/backup-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/backup-tests \
  app/src/io/freemytv/BackupRules.java tests/BackupRulesTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/backup-tests io.freemytv.BackupRulesTest
