#!/usr/bin/env bash
set -euo pipefail
source parikt/native/build-lock.sh
log() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }
workspace=$(mktemp -d)
trap 'rm -rf "$workspace"' EXIT
(
  parikt_lock_work_directory "$workspace"
  parikt_lock_work_directory "$workspace/engine"
  captured=$(printf 'command substitution')
  ( true )
  [ -d "$workspace/.parikt-build.lock" ]
  [ -d "$workspace/engine/.parikt-build.lock" ]
  [ -f "$workspace/engine/.parikt-build.lock.owner" ]
  printf 'first-enter\n' >> "$workspace/order"
  sleep 2
  printf 'first-exit\n' >> "$workspace/order"
) &
first=$!
sleep 0.2
(
  parikt_lock_work_directory "$workspace"
  printf 'second-enter\n' >> "$workspace/order"
) &
second=$!
wait "$first"
wait "$second"
printf 'first-enter\nfirst-exit\nsecond-enter\n' > "$workspace/expected"
cmp "$workspace/order" "$workspace/expected"
[ ! -d "$workspace/.parikt-build.lock" ]
[ ! -d "$workspace/engine/.parikt-build.lock" ]
[ ! -e "$workspace/.parikt-build.lock.owner" ]
[ ! -e "$workspace/engine/.parikt-build.lock.owner" ]
echo 'PASS: shared native work lock serializes builders and cleans up'
