#!/usr/bin/env bash
# The checks for the Google TV app. Run them before you push:
#
#   tools/check.sh           # the build and its tests (needs what build.sh needs)
#
# Here ./build.sh uses the newest SDK and JDK you have, while CI uses build-tools 34, android-34 and JDK 17, as the
# README says. A call newer than Android 34 can pass here and fail on the pull request.
set -euo pipefail
cd "$(dirname "$0")/.."

./build.sh
bash tools/test-remote.sh
bash tools/test-order.sh
bash tools/test-net.sh

echo "All checks passed."
