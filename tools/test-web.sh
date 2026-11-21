#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
node tests/WebPrefsTest.js
node tests/EckCoreTest.js
node tests/EckTvTest.js
