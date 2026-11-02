#!/usr/bin/env bash
# Static PARI/GMP and C bridge for Kotlin/Native. No JDK or JNI dependency.
set -euo pipefail
PARI_VERSION=2.17.1
GMP_VERSION=6.3.0
OUT=""
FORCE=0
TOOLCHAIN=""
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
    *) die "unknown argument: $1";;
  esac
done
[ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] || die "linuxX64 dependencies must be built on Linux/WSL x86_64"
[ -n "$OUT" ] || die "--out is required"
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
CC="$TOOLCHAIN/bin/x86_64-unknown-linux-gnu-gcc"
AR="$TOOLCHAIN/bin/x86_64-unknown-linux-gnu-ar"
[ -x "$CC" ] && [ -x "$AR" ] || die "missing Kotlin/Native Linux C compiler under $TOOLCHAIN"
export CC AR
for tool in cc ar make m4 git curl tar xz sha256sum; do
  command -v "$tool" >/dev/null || die "missing '$tool'; install a C build environment, m4, git, curl, tar and xz"
done
mkdir -p "$WORK/src" "$WORK/logs" "$OUT/lib" "$OUT/include"
INPUT_HASH=$(cat "${BASH_SOURCE[0]}" "$SCRIPT_DIR/kotlin-native/bridge.c" \
  "$SCRIPT_DIR/kotlin-native/bridge.h" "$SCRIPT_DIR/paridroid/paridroid.c" \
  "$SCRIPT_DIR/paridroid/paridroid.h" | sha256sum | cut -d' ' -f1)
COMPILER="$("$CC" --version | sed -n '1p') [$(basename "$TOOLCHAIN")]"
BUILD_ID=$( { cat "${BASH_SOURCE[0]}"; printf '%s' "$COMPILER"; } | sha256sum | cut -c1-16)
STAMP="pari=$PARI_VERSION gmp=$GMP_VERSION linuxX64 inputs=$INPUT_HASH compiler=$COMPILER"
if [ "$FORCE" = 0 ] && [ "$PARI_VERSION" != master ] && [ "$PARI_VERSION" != latest ] && \
   [ -f "$OUT/.stamp" ] && [ "$(cat "$OUT/.stamp")" = "$STAMP" ] && \
   [ -f "$OUT/lib/libparikt.a" ] && [ -f "$OUT/include/bridge.h" ]; then
  log "PARI $PARI_VERSION linuxX64 artifacts are up to date"
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
GMP_STAGE="$WORK/gmp-$GMP_VERSION-linuxX64-$BUILD_ID"
if [ "$FORCE" = 1 ] || [ ! -f "$GMP_STAGE/lib/libgmp.a" ]; then
  log "building static GMP $GMP_VERSION"
  build=$(mktemp -d "$WORK/gmp-build.XXXXXX")
  (
    cd "$build" && CC_FOR_BUILD=cc "$GMP_SRC/configure" --disable-shared --enable-static --with-pic \
      --host=x86_64-pc-linux-gnu --build="$(cc -dumpmachine)" \
      --prefix="$GMP_STAGE" CFLAGS='-O2 -std=gnu17' &&
    make -j"$JOBS" SUBDIRS='mpn mpz mpq mpf printf scanf rand' &&
    mkdir -p "$GMP_STAGE/lib" "$GMP_STAGE/include" &&
    cp .libs/libgmp.a "$GMP_STAGE/lib/" && cp gmp.h "$GMP_STAGE/include/"
  ) >"$WORK/logs/gmp.log" 2>&1 || { tail -n 60 "$WORK/logs/gmp.log" >&2; die "GMP build failed"; }
fi
PARI_STAGE="$WORK/pari-$PARI_VERSION-$SOURCE_ID-linuxX64-$BUILD_ID"
if [ "$FORCE" = 1 ] || [ ! -f "$PARI_STAGE/lib/libpari.a" ]; then
  log "building static PARI $PARI_VERSION"
  build=$(mktemp -d "$WORK/pari-build.XXXXXX")
  # A clean source copy avoids sharing generated Configure files with Android/JVM builds.
  git -C "$SOURCE" archive HEAD | tar -x -C "$build"
  (
    cd "$build" && CFLAGS='-O2 -std=gnu17 -fPIC' ./Configure --graphic=none --without-readline \
      --builddir=Onative --prefix="$PARI_STAGE" --datadir=/pari-data --with-gmp="$GMP_STAGE" &&
    make -C Onative -j"$JOBS" lib-sta && make -C Onative install-include install-lib-sta
  ) >"$WORK/logs/pari-$PARI_VERSION.log" 2>&1 || {
    tail -n 80 "$WORK/logs/pari-$PARI_VERSION.log" >&2; die "PARI build failed";
  }
fi
log "building the Kotlin/Native C bridge"
build=$(mktemp -d "$WORK/bridge-build.XXXXXX")
FLAGS=(-DPARIKT_STATIC_PARI=1)
if grep -q functions_hash_MASK "$PARI_STAGE/include/pari/paripriv.h"; then FLAGS+=(-DPARIDROID_USE_HASH_MASK=1); fi
for unit in paridroid/paridroid kotlin-native/bridge; do
  "$CC" -O2 -std=gnu17 -fPIC "${FLAGS[@]}" -I"$PARI_STAGE/include" -I"$GMP_STAGE/include" \
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
log "built linuxX64 PARI $PARI_VERSION artifacts in $OUT"
