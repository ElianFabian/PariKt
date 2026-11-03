#!/usr/bin/env bash
# Builds GMP + PARI/GP as Android shared libraries.
# Output: <out>/<abi>/{gmp,pari}/{lib,include}. Skipped per ABI when up to date.
set -euo pipefail

PARI_VERSION=""
ABIS="arm64-v8a x86_64"
API=21
OUT_DIR=""
NDK=""
WORK_DIR="${PARIDROID_WORK_DIR:-$HOME/.cache/paridroid-native}"
DATADIR=/data/data/fr.ubordeaux.math.paridroid
FORCE=0
VERBOSE=0
# Keep GMP pinned independently of pariVersion. PARI currently supports GMP 4.2+;
# change this only if a future PARI release requires it or a build issue calls for it.
GMP_VERSION=6.3.0
NDK_REV=r28c   # only used when --ndk is not given (Linux/WSL/Docker); keep in sync with ndkVersion

die() { echo "error: $*" >&2; exit 1; }
log() { echo "[native] $*"; }

# Accept Windows paths when running under WSL.
to_posix() {
  local re='^[A-Za-z]:[\\/]'
  if [[ $1 =~ $re ]] && command -v wslpath >/dev/null; then wslpath -u "$1"; else printf '%s' "$1"; fi
}

while [ $# -gt 0 ]; do
  case "$1" in
    --pari-version) PARI_VERSION=$2; shift 2;;
    --abis)         ABIS=$2; shift 2;;
    --api)          API=$2; shift 2;;
    --out)          OUT_DIR=$(to_posix "$2"); shift 2;;
    --ndk)          NDK=$(to_posix "$2"); shift 2;;
    --work)         WORK_DIR=$2; shift 2;;
    --datadir)      DATADIR=$2; shift 2;;
    --force)        FORCE=1; shift;;
    --verbose|-v)   VERBOSE=1; shift;;
    *) die "unknown option $1";;
  esac
done
# Serialize shared compiler/source stages across independent consumer builds.
source "$(dirname "${BASH_SOURCE[0]}")/build-lock.sh"
parikt_lock_work_directory "$WORK_DIR"

[ -n "$PARI_VERSION" ] || die "--pari-version is required; the consuming build must select PARI"
[ -n "$OUT_DIR" ] || die "--out is required"

if [ "$VERBOSE" = 1 ]; then
  set -x
fi

case "$(uname -s)" in
  Linux)  NDK_HOST=linux-x86_64;;
  Darwin) NDK_HOST=darwin-x86_64;;
  *) die "run on Linux, macOS or WSL (got $(uname -s))";;
esac

for t in git curl tar xz make m4 sed cc patchelf; do
  command -v "$t" >/dev/null || die "missing '$t'. Ubuntu: sudo apt install build-essential m4 git curl xz-utils unzip"
done

# ---- NDK -------------------------------------------------------------------
if [ -z "$NDK" ]; then
  [ "$(uname -s)" = Linux ] || die "pass --ndk <path> (no auto-download on macOS)"
  NDK="$WORK_DIR/ndk/android-ndk-$NDK_REV"
  if [ ! -d "$NDK" ]; then
    command -v unzip >/dev/null || die "missing 'unzip'"
    log "downloading NDK $NDK_REV"
    mkdir -p "$WORK_DIR/ndk"
    curl -fL --retry 3 -o "$WORK_DIR/ndk/ndk.zip" \
      "https://dl.google.com/android/repository/android-ndk-$NDK_REV-linux.zip"
    unzip -q "$WORK_DIR/ndk/ndk.zip" -d "$WORK_DIR/ndk" && rm "$WORK_DIR/ndk/ndk.zip"
  fi
fi
TOOLBIN="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin"
[ -d "$TOOLBIN" ] || die "no $NDK_HOST toolchain in NDK: $NDK"

# ---- up-to-date check ------------------------------------------------------
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PARI_SRC="$WORK_DIR/src/pari-$PARI_VERSION-work"
SOURCE_REVISION=$(bash "$SCRIPT_DIR/pari-source.sh" --pari-version "$PARI_VERSION" \
  --destination "$PARI_SRC" --seed "$WORK_DIR/src/pari-$PARI_VERSION" \
  --workspace-key "$(git hash-object "${BASH_SOURCE[0]}"):$ABIS:$API")
SCRIPT_ID=$(cat "${BASH_SOURCE[0]}" "$SCRIPT_DIR/pari-source.sh" "$SCRIPT_DIR/build-lock.sh" | cksum | cut -d' ' -f1)
NDK_ID=$(cksum < "$NDK/source.properties" | cut -d' ' -f1)
expected_stamp() { echo "pari=$PARI_VERSION revision=$SOURCE_REVISION gmp=$GMP_VERSION abi=$abi api=$API ndk=$NDK_ID datadir=$DATADIR script=$SCRIPT_ID"; }

todo=""
for abi in $ABIS; do
  if [ "$FORCE" = 0 ] && [ -f "$OUT_DIR/$abi/.stamp" ] \
     && [ "$(cat "$OUT_DIR/$abi/.stamp")" = "$(expected_stamp)" ] \
     && [ -f "$OUT_DIR/$abi/gmp/lib/libgmp.so" ] && [ -f "$OUT_DIR/$abi/pari/lib/libpari.so" ] \
     && [ -f "$OUT_DIR/$abi/gmp/include/gmp.h" ] && [ -f "$OUT_DIR/$abi/pari/include/pari/paripriv.h" ]; then
    log "$abi: up to date"
  else
    todo="$todo $abi"
  fi
done
if [ -z "$todo" ]; then log "nothing to do"; exit 0; fi

# ---- sources ---------------------------------------------------------------
GMP_SRC="$WORK_DIR/src/gmp-$GMP_VERSION"
mkdir -p "$WORK_DIR/src" "$WORK_DIR/logs"

if [ ! -f "$GMP_SRC/configure" ]; then
  log "downloading GMP $GMP_VERSION"
  curl -fL --retry 3 -o "$WORK_DIR/src/gmp.tar.xz" "https://gmplib.org/download/gmp/gmp-$GMP_VERSION.tar.xz"
  tar -xf "$WORK_DIR/src/gmp.tar.xz" -C "$WORK_DIR/src" && rm "$WORK_DIR/src/gmp.tar.xz"
fi

JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)

# Run a command in a subshell, log its output, show the tail on failure.
step() {
  local logf=$1; shift
  if [ "$VERBOSE" = 1 ]; then
    if ! ( "$@" ) 2>&1 | tee "$logf"; then
      die "step failed, full log: $logf"
    fi
  else
    if ! ( "$@" ) >"$logf" 2>&1; then
      tail -n 40 "$logf" >&2; die "step failed, full log: $logf"
    fi
  fi
}

# Adjust pari.cfg, since Configure cannot run test programs when cross-compiling.
patch_cfg() {
  local f=$1
  case "$cpu" in
    aarch64)
      sed -i.bak \
        -e "s!^asmarch='none'!asmarch='aarch64'!" \
        -e "s!^kernlvl0='none'!kernlvl0='aarch64'!" \
        -e "s!^doubleformat=''!doubleformat='-'!" \
        -e "s!^sizeof_long='4'!sizeof_long='8'!" \
        -e "s!^kernlvl1='none'!kernlvl1='gmp'!" \
        -e "s!^libpari_base='pari'!libpari_base='pari-gmp'!" \
        -e "s!^gmp=''!gmp='$stage/gmp/lib'!" \
        -e "s!^GMPLIBS=''!GMPLIBS='-L$stage/gmp/lib -lgmp'!" \
        -e "s!^GMPINCLUDE=''!GMPINCLUDE='-I$stage/gmp/include'!" "$f";;
    x86_64)
      sed -i.bak -E \
        -e "s!^asmarch='ix86'!asmarch='x86_64'!" \
        -e "s!^kernlvl0='ix86'!kernlvl0='x86_64'!" \
        -e "s!^doubleformat='.*'!doubleformat='-'!" \
        -e "s!^sizeof_long='4'!sizeof_long='8'!" \
        -e "s!^kernlvl1='none'!kernlvl1='gmp'!" \
        -e "s!^libpari_base='pari'!libpari_base='pari-gmp'!" \
        -e "s!^gmp=''!gmp='$stage/gmp/lib'!" \
        -e "s!^GMPLIBS=''!GMPLIBS='-L$stage/gmp/lib -lgmp'!" \
        -e "s!^GMPINCLUDE=''!GMPINCLUDE='-I$stage/gmp/include'!" "$f";;
  esac
}

gmp_build() {
  rm -rf "$gbld" && mkdir -p "$gbld" && cd "$gbld" &&
  CC="$cc" CFLAGS="-O2 -std=c99" "$GMP_SRC/configure" --host="$triple" --prefix="$stage/gmp" &&
  make -j"$JOBS" && make install

  # Force unversioned SONAME so libpari.so links to plain libgmp.so
  patchelf --set-soname libgmp.so "$stage/gmp/lib/libgmp.so"
}

pari_build() {
  cd "$PARI_SRC" && rm -rf "Olinux-$cpu" &&
  RUNTEST=true CC="$cc" ./Configure --prefix="$stage/pari" --datadir="$DATADIR" \
      --with-gmp="$stage/gmp" --host="$cpu-linux" &&
  patch_cfg "Olinux-$cpu/pari.cfg" &&
  RUNTEST=true ./Configure -l "Olinux-$cpu/pari.cfg" &&
  cd "Olinux-$cpu" &&
  make -j"$JOBS" lib-dyn &&
  make LIBPARI_SONAME=libpari.so install-include install-lib-dyn
}

build_abi() {
  local abi=$1 triple cpu
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android; cpu=aarch64;;
    x86_64)    triple=x86_64-linux-android;  cpu=x86_64;;
    *) die "unsupported ABI '$abi' (add it to build_abi / patch_cfg)";;
  esac
  local cc="$TOOLBIN/${triple}${API}-clang"
  local stage="$WORK_DIR/stage/pari-$PARI_VERSION-api$API/$abi"
  local gbld="$WORK_DIR/build/gmp-$GMP_VERSION-$abi-api$API"
  rm -rf "$stage"; mkdir -p "$stage"

  log "$abi: building GMP $GMP_VERSION"
  step "$WORK_DIR/logs/gmp-$abi.log" gmp_build
  log "$abi: building PARI $PARI_VERSION"
  step "$WORK_DIR/logs/pari-$abi.log" pari_build

  # Sanity check: Android only loads un-versioned names like libgmp.so.
  local re="$TOOLBIN/llvm-readelf" pl="$stage/pari/lib/libpari.so"
  "$re" -d "$pl" | grep -q 'SONAME.*\[libpari\.so\]' || die "$abi: libpari.so has an unexpected SONAME"
  "$re" -d "$pl" | grep NEEDED | grep -q '\[libgmp\.so\]' || die "$abi: libpari.so does not link plain libgmp.so"

  local dst="$OUT_DIR/$abi"
  rm -rf "$dst"; mkdir -p "$dst/gmp/lib" "$dst/gmp/include" "$dst/pari/lib" "$dst/pari/include"
  cp -L "$stage/gmp/lib/libgmp.so"       "$dst/gmp/lib/"
  cp    "$stage/gmp/include/gmp.h"       "$dst/gmp/include/"
  cp -L "$pl"                            "$dst/pari/lib/"
  cp -R "$stage/pari/include/pari"       "$dst/pari/include/"
  expected_stamp > "$dst/.stamp"
  log "$abi: done"
}

mkdir -p "$OUT_DIR"
for abi in $todo; do build_abi "$abi"; done
