#!/usr/bin/env bash
# Shared test-classpath issuer lifecycle. Runtime services still launch their packaged jars.
start_security_test_issuer() {
  local repository="$1"
  SECURITY_TEST_DIR="$(mktemp -d)" || return 1
  chmod 700 "$SECURITY_TEST_DIR"
  local maven_options=()
  if [ -n "${SECURITY_TEST_MAVEN_REPO:-}" ]; then
    maven_options=("-Dmaven.repo.local=$SECURITY_TEST_MAVEN_REPO")
  fi
  if ! mvn "${maven_options[@]}" -q -f "$repository/mars-cloud-sample-service/pom.xml" dependency:build-classpath \
      -DincludeScope=test -Dmdep.outputFile="$SECURITY_TEST_DIR/classpath" >"$SECURITY_TEST_DIR/classpath.log" 2>&1; then
    echo "Cannot resolve the test issuer classpath; build the modules in the selected Maven repository first." >&2
    return 1
  fi
  java --enable-native-access=ALL-UNNAMED -cp "$(cat "$SECURITY_TEST_DIR/classpath")" \
      com.mars.cloud.security.test.TestIdentityProviderProcess "$SECURITY_TEST_DIR" >"$SECURITY_TEST_DIR/issuer.log" 2>&1 &
  SECURITY_ISSUER_PID=$!
  local attempts=0
  while [ ! -f "$SECURITY_TEST_DIR/ready" ] && [ "$attempts" -lt 100 ]; do
    kill -0 "$SECURITY_ISSUER_PID" 2>/dev/null || { echo "Test issuer failed to start" >&2; return 1; }
    sleep 0.1
    attempts=$((attempts + 1))
  done
  [ -f "$SECURITY_TEST_DIR/ready" ] || { echo "Test issuer readiness timed out" >&2; return 1; }
  export MARS_SECURITY_ISSUER_URI MARS_SECURITY_JWK_SET_URI MARS_SECURITY_TOKEN_FILE
  MARS_SECURITY_ISSUER_URI="$(sed -n 's/^issuer=//p' "$SECURITY_TEST_DIR/issuer.properties")"
  MARS_SECURITY_JWK_SET_URI="$(sed -n 's/^jwks=//p' "$SECURITY_TEST_DIR/issuer.properties")"
  MARS_SECURITY_TOKEN_FILE="$SECURITY_TEST_DIR/allow.token"
  local token_file
  for token_file in "$SECURITY_TEST_DIR"/*.token; do
    (umask 077; printf 'Authorization: Bearer %s\n' "$(cat "$token_file")" >"${token_file%.token}.headers")
  done
  SECURITY_CURL_HEADER="$SECURITY_TEST_DIR/allow.headers"
}

security_curl() {
  if [ -n "${SECURITY_CURL_HEADER:-}" ] && [ -f "$SECURITY_CURL_HEADER" ]; then
    command curl --header "@$SECURITY_CURL_HEADER" "$@"
  else
    command curl "$@"
  fi
}

verify_no_test_credentials() {
  python3 - "$SECURITY_TEST_DIR" "$@" <<'PYCODE'
import pathlib, sys
secrets = [path.read_bytes() for path in pathlib.Path(sys.argv[1]).glob('*.token')]
for name in sys.argv[2:]:
    path = pathlib.Path(name)
    if not path.is_file():
        raise SystemExit('Missing acceptance log: ' + str(path))
    data = path.read_bytes()
    if any(secret and secret in data for secret in secrets):
        raise SystemExit('Credential appeared in acceptance output: ' + str(path))
print('Credential output check passed')
PYCODE
}

stop_security_test_issuer() {
  if [ -n "${SECURITY_ISSUER_PID:-}" ] && kill -0 "$SECURITY_ISSUER_PID" 2>/dev/null; then
    kill "$SECURITY_ISSUER_PID" 2>/dev/null
    wait "$SECURITY_ISSUER_PID" 2>/dev/null || true
  fi
  if [ -n "${SECURITY_TEST_DIR:-}" ] && [ -d "$SECURITY_TEST_DIR" ]; then
    rm -rf "$SECURITY_TEST_DIR"
  fi
}
