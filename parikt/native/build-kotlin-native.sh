#!/usr/bin/env bash
# Static PARI/GMP and C bridge for Kotlin/Native. No JDK or JNI dependency.
set -euo pipefail
PARI_VERSION=""
GMP_VERSION=6.3.0
OUT=""
FORCE=0
TOOLCHAIN=""
PLATFORM_ROOT=""
TARGET=linux-x86_64
WORK="${PARIKT_NATIVE_WORK_DIR:-$HOME/.cache/parikt-kotlin-native}"
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
JOBS=${PARIKT_NATIVE_JOBS:-$(getconf _NPROCESSORS_ONLN)}
[ "$JOBS" -le 8 ] || JOBS=8
die() { echo "error: $*" >&2; exit 1; }
log() { echo "[kotlin-native] $*"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --pari-version) PARI_VERSION=$2; shift 2;;
    --out) OUT=$2; shift 2;;
    --force) FORCE=1; shift;;
    --toolchain-root) TOOLCHAIN=$2; shift 2;;
    --target) TARGET=$2; shift 2;;
    --platform-root) PLATFORM_ROOT=$2; shift 2;;
    *) die "unknown argument: $1";;
  esac
done
# Serialize shared compiler/source stages across independent consumer builds.
source "$(dirname "${BASH_SOURCE[0]}")/build-lock.sh"
parikt_lock_work_directory "$WORK"

[ -n "$PARI_VERSION" ] || die "--pari-version is required; the consuming build must select PARI"
[ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] || die "native C dependencies must be built on Linux/WSL x86_64"
[ -n "$OUT" ] || die "--out is required"
if [ "$TARGET" = windows-x86_64 ]; then
  # Same checksum-verified xPack release used by the JVM builder. Always use the
  # pinned compiler here: -mcrtdll selects Kotlin/Native's MSVCRT ABI explicitly.
  mingw_version=15.2.0-2
  mingw_sha=daf373cbea59bb04cc610cdc26ef98828eb5c5eee3407d448d3b26272fa816d5
  TOOLCHAIN="$WORK/toolchains/xpack-mingw-w64-gcc-$mingw_version"
  reused="$HOME/.cache/parikt-jvm-native/toolchains/xpack-mingw-w64-gcc-$mingw_version"
  if [ -x "$reused/bin/x86_64-w64-mingw32-gcc" ]; then TOOLCHAIN="$reused"; fi
  if [ ! -x "$TOOLCHAIN/bin/x86_64-w64-mingw32-gcc" ]; then
    mkdir -p "$WORK/toolchains"
    archive="$WORK/toolchains/mingw-$mingw_version.tar.gz"
    log "downloading pinned MinGW-w64 $mingw_version"
    curl -fL --retry 3 -o "$archive" "https://github.com/xpack-dev-tools/mingw-w64-gcc-xpack/releases/download/v$mingw_version/xpack-mingw-w64-gcc-$mingw_version-linux-x64.tar.gz"
    echo "$mingw_sha  $archive" | sha256sum -c -
    tar -xf "$archive" -C "$WORK/toolchains"
  fi
  PREFIX=x86_64-w64-mingw32
  GMP_HOST=x86_64-w64-mingw32
  CFLAGS='-O2 -std=gnu17 -mcrtdll=msvcrt-os -D__USE_MINGW_ANSI_STDIO=1 -fno-omit-frame-pointer'
  platform_headers="$PLATFORM_ROOT/x86_64-w64-mingw32/include"
  if [ ! -f "$platform_headers/sys/stat.h" ]; then platform_headers="$PLATFORM_ROOT/include"; fi
  [ -f "$platform_headers/sys/stat.h" ] || die "--platform-root must point to Kotlin/Native's managed Windows sysroot"
  # New MinGW headers can reference CRT wrappers absent from Kotlin/Native's runtime.
  # Use the exact target headers selected by Kotlin/Native, cached on the Linux filesystem.
  platform_id=$(basename "$PLATFORM_ROOT")
  platform_cache="$WORK/headers/$platform_id"
  if [ ! -f "$platform_cache/.ready" ]; then
    log "caching Kotlin/Native's Windows headers inside WSL"
    mkdir -p "$platform_cache"
    cp -a "$platform_headers" "$platform_cache/include"
    touch "$platform_cache/.ready"
  fi
  PLATFORM_ROOT="$platform_cache"
elif [ "$TARGET" = linux-x86_64 ]; then
  [ -n "$TOOLCHAIN" ] || die "--toolchain-root is required (Gradle prepares Kotlin/Native's matching Linux toolchain)"
  case "$TOOLCHAIN" in
    /mnt/[a-z]/*)
      cached_toolchain="$WORK/toolchains/$(basename "$TOOLCHAIN")"
      if [ ! -f "$cached_toolchain/.ready" ] || [ ! -x "$cached_toolchain/bin/x86_64-unknown-linux-gnu-gcc" ]; then
        log "caching the Linux compiler inside WSL"
        mkdir -p "$cached_toolchain"
        cp -a "$TOOLCHAIN/." "$cached_toolchain/"
        touch "$cached_toolchain/.ready"
      fi
      TOOLCHAIN="$cached_toolchain"
      ;;
  esac
  PREFIX=x86_64-unknown-linux-gnu
  GMP_HOST=x86_64-pc-linux-gnu
  CFLAGS='-O2 -std=gnu17 -fPIC'
else
  die "unsupported native target: $TARGET"
fi
CC="$TOOLCHAIN/bin/$PREFIX-gcc"
AR="$TOOLCHAIN/bin/$PREFIX-ar"
[ -x "$CC" ] && [ -x "$AR" ] || die "missing native C compiler under $TOOLCHAIN"
if [ "$TARGET" = windows-x86_64 ]; then
  CFLAGS="$CFLAGS -nostdinc -isystem $("$CC" -print-file-name=include) -isystem $PLATFORM_ROOT/include"
fi
export CC AR CFLAGS
for tool in cc ar make m4 git curl tar xz sha256sum; do
  command -v "$tool" >/dev/null || die "missing '$tool'; install a C build environment, m4, git, curl, tar and xz"
done
mkdir -p "$WORK/src" "$WORK/logs" "$OUT/lib" "$OUT/include"
INPUT_HASH=$(cat "${BASH_SOURCE[0]}" "$SCRIPT_DIR/kotlin-native/bridge.c" \
  "$SCRIPT_DIR/kotlin-native/bridge.h" "$SCRIPT_DIR/paridroid/paridroid.c" \
  "$SCRIPT_DIR/paridroid/paridroid.h" | sha256sum | cut -d' ' -f1)
COMPILER="$("$CC" --version | sed -n '1p') [$(basename "$TOOLCHAIN")] platform=${platform_id:-linux}"
BUILD_ID=$( { cat "${BASH_SOURCE[0]}"; printf '%s' "$COMPILER"; } | sha256sum | cut -c1-16)
STAMP="pari=$PARI_VERSION gmp=$GMP_VERSION target=$TARGET inputs=$INPUT_HASH compiler=$COMPILER"
if [ "$FORCE" = 0 ] && [ "$PARI_VERSION" != master ] && [ "$PARI_VERSION" != latest ] && \
   [ -f "$OUT/.stamp" ] && [ "$(cat "$OUT/.stamp")" = "$STAMP" ] && \
   [ -f "$OUT/lib/libparikt.a" ] && [ -f "$OUT/include/bridge.h" ]; then
  log "PARI $PARI_VERSION $TARGET artifacts are up to date"
  exit 0
fi

GMP_SRC="$WORK/src/gmp-$GMP_VERSION"
if [ ! -f "$GMP_SRC/configure" ]; then
  # Optional source reuse is an optimization; a new checkout can download everything.
  cached="$HOME/.cache/parikt-jvm-native/src/gmp-$GMP_VERSION"
  if [ -f "$cached/configure" ]; then
    cp -R "$cached" "$GMP_SRC"
  else
    curl -fL --retry 3 -o "$WORK/src/gmp.tar.xz" "https://ftp.gnu.org/gnu/gmp/gmp-$GMP_VERSION.tar.xz"
    echo "a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898  $WORK/src/gmp.tar.xz" | sha256sum -c -
    tar -xf "$WORK/src/gmp.tar.xz" -C "$WORK/src"
  fi
fi
SOURCE="$WORK/src/pari-$PARI_VERSION"
REF="pari-$PARI_VERSION"
case "$PARI_VERSION" in master|latest) REF=master;; esac
if [ ! -d "$SOURCE/.git" ]; then
  cached="$HOME/.cache/parikt-jvm-native/src/pari-$PARI_VERSION"
  if [ "$REF" != master ] && git -C "$cached" rev-parse --verify "refs/tags/$REF" >/dev/null 2>&1; then
    log "reusing local PARI $REF source"
    git -c advice.detachedHead=false clone -q --no-hardlinks --branch "$REF" "$cached" "$SOURCE"
    git -C "$SOURCE" remote set-url origin https://pari.math.u-bordeaux.fr/git/pari.git
  else
    log "fetching PARI $REF"
    git -c advice.detachedHead=false clone -q --depth 1 --branch "$REF" https://pari.math.u-bordeaux.fr/git/pari.git "$SOURCE"
  fi
elif [ "$REF" = master ]; then
  git -C "$SOURCE" fetch --depth 1 origin master
  git -C "$SOURCE" checkout -q --detach FETCH_HEAD
fi
SOURCE_ID=$(git -C "$SOURCE" rev-parse HEAD)
GMP_STAGE="$WORK/gmp-$GMP_VERSION-$TARGET-$BUILD_ID"
if [ "$FORCE" = 1 ] || [ ! -f "$GMP_STAGE/lib/libgmp.a" ]; then
  log "building static GMP $GMP_VERSION"
  build=$(mktemp -d "$WORK/gmp-build.XXXXXX")
  (
    cd "$build" && CC_FOR_BUILD=cc "$GMP_SRC/configure" --disable-shared --enable-static --with-pic \
      --host="$GMP_HOST" --build="$(cc -dumpmachine)" \
      --prefix="$GMP_STAGE" &&
    make -j"$JOBS" SUBDIRS='mpn mpz mpq mpf printf scanf rand' &&
    mkdir -p "$GMP_STAGE/lib" "$GMP_STAGE/include" &&
    cp .libs/libgmp.a "$GMP_STAGE/lib/" && cp gmp.h "$GMP_STAGE/include/"
  ) >"$WORK/logs/gmp-$TARGET.log" 2>&1 || { tail -n 60 "$WORK/logs/gmp-$TARGET.log" >&2; die "GMP build failed"; }
fi
PARI_STAGE="$WORK/pari-$PARI_VERSION-$SOURCE_ID-$TARGET-$BUILD_ID"
if [ "$FORCE" = 1 ] || [ ! -f "$PARI_STAGE/lib/libpari.a" ]; then
  log "building static PARI $PARI_VERSION"
  build=$(mktemp -d "$WORK/pari-build.XXXXXX")
  # A clean source copy avoids sharing generated Configure files with Android/JVM builds.
  git -C "$SOURCE" archive HEAD | tar -x -C "$build"
  (
    cd "$build" &&
    if [ "$TARGET" = windows-x86_64 ]; then
      RUNTEST=true ./Configure --host=x86_64-mingw --graphic=none --without-readline \
        --builddir=Onative --prefix="$PARI_STAGE" --datadir=@ --with-gmp="$GMP_STAGE" &&
      # PARI's cross probes cannot run Windows programs in this build environment.
      # Match the verified JVM Windows configuration, including PARI's 64-bit long model.
      sed -i -E \
        -e "s!^asmarch='ix86'!asmarch='x86_64'!" \
        -e "s!^kernlvl0='ix86'!kernlvl0='x86_64'!" \
        -e "s!^doubleformat='.*'!doubleformat='-'!" \
        -e "s!^sizeof_long='4'!sizeof_long='8'!" \
        -e "s!^kernlvl1='none'!kernlvl1='gmp'!" \
        -e "s!^libpari_base='pari'!libpari_base='pari-gmp'!" \
        -e "s!^gmp=''!gmp='$GMP_STAGE/lib'!" \
        -e "s!^GMPLIBS=''!GMPLIBS='-L$GMP_STAGE/lib -lgmp'!" \
        -e "s!^GMPINCLUDE=''!GMPINCLUDE='-I$GMP_STAGE/include'!" Onative/pari.cfg &&
      RUNTEST=true ./Configure -l Onative/pari.cfg
    else
      ./Configure --graphic=none --without-readline --builddir=Onative \
        --prefix="$PARI_STAGE" --datadir=/pari-data --with-gmp="$GMP_STAGE"
    fi &&
    make -C Onative -j"$JOBS" lib-sta && make -C Onative install-include install-lib-sta
  ) >"$WORK/logs/pari-$TARGET-$PARI_VERSION.log" 2>&1 || {
    tail -n 80 "$WORK/logs/pari-$TARGET-$PARI_VERSION.log" >&2; die "PARI build failed";
  }
  if [ ! -f "$PARI_STAGE/lib/libpari.a" ]; then
    # PARI installs Windows static archives in bin, like its Windows DLLs.
    for directory in "$PARI_STAGE/bin" "$PARI_STAGE/lib"; do
      for name in libpari.a libpari-gmp.a; do
        if [ -f "$directory/$name" ]; then
          mkdir -p "$PARI_STAGE/lib"
          cp "$directory/$name" "$PARI_STAGE/lib/libpari.a"
          break 2
        fi
      done
    done
  fi
  [ -f "$PARI_STAGE/lib/libpari.a" ] || die "PARI did not install a static archive under $PARI_STAGE"
fi
log "building the Kotlin/Native C bridge"
build=$(mktemp -d "$WORK/bridge-build.XXXXXX")
FLAGS=(-DPARIKT_STATIC_PARI=1)
if grep -q functions_hash_MASK "$PARI_STAGE/include/pari/paripriv.h"; then FLAGS+=(-DPARIDROID_USE_HASH_MASK=1); fi
for unit in paridroid/paridroid kotlin-native/bridge; do
  "$CC" $CFLAGS "${FLAGS[@]}" -I"$PARI_STAGE/include" -I"$GMP_STAGE/include" \
    -I"$SCRIPT_DIR/paridroid" -c "$SCRIPT_DIR/$unit.c" -o "$build/$(basename "$unit").o"
done
(
  cd "$build"
  "$AR" rcs libbridge.a paridroid.o bridge.o
  cp "$PARI_STAGE/lib/libpari.a" "$GMP_STAGE/lib/libgmp.a" .
  # One archive lets the final linker resolve bridge -> PARI -> GMP internally.
  # It also avoids depending on cinterop's archive ordering.
  "$AR" -M <<'MRI'
CREATE libparikt.a
ADDLIB libbridge.a
ADDLIB libpari.a
ADDLIB libgmp.a
SAVE
END
MRI
  cp libparikt.a "$OUT/lib/"
)
cp "$SCRIPT_DIR/kotlin-native/bridge.h" "$OUT/include/"
printf '%s\n' "$STAMP" > "$OUT/.stamp"
printf '%s\n' "$SOURCE_ID" > "$OUT/.pari-source"
log "built $TARGET PARI $PARI_VERSION artifacts in $OUT"
