#!/usr/bin/env bash
# Sourced by builders: source/toolchain staging is shared across consumer builds.
# mkdir is atomic on Linux and macOS and needs no additional locking package.
parikt_lock_work_directory() {
  mkdir -p "$1"
  PARIKT_LOCK_DIRECTORY="$1/.parikt-build.lock"
  local attempts=0
  until mkdir "$PARIKT_LOCK_DIRECTORY" 2>/dev/null; do
    if [ "$attempts" = 0 ]; then log "waiting for another build using $1"; fi
    attempts=$((attempts + 1))
    [ "$attempts" -lt 900 ] || die "timed out waiting for $PARIKT_LOCK_DIRECTORY; if no builder is running, remove that stale lock directory"
    sleep 1
  done
  printf '%s\n' "$$" > "$PARIKT_LOCK_DIRECTORY/pid"
  trap 'rm -f "$PARIKT_LOCK_DIRECTORY/pid"; rmdir "$PARIKT_LOCK_DIRECTORY" 2>/dev/null || true' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 129' HUP
}
