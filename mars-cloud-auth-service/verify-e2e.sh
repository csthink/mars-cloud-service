#!/usr/bin/env bash
set -euo pipefail
MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${E2E_ENV_FILE:-$MODULE_DIR/.env}"
if [ ! -f "$ENV_FILE" ]; then
    echo "Prepare the module environment before verification." >&2
    exit 2
fi
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
cd "$MODULE_DIR/.."
mvn -q -pl mars-cloud-auth-service test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test
exec python3 "$MODULE_DIR/scripts/verify_runtime.py"
