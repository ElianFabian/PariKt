#!/usr/bin/env bash
# Build desktop GMP/PARI and the PariKt JNI bridge for a JVM host.
# Windows builds are cross-compiled from Linux using MinGW-w64.
set -euo pipefail

PARI_VERSION=""
GMP_VERSION=6.3.0
MINGW_VERSION=15.2.0-2
MINGW_SHA256=daf373cbea59bb04cc610cdc26ef98828eb5c5eee3407d448d3b26272fa816d5
TARGET=""
OUT_DIR=""
WORK_DIR="${PARIKT_JVM_WORK_DIR:-$HOME/.cache/parikt-jvm-native}"
JNI_HOME="${JAVA_HOME:-}"
FORCE=0

die() { echo "error: $*" >&2; exit 1; }
log() { echo "[jvm-native] $*"; }

ensure_mingw_toolchain() {
  # Prefer a user/system MinGW compiler when available, then fall back to a pinned portable
  # xPack toolchain in the native work cache. The checksum is published in xPack's package.json.
  if [ -n "${MINGW_CC:-}" ]; then
    CC=$MINGW_CC
    command -v "$CC" >/dev/null || die "MINGW_CC='$CC' was not found on PATH"
    return
  fi

  for candidate in x86_64-w64-mingw32-gcc-posix x86_64-w64-mingw32-gcc; do
    if command -v "$candidate" >/dev/null; then
      CC=$candidate
      return
    fi
  done

  local toolchain_dir="$WORK_DIR/toolchains/xpack-mingw-w64-gcc-$MINGW_VERSION"
  local compiler="$toolchain_dir/bin/x86_64-w64-mingw32-gcc"
  if [ ! -x "$compiler" ]; then
    local archive="$WORK_DIR/toolchains/xpack-mingw-w64-gcc-$MINGW_VERSION-linux-x64.tar.gz"
    local url="https://github.com/xpack-dev-tools/mingw-w64-gcc-xpack/releases/download/v$MINGW_VERSION/xpack-mingw-w64-gcc-$MINGW_VERSION-linux-x64.tar.gz"
    mkdir -p "$WORK_DIR/toolchains"
    log "downloading portable MinGW-w64 GCC $MINGW_VERSION"
    curl -fL --retry 3 -o "$archive" "$url"
    echo "$MINGW_SHA256  $archive" | sha256sum -c - >/dev/null || die "MinGW-w64 toolchain checksum verification failed"
    tar -xzf "$archive" -C "$WORK_DIR/toolchains"
    rm -f "$archive"
  fi
  [ -x "$compiler" ] || die "downloaded MinGW-w64 toolchain did not contain $compiler"
  PATH="$toolchain_dir/bin:$PATH"
  export PATH
  CC=$compiler
}

while [ $# -gt 0 ]; do
  case "$1" in
    --pari-version) PARI_VERSION=$2; shift 2;;
    --gmp-version) GMP_VERSION=$2; shift 2;;
    --target) TARGET=$2; shift 2;;
    --out) OUT_DIR=$2; shift 2;;
    --work) WORK_DIR=$2; shift 2;;
    --jni-home) JNI_HOME=$2; shift 2;;
    --force) FORCE=1; shift;;
    *) die "unknown option $1";;
  esac
done
# Serialize shared compiler/source stages across independent consumer builds.
source "$(dirname "${BASH_SOURCE[0]}")/build-lock.sh"
parikt_lock_work_directory "$WORK_DIR"


[ -n "$PARI_VERSION" ] || die "--pari-version is required; the consuming build must select PARI"
[ -n "$TARGET" ] || die "--target is required (linux-x86_64 or windows-x86_64)"
[ -n "$OUT_DIR" ] || die "--out is required"
[ -n "$JNI_HOME" ] || die "pass --jni-home pointing to a JDK with JNI headers"

for tool in git curl tar xz make m4 sed sha256sum; do
  command -v "$tool" >/dev/null || die "missing required build tool '$tool'"
done

case "$TARGET" in
  linux-x86_64)
    [ "$(uname -s)" = Linux ] || die "linux-x86_64 must be built in Linux/WSL"
    [ "$(uname -m)" = x86_64 ] || die "linux-x86_64 build requires an x86_64 host"
    CC=${CC:-cc}
    HOST=x86_64-pc-linux-gnu
    BUILD_DIR=Olinux-x86_64
    ;;
  windows-x86_64)
    [ "$(uname -s)" = Linux ] || die "windows-x86_64 is cross-compiled from Linux/WSL"
    ensure_mingw_toolchain
    HOST=x86_64-mingw
    BUILD_DIR=Omingw-x86_64
    ;;
  *) die "unsupported JVM target '$TARGET'";;
esac

command -v "$CC" >/dev/null || die "missing C compiler '$CC'"

JNI_INCLUDE="$JNI_HOME/include"
[ -f "$JNI_INCLUDE/jni.h" ] || die "JNI headers not found under '$JNI_INCLUDE'"
case "$TARGET" in
  linux-x86_64) JNI_PLATFORM=linux;;
  windows-x86_64) JNI_PLATFORM=win32;;
esac
[ -f "$JNI_INCLUDE/$JNI_PLATFORM/jni_md.h" ] || die "JNI platform headers not found: $JNI_INCLUDE/$JNI_PLATFORM/jni_md.h"

case "$PARI_VERSION" in
  master|latest) PARI_REF=master;;
  *) PARI_REF="pari-$PARI_VERSION";;
esac

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# Cache the bridge with its source/header and JNI/compiler identity, not PARI version alone.
SCRIPT_ID=$(cat "${BASH_SOURCE[0]}" "$SCRIPT_DIR/paridroid/paridroid.c" \
  "$SCRIPT_DIR/paridroid/paridroid.h" "$SCRIPT_DIR/paridroid/jni_bridge.c" \
  "$JNI_INCLUDE/jni.h" "$JNI_INCLUDE/$JNI_PLATFORM/jni_md.h" | sha256sum | cut -d' ' -f1)
COMPILER_ID=$("$CC" --version | sed -n '1p')
STAMP="$OUT_DIR/.stamp"
EXPECTED_STAMP="pari=$PARI_VERSION gmp=$GMP_VERSION target=$TARGET inputs=$SCRIPT_ID compiler=$COMPILER_ID"
CHECK_BRIDGE="$OUT_DIR/libparidroid.so"
CHECK_PARI="$OUT_DIR/libpari.so"
if [ "$TARGET" = windows-x86_64 ]; then
  CHECK_BRIDGE="$OUT_DIR/paridroid.dll"
fi
cached_pari_library_exists() {
  if [ "$TARGET" = windows-x86_64 ]; then
    [ -f "$OUT_DIR/libpari-gmp.dll" ] || [ -f "$OUT_DIR/libpari.dll" ] || [ -f "$OUT_DIR/pari.dll" ]
  else
    [ -f "$CHECK_PARI" ]
  fi
}
if [ "$PARI_REF" != master ] && [ "$FORCE" = 0 ] && [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$EXPECTED_STAMP" ] && \
   [ -f "$CHECK_BRIDGE" ] && cached_pari_library_exists; then
  log "$TARGET PARI $PARI_VERSION artifacts are up to date at $OUT_DIR"
  exit 0
fi

GMP_SRC="$WORK_DIR/src/gmp-$GMP_VERSION"
PARI_SRC="$WORK_DIR/src/pari-$PARI_VERSION"
mkdir -p "$WORK_DIR/src" "$WORK_DIR/build" "$WORK_DIR/logs" "$OUT_DIR"

if [ ! -f "$GMP_SRC/configure" ]; then
  log "downloading GMP $GMP_VERSION"
  curl -fL --retry 3 -o "$WORK_DIR/src/gmp-$GMP_VERSION.tar.xz" \
    "https://gmplib.org/download/gmp/gmp-$GMP_VERSION.tar.xz"
  tar -xf "$WORK_DIR/src/gmp-$GMP_VERSION.tar.xz" -C "$WORK_DIR/src"
  rm "$WORK_DIR/src/gmp-$GMP_VERSION.tar.xz"
fi

if [ ! -d "$PARI_SRC/.git" ]; then
  log "fetching PARI $PARI_REF"
  mkdir -p "$PARI_SRC"
  git -C "$PARI_SRC" init -q
  git -C "$PARI_SRC" remote add origin https://pari.math.u-bordeaux.fr/git/pari.git
  if [ "$PARI_REF" = master ]; then
    git -C "$PARI_SRC" fetch --depth 1 origin master
    git -C "$PARI_SRC" checkout -q FETCH_HEAD
  else
    git -C "$PARI_SRC" fetch --depth 1 origin "refs/tags/$PARI_REF:refs/tags/$PARI_REF" || \
      git -C "$PARI_SRC" fetch --depth 1 origin "$PARI_REF"
    git -C "$PARI_SRC" checkout -q "$PARI_REF"
  fi
elif [ "$PARI_REF" = master ]; then
  git -C "$PARI_SRC" fetch --depth 1 origin master
  git -C "$PARI_SRC" checkout -q --detach FETCH_HEAD
fi

if [ "$TARGET" = windows-x86_64 ]; then
  # PARI's MinGW export-file recipe feeds a generated .def file back to ld. With current
  # MinGW toolchains this can fail while parsing the large export list; MinGW ld auto-exports
  # symbols when no export file is supplied. Match the workaround used by PARI downstream builds.
  sed -i.bak "/export_file='\\\$(LIBPARI).def';/d" "$PARI_SRC/config/Makefile.SH"
fi

JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
STAGE="$WORK_DIR/stage/$TARGET/pari-$PARI_VERSION"
GMP_BUILD="$WORK_DIR/build/gmp-$GMP_VERSION-$TARGET"
PARI_BUILD="$PARI_SRC/$BUILD_DIR"
rm -rf "$STAGE"
mkdir -p "$STAGE/gmp" "$STAGE/pari"

if [ "$TARGET" = windows-x86_64 ]; then
  log "building static GMP $GMP_VERSION for Windows x86_64"
  rm -rf "$GMP_BUILD"
  mkdir -p "$GMP_BUILD"
  (
    cd "$GMP_BUILD"
    CC="$CC" CC_FOR_BUILD=cc CFLAGS="-O2 -std=gnu17 -fno-omit-frame-pointer" \
      "$GMP_SRC/configure" --build="$(cc -dumpmachine)" --host=x86_64-w64-mingw32 --prefix="$STAGE/gmp" \
      --enable-static --disable-shared
    make -j"$JOBS"
    make install
  ) >"$WORK_DIR/logs/gmp-$TARGET.log" 2>&1 || {
    tail -n 60 "$WORK_DIR/logs/gmp-$TARGET.log" >&2
    die "GMP build failed; full log: $WORK_DIR/logs/gmp-$TARGET.log"
  }
else
  log "building shared GMP $GMP_VERSION for Linux x86_64"
  rm -rf "$GMP_BUILD"
  mkdir -p "$GMP_BUILD"
  (
    cd "$GMP_BUILD"
    CC="$CC" CFLAGS="-O2 -std=gnu17 -fPIC" "$GMP_SRC/configure" --host="$HOST" \
      --prefix="$STAGE/gmp" --enable-shared --disable-static
    make -j"$JOBS"
    make install
  ) >"$WORK_DIR/logs/gmp-$TARGET.log" 2>&1 || {
    tail -n 60 "$WORK_DIR/logs/gmp-$TARGET.log" >&2
    die "GMP build failed; full log: $WORK_DIR/logs/gmp-$TARGET.log"
  }
fi

log "configuring PARI $PARI_VERSION for $TARGET"
rm -rf "$PARI_BUILD"
(
  cd "$PARI_SRC"
  if [ "$TARGET" = windows-x86_64 ]; then
    export CC="$CC" CFLAGS="-O2 -std=gnu17 -D__USE_MINGW_ANSI_STDIO=1 -fno-omit-frame-pointer"
    export LDFLAGS="-static-libgcc" DLLDFLAGS="-static-libgcc" RUNTEST=true
    ./Configure --host="$HOST" --with-gmp="$STAGE/gmp" --prefix="$STAGE/pari" \
      --datadir=@ --builddir="$BUILD_DIR"
    sed -i.bak -E \
      -e "s!^asmarch='ix86'!asmarch='x86_64'!" \
      -e "s!^kernlvl0='ix86'!kernlvl0='x86_64'!" \
      -e "s!^doubleformat='.*'!doubleformat='-'!" \
      -e "s!^sizeof_long='4'!sizeof_long='8'!" \
      -e "s!^kernlvl1='none'!kernlvl1='gmp'!" \
      -e "s!^libpari_base='pari'!libpari_base='pari-gmp'!" \
      -e "s!^gmp=''!gmp='$STAGE/gmp/lib'!" \
      -e "s!^GMPLIBS=''!GMPLIBS='-L$STAGE/gmp/lib -lgmp'!" \
      -e "s!^GMPINCLUDE=''!GMPINCLUDE='-I$STAGE/gmp/include'!" \
      "$PARI_BUILD/pari.cfg"
    RUNTEST=true ./Configure -l "$PARI_BUILD/pari.cfg"
  else
    export LDFLAGS="-Wl,-rpath,\$ORIGIN"
    CC="$CC" ./Configure --host="$HOST" --with-gmp="$STAGE/gmp" \
      --prefix="$STAGE/pari" --datadir="$OUT_DIR/share/pari" --builddir="$BUILD_DIR"
  fi
  make -C "$PARI_BUILD" -j"$JOBS" LIBPARI_SONAME="$([ "$TARGET" = windows-x86_64 ] && echo pari.dll || echo libpari.so)" lib-dyn
  make -C "$PARI_BUILD" LIBPARI_SONAME="$([ "$TARGET" = windows-x86_64 ] && echo pari.dll || echo libpari.so)" install-include install-lib-dyn
) >"$WORK_DIR/logs/pari-$TARGET.log" 2>&1 || {
  tail -n 80 "$WORK_DIR/logs/pari-$TARGET.log" >&2
  die "PARI build failed; full log: $WORK_DIR/logs/pari-$TARGET.log"
}

REPO_PARIKT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WRAPPER_DIR="$REPO_PARIKT/native/paridroid"
BRIDGE="$WRAPPER_DIR/jni_bridge.c"
PARI_INCLUDE="$STAGE/pari/include"
PARI_LIB="$STAGE/pari/lib"
mkdir -p "$OUT_DIR"

if [ "$TARGET" = windows-x86_64 ]; then
  PARI_LIB=""
  PARI_DLL=""
  for directory in "$STAGE/pari/bin" "$STAGE/pari/lib"; do
    for name in libpari-gmp.dll libpari.dll pari.dll; do
      candidate="$directory/$name"
      if [ -e "$candidate" ]; then PARI_LIB="$directory"; PARI_DLL="$candidate"; break 2; fi
    done
  done
  [ -n "$PARI_DLL" ] || die "PARI did not install a Windows PARI DLL under $STAGE/pari"
  rm -f "$OUT_DIR/libpari-gmp.dll" "$OUT_DIR/libpari.dll" "$OUT_DIR/pari.dll"
  "$CC" -shared -O2 -static-libgcc -D__USE_MINGW_ANSI_STDIO=1 \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE/win32" \
    -I"$PARI_INCLUDE" -I"$WRAPPER_DIR" \
    "$WRAPPER_DIR/paridroid.c" "$BRIDGE" \
    -L"$PARI_LIB" -lpari -Wl,--add-stdcall-alias -o "$OUT_DIR/paridroid.dll"
  PARI_IMPORT_NAME=$(objdump -p "$OUT_DIR/paridroid.dll" | awk '$1 == "DLL" && $2 == "Name:" && $3 ~ /^(libpari.*|pari)[.]dll$/ && name == "" { name = $3 } END { if (name != "") print name }')
  [ -n "$PARI_IMPORT_NAME" ] || die "could not determine the PARI DLL imported by paridroid.dll"
  cp -L "$PARI_DLL" "$OUT_DIR/$PARI_IMPORT_NAME"
else
  cp -a "$STAGE/gmp/lib"/libgmp.so* "$OUT_DIR/"
  PARI_SO="$PARI_LIB/libpari.so"
  [ -n "$PARI_SO" ] || die "PARI did not install libpari.so in $PARI_LIB"
  cp -L "$PARI_SO" "$OUT_DIR/libpari.so"
  "$CC" -shared -fPIC -O2 -std=gnu99 \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE/linux" \
    -I"$PARI_INCLUDE" -I"$WRAPPER_DIR" \
    "$WRAPPER_DIR/paridroid.c" "$BRIDGE" \
    -L"$OUT_DIR" -lpari -lgmp -Wl,-rpath,'$ORIGIN' -o "$OUT_DIR/libparidroid.so"
fi

echo "$EXPECTED_STAMP" > "$STAMP"
log "built $TARGET PARI $PARI_VERSION artifacts in $OUT_DIR"
