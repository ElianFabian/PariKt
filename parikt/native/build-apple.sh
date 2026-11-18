#!/usr/bin/env bash
# Xcode SDK-specific static PARI/GMP archive for Kotlin/Native. Requires a Mac.
set -euo pipefail
PARI_VERSION= TARGET= OUT= MINIMUM=
GMP_VERSION=6.3.0
FORCE=0
WORK=${PARIKT_APPLE_WORK_DIR:-$HOME/.cache/parikt-apple}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
die() { echo "error: $*" >&2; exit 1; }
log() { echo "[apple-native] $*"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --pari-version) PARI_VERSION=$2; shift 2;;
    --target) TARGET=$2; shift 2;;
    --out) OUT=$2; shift 2;;
    --minimum-version) MINIMUM=$2; shift 2;;
    --force) FORCE=1; shift;;
    *) die "unknown argument: $1";;
  esac
done
[ -n "$PARI_VERSION" ] && [ -n "$OUT" ] || die "--pari-version and --out are required"
case "$TARGET" in
  macosX64) SDK=macosx; CPU=x86_64; OS=macos; FAMILY=macos;;
  macosArm64) SDK=macosx; CPU=arm64; OS=macos; FAMILY=macos;;
  iosArm64) SDK=iphoneos; CPU=arm64; OS=ios; FAMILY=ios;;
  iosX64) SDK=iphonesimulator; CPU=x86_64; OS=ios; FAMILY=ios;;
  iosSimulatorArm64) SDK=iphonesimulator; CPU=arm64; OS=ios; FAMILY=ios;;
  tvosArm64) SDK=appletvos; CPU=arm64; OS=tvos; FAMILY=tvos;;
  tvosX64) SDK=appletvsimulator; CPU=x86_64; OS=tvos; FAMILY=tvos;;
  tvosSimulatorArm64) SDK=appletvsimulator; CPU=arm64; OS=tvos; FAMILY=tvos;;
  watchosArm64) SDK=watchos; CPU=arm64_32; OS=watchos; FAMILY=watchos;;
  watchosDeviceArm64) SDK=watchos; CPU=arm64; OS=watchos; FAMILY=watchos;;
  watchosX64) SDK=watchsimulator; CPU=x86_64; OS=watchos; FAMILY=watchos;;
  watchosSimulatorArm64) SDK=watchsimulator; CPU=arm64; OS=watchos; FAMILY=watchos;;
  *) die "unsupported Apple target: $TARGET";;
esac
[ "$(uname -s)" = Darwin ] || die "Apple native dependencies require macOS and Xcode"
[[ "$MINIMUM" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || die "--minimum-version must match the Kotlin/Native deployment target"
for tool in xcrun xcodebuild make m4 git curl tar shasum perl; do
  command -v "$tool" >/dev/null || die "missing required build tool: $tool"
done
SDK_PATH=$(xcrun --sdk "$SDK" --show-sdk-path)
SDK_VERSION=$(xcrun --sdk "$SDK" --show-sdk-version)
CLANG=$(xcrun --sdk "$SDK" --find clang)
LIBTOOL=$(xcrun --sdk "$SDK" --find libtool)
AR=$(xcrun --sdk "$SDK" --find ar)
RANLIB=$(xcrun --sdk "$SDK" --find ranlib)
# Explicit target flags own deployment/SDK selection; host code generators must remain macOS.
unset SDKROOT MACOSX_DEPLOYMENT_TARGET IPHONEOS_DEPLOYMENT_TARGET TVOS_DEPLOYMENT_TARGET WATCHOS_DEPLOYMENT_TARGET
TRIPLE="$CPU-apple-$OS$MINIMUM"
case "$SDK" in *simulator) TRIPLE="$TRIPLE-simulator";; esac
# arm64_32 has 32-bit long/pointers. Portable kernels avoid ARM32 assembler on this ABI.
LONG_BYTES=8 GMP_HOST=aarch64-apple-darwin PARI_HOST=aarch64-darwin
case "$CPU" in
  x86_64) GMP_HOST=x86_64-apple-darwin; PARI_HOST=x86_64-darwin;;
  arm64_32) LONG_BYTES=4; GMP_HOST=arm-apple-darwin; PARI_HOST=arm-darwin;;
esac
source "$SCRIPT_DIR/build-lock.sh"
parikt_lock_work_directory "$WORK"
JOBS=${PARIKT_NATIVE_JOBS:-$(sysctl -n hw.ncpu)}
[ "$JOBS" -le 8 ] || JOBS=8
mkdir -p "$WORK/src" "$WORK/logs" "$OUT/lib" "$OUT/include"
SOURCE="$WORK/src/pari-$PARI_VERSION-work"
SOURCE_ID=$(bash "$SCRIPT_DIR/pari-source.sh" --pari-version "$PARI_VERSION" \
  --destination "$SOURCE" --workspace-key "$(git hash-object "${BASH_SOURCE[0]}"):$TARGET")
INPUT_HASH=$(cat "${BASH_SOURCE[0]}" "$SCRIPT_DIR/pari-source.sh" "$SCRIPT_DIR/build-lock.sh" \
  "$SCRIPT_DIR/kotlin-native/bridge.c" "$SCRIPT_DIR/kotlin-native/bridge.h" \
  "$SCRIPT_DIR/paridroid/paridroid.c" "$SCRIPT_DIR/paridroid/paridroid.h" | shasum -a 256 | cut -d' ' -f1)
COMPILER=$("$CLANG" --version | head -n 1)
XCODE=$(xcodebuild -version | tr '\n' ' ')
STAMP="pari=$PARI_VERSION revision=$SOURCE_ID gmp=$GMP_VERSION target=$TARGET triple=$TRIPLE sdk=$SDK_VERSION path=$SDK_PATH long=$LONG_BYTES compiler=$COMPILER xcode=$XCODE inputs=$INPUT_HASH"
BUILD_ID=$(printf '%s' "$STAMP" | shasum -a 256 | cut -c1-20)
TOOLCHAIN_ID=$({ git hash-object "${BASH_SOURCE[0]}"; printf '%s\n' "$COMPILER" "$XCODE" "$TRIPLE" "$SDK_PATH" "$SDK_VERSION"; } | shasum -a 256 | cut -c1-20)
if [ "$FORCE" = 0 ] && [ "$(cat "$OUT/.stamp" 2>/dev/null || true)" = "$STAMP" ] && \
   [ -f "$OUT/lib/libparikt.a" ] && [ -f "$OUT/include/bridge.h" ]; then
  log "PARI $PARI_VERSION $TARGET artifacts are up to date"; exit 0
fi
# Keep target flags in a wrapper, so Configure cannot accidentally probe/build host objects.
TOOLS="$WORK/tools/$BUILD_ID"
mkdir -p "$TOOLS"
printf '#!/usr/bin/env bash\nexec %q -target %q -isysroot %q "$@"\n' "$CLANG" "$TRIPLE" "$SDK_PATH" > "$TOOLS/cc"
chmod +x "$TOOLS/cc"
printf -v CC '%q' "$TOOLS/cc"
export CC AR RANLIB
export CFLAGS='-O2 -std=gnu17 -fPIC'
export CPPFLAGS= LDFLAGS=
# Fail before building dependencies if the selected compiler's data model is wrong.
printf '_Static_assert(sizeof(long) == %s, "wrong long ABI");\n_Static_assert(sizeof(void*) == %s, "wrong pointer ABI");\n' \
  "$LONG_BYTES" "$LONG_BYTES" | "$TOOLS/cc" -std=c11 -x c -c -o "$TOOLS/abi.o" -
GMP_SRC="$WORK/src/gmp-$GMP_VERSION"
if [ ! -f "$GMP_SRC/configure" ]; then
  curl -fL --retry 3 -o "$WORK/src/gmp.tar.xz" "https://ftp.gnu.org/gnu/gmp/gmp-$GMP_VERSION.tar.xz"
  echo "a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898  $WORK/src/gmp.tar.xz" | shasum -a 256 -c -
  tar -xf "$WORK/src/gmp.tar.xz" -C "$WORK/src"
fi
STAGE="$WORK/stage/$BUILD_ID"
# Different PARI versions/modules reuse the same GMP component for this SDK/compiler/ABI.
GMP_STAGE="$WORK/gmp-$GMP_VERSION-$TARGET-$TOOLCHAIN_ID"
if [ "$FORCE" = 1 ] || [ ! -f "$GMP_STAGE/lib/libgmp.a" ]; then
  build=$(mktemp -d "$WORK/gmp-build.XXXXXX")
  (cd "$build"
   CC_FOR_BUILD=cc "$GMP_SRC/configure" --host="$GMP_HOST" --build="$("$GMP_SRC/config.guess")" \
     --disable-assembly --disable-shared --enable-static --with-pic --prefix="$GMP_STAGE"
   make -j"$JOBS" SUBDIRS='mpn mpz mpq mpf printf scanf rand'
   mkdir -p "$GMP_STAGE/lib" "$GMP_STAGE/include"
   cp .libs/libgmp.a "$GMP_STAGE/lib/"; cp gmp.h "$GMP_STAGE/include/"
  ) >"$WORK/logs/gmp-$TARGET.log" 2>&1 || { tail -n 70 "$WORK/logs/gmp-$TARGET.log" >&2; die "GMP build failed"; }
fi
PARI_STAGE="$STAGE/pari"
if [ "$FORCE" = 1 ] || [ ! -f "$PARI_STAGE/lib/libpari.a" ]; then
  build=$(mktemp -d "$WORK/pari-build.XXXXXX")
  cp -R "$SOURCE/." "$build/"
  # Configure must never execute device/simulator probes on the build Mac. Only these two
  # probes return target data; feature availability is still determined by compile/link tests.
  endian=-; [ "$LONG_BYTES" = 8 ] || endian=1
  cat > "$build/run-target-probe" <<EOF
#!/bin/sh
case "\$1" in
  *-endian*) printf '%s\n' '$endian';;
  *-gmp*) printf '%s\n' '$GMP_VERSION';;
esac
exit 0
EOF
  chmod +x "$build/run-target-probe"
  (cd "$build"
   RUNTEST=./run-target-probe ./Configure --host="$PARI_HOST" --kernel=none-gmp \
     --graphic=none --without-readline --builddir=Onative --prefix="$PARI_STAGE" \
     --datadir=/pari-data --with-gmp="$GMP_STAGE"
   # Dynamic code loading/subprocess APIs are unavailable in mobile app sandboxes.
   if [ "$FAMILY" != macos ]; then
     perl -pi -e "s/^has_(system|waitpid|setsid|dlopen)='.*'/has_\$1='no'/" Onative/pari.cfg
     RUNTEST=./run-target-probe ./Configure -l Onative/pari.cfg
   fi
   make -C Onative -j"$JOBS" lib-sta
   make -C Onative install-include install-lib-sta
  ) >"$WORK/logs/pari-$TARGET.log" 2>&1 || { tail -n 80 "$WORK/logs/pari-$TARGET.log" >&2; die "PARI build failed"; }
fi
build=$(mktemp -d "$WORK/bridge-build.XXXXXX")
FLAGS=(-DPARIKT_STATIC_PARI=1)
if grep -q functions_hash_MASK "$PARI_STAGE/include/pari/paripriv.h"; then FLAGS+=(-DPARIDROID_USE_HASH_MASK=1); fi
for unit in paridroid/paridroid kotlin-native/bridge; do
  "$TOOLS/cc" -O2 -std=gnu17 -fPIC "${FLAGS[@]}" -I"$PARI_STAGE/include" -I"$GMP_STAGE/include" \
    -I"$SCRIPT_DIR/paridroid" -c "$SCRIPT_DIR/$unit.c" -o "$build/$(basename "$unit").o"
done
"$LIBTOOL" -static -o "$build/libparikt.a" "$build/paridroid.o" "$build/bridge.o" \
  "$PARI_STAGE/lib/libpari.a" "$GMP_STAGE/lib/libgmp.a"
cp "$build/libparikt.a" "$OUT/lib/"
cp "$SCRIPT_DIR/kotlin-native/bridge.h" "$OUT/include/"
printf '%s\n' "$STAMP" > "$OUT/.stamp"
printf '%s\n' "$SOURCE_ID" > "$OUT/.pari-source"
log "built $TARGET PARI $PARI_VERSION artifacts in $OUT"
