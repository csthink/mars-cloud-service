#!/usr/bin/env bash
set -euo pipefail
exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /actuator/health HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
response=$(cat <&3)
[[ "$response" == *'200 '* && "$response" == *'"status":"UP"'* ]]
