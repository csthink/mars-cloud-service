#!/usr/bin/env bash
# Delegate all build and log policy to the selected framework source.
set -euo pipefail
: "${FRAMEWORK_DIR:?Set FRAMEWORK_DIR to the exact framework source checkout}"
exec bash "$FRAMEWORK_DIR/tools/verify.sh" --framework "$FRAMEWORK_DIR" --service "$(cd "$(dirname "$0")/.." && pwd)" --purpose service "$@"
