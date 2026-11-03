#!/usr/bin/env bash
# Sourced by builders: source/toolchain staging is shared across consumer builds.
# Exclusive file claims and legacy directory guards need no extra host package.
PARIKT_LOCK_DIRECTORIES=()
PARIKT_LOCK_FILES=()
parikt_unlock_work_directories() {
  # Subshells/command substitutions never release locks owned by their parent.
  [ "${PARIKT_LOCK_OWNER:-}" = "${BASHPID:-$$}" ] || return 0
  [ "${PARIKT_LOCK_SUBSHELL:-}" = "$BASH_SUBSHELL" ] || return 0
  local directory file owner
  for directory in "${PARIKT_LOCK_DIRECTORIES[@]}"; do
    rm -f "$directory/pid"
    rmdir "$directory" 2>/dev/null || true
  done
  for file in "${PARIKT_LOCK_FILES[@]}"; do
    owner=""
    if [ -f "$file" ]; then read -r owner < "$file" || true; fi
    if [ "$owner" = "$PARIKT_LOCK_OWNER" ]; then rm -f "$file"; fi
  done
}
parikt_lock_work_directory() {
  mkdir -p "$1"
  PARIKT_LOCK_DIRECTORY="$1/.parikt-build.lock"
  local held
  for held in "${PARIKT_LOCK_DIRECTORIES[@]}"; do
    [ "$held" != "$PARIKT_LOCK_DIRECTORY" ] || return 0
  done
  local attempts=0 claim="$PARIKT_LOCK_DIRECTORY.owner" owner="${BASHPID:-$$}"
  # noclobber uses exclusive creation. Reset the inherited EXIT trap in the
  # acquisition subshell so it cannot release previously held parent locks.
  until (trap - EXIT; set -C; printf '%s\n' "$owner" > "$claim") 2>/dev/null; do
    if [ "$attempts" = 0 ]; then log "waiting for another build using $1"; fi
    attempts=$((attempts + 1))
    [ "$attempts" -lt 900 ] || die "timed out waiting for $claim; if no builder is running, remove that stale lock file"
    sleep 1
  done
  PARIKT_LOCK_FILES+=("$claim")
  PARIKT_LOCK_OWNER=$owner
  PARIKT_LOCK_SUBSHELL=$BASH_SUBSHELL
  trap parikt_unlock_work_directories EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 129' HUP
  attempts=0
  # Retain the directory guard to coordinate with older builder processes too.
  until mkdir "$PARIKT_LOCK_DIRECTORY" 2>/dev/null; do
    if [ "$attempts" = 0 ]; then log "waiting for another build using $1"; fi
    attempts=$((attempts + 1))
    [ "$attempts" -lt 900 ] || die "timed out waiting for $PARIKT_LOCK_DIRECTORY; if no builder is running, remove that stale lock directory"
    sleep 1
  done
  printf '%s\n' "$$" > "$PARIKT_LOCK_DIRECTORY/pid"
  PARIKT_LOCK_DIRECTORIES+=("$PARIKT_LOCK_DIRECTORY")
}
