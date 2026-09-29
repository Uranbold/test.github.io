#!/usr/bin/env bash
# Docker healthcheck without curl/wget (the JDK image has neither): http-ok.sh <port> <path>
# Exits 0 only when 127.0.0.1:<port><path> answers HTTP 200.
set -euo pipefail
exec 3<>"/dev/tcp/127.0.0.1/$1"
printf 'GET %s HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' "$2" >&3
IFS= read -r status <&3
exec 3<&-
[[ "$status" == HTTP/1.?\ 200* ]]
