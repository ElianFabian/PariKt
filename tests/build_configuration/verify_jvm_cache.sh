#!/usr/bin/env bash
# Real Windows JVM engines built in WSL, with two independent build workspaces.
set -euo pipefail
jni_home=$1
repo=$(pwd)
# GMP's configure machinery requires a build workspace without spaces.
scratch=$(mktemp -d "${TMPDIR:-/tmp}/parikt-jvm-reuse.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
export PARIKT_ENGINE_CACHE_DIR="$scratch/engines"
compiler=""
for candidate in x86_64-w64-mingw32-gcc-posix x86_64-w64-mingw32-gcc; do
  if command -v "$candidate" >/dev/null; then compiler=$(command -v "$candidate"); break; fi
done
if [ -z "$compiler" ]; then compiler="$HOME/.cache/parikt-jvm-native/toolchains/xpack-mingw-w64-gcc-15.2.0-2/bin/x86_64-w64-mingw32-gcc"; fi
[ -x "$compiler" ] || { echo 'Run the JVM fixture once to prepare its native compiler' >&2; exit 1; }
export MINGW_CC="$compiler" PATH="$(dirname "$compiler"):$PATH"
for consumer in a b; do
  mkdir -p "$scratch/work-$consumer/src"
  cp -a "$HOME/.cache/parikt-jvm-native/src/gmp-6.3.0" "$scratch/work-$consumer/src/"
done
build_version() {
  local consumer=$1 version=$2; shift 2
  bash "$repo/parikt/native/build-jvm.sh" --pari-version "$version" --target windows-x86_64 \
    --jni-home "$jni_home" --work "$scratch/work-$consumer" --out "$scratch/out-$consumer" "$@"
}
build() { local consumer=$1; shift; build_version "$consumer" 2.19.0 "$@"; }
build a >"$scratch/a.log" 2>&1 & first=$!
build b >"$scratch/b.log" 2>&1 & second=$!
if ! wait "$first"; then cat "$scratch/a.log"; exit 1; fi
if ! wait "$second"; then cat "$scratch/b.log"; exit 1; fi
[ "$(cat "$scratch/a.log" "$scratch/b.log" | grep -c 'configuring PARI')" = 1 ]
grep -q 'reusing compiled engine' "$scratch/a.log" "$scratch/b.log"
grep -q 'waiting for another build using .*engines/jvm/locks/' "$scratch/a.log" "$scratch/b.log"
! grep -q '\[pari-source\] fetching' "$scratch/a.log" "$scratch/b.log"
cmp "$scratch/out-a/.stamp" "$scratch/out-b/.stamp"
cmp "$scratch/out-a/.engine-generation" "$scratch/out-b/.engine-generation"
cmp "$scratch/out-a/paridroid.dll" "$scratch/out-b/paridroid.dll"
key=$(printf '%s' "$(cat "$scratch/out-a/.stamp")" | sha256sum | cut -d' ' -f1)
entry="$PARIKT_ENGINE_CACHE_DIR/jvm/$key"
[ -f "$entry/paridroid.dll" ]
echo 'PASS: concurrent independent workspaces compile exactly one compatible engine'
old_generation=$(cat "$scratch/out-b/.engine-generation")
build a --force >"$scratch/force.log" 2>&1
! grep -q '\[pari-source\] fetching' "$scratch/force.log"
[ "$(cat "$scratch/out-a/.engine-generation")" != "$old_generation" ]
build b >"$scratch/refresh.log" 2>&1
! grep -q 'configuring PARI' "$scratch/refresh.log"
cmp "$scratch/out-a/.engine-generation" "$scratch/out-b/.engine-generation"
cmp "$entry/.engine-generation" "$scratch/out-b/.engine-generation"
cmp "$entry/paridroid.dll" "$scratch/out-b/paridroid.dll"
[ "$(find "$PARIKT_ENGINE_CACHE_DIR/jvm" -mindepth 1 -maxdepth 1 -type d | grep -c '/[0-9a-f]\{64\}$')" = 1 ]
echo 'PASS: force replaces one entry and another consumer refreshes without recompilation'
rm "$entry/.engine-generation"
build b >"$scratch/repair.log" 2>&1
! grep -q 'configuring PARI' "$scratch/repair.log"
[ -s "$entry/.engine-generation" ]
echo 'PASS: incomplete compiled-cache metadata is repaired from a valid consumer output'

# Simulate master/latest resolving to this real release commit, without a network
# fetch or an extra native build. Requested labels are not compiler inputs.
alias_remote="$scratch/alias-remote.git"
git init --bare -q "$alias_remote"
release_commit=$(git --git-dir="$HOME/.cache/parikt/sources/pari.git" rev-parse 'refs/parikt/pari-2.19.0^{commit}')
git --git-dir="$alias_remote" fetch -q --depth 1 "$HOME/.cache/parikt/sources/pari.git" \
  "$release_commit:refs/heads/master"
export PARIKT_SOURCE_REMOTE="$alias_remote" PARIKT_SOURCE_CACHE_DIR="$scratch/alias-sources"
build_version master master >"$scratch/master.log" 2>&1
build_version latest latest >"$scratch/latest.log" 2>&1
! grep -q 'configuring PARI' "$scratch/master.log" "$scratch/latest.log"
grep -q 'reusing compiled engine' "$scratch/master.log"
grep -q 'reusing compiled engine' "$scratch/latest.log"
cmp "$scratch/out-master/.stamp" "$scratch/out-latest/.stamp"
cmp "$scratch/out-a/.stamp" "$scratch/out-master/.stamp"
[ "$(find "$PARIKT_ENGINE_CACHE_DIR/jvm" -mindepth 1 -maxdepth 1 -type d | grep -c '/[0-9a-f]\{64\}$')" = 1 ]
echo 'PASS: release/master/latest resolving to one commit share one compiled engine'
