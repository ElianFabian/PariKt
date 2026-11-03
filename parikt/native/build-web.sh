#!/usr/bin/env bash
# Build one Emscripten PARI engine shared by Kotlin/JS and Kotlin/WasmJS.
set -euo pipefail
PARI_VERSION=""
GMP_VERSION=6.3.0
EMSCRIPTEN_VERSION=4.0.21
EMSCRIPTEN_RELEASE=d70a5da89b3e673bf6a482724478fc17e81e575e
WORK_DIR="${PARIKT_WEB_WORK_DIR:-$HOME/.cache/parikt-web}"
OUT_DIR=""
SDK=""
SOURCE=""
FORCE=0
SMOKE=0
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
die() { echo "error: $*" >&2; exit 1; }
log() { echo "[web-native] $*"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --pari-version) PARI_VERSION=$2; shift 2;;
    --out) OUT_DIR=$2; shift 2;;
    --work) WORK_DIR=$2; shift 2;;
    --emsdk) SDK=$2; shift 2;;
    --pari-source) SOURCE=$2; shift 2;;
    --force) FORCE=1; shift;;
    --smoke) SMOKE=1; shift;;
    *) die "unknown option '$1'";;
  esac
done
# Serialize shared compiler/source stages across independent consumer builds.
source "$(dirname "${BASH_SOURCE[0]}")/build-lock.sh"
parikt_lock_work_directory "$WORK_DIR"

[ -n "$PARI_VERSION" ] || die "--pari-version is required; the consuming build must select PARI"
[ -n "$OUT_DIR" ] || die "--out is required"
[[ "$PARI_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)*$ || "$PARI_VERSION" = master || "$PARI_VERSION" = latest ]] || die "invalid PARI version"
for tool in bash curl tar xz make m4 git python3 cc; do
  command -v "$tool" >/dev/null || die "missing host tool '$tool' (Linux/WSL: build-essential m4 git curl xz-utils python3)"
done
mkdir -p "$WORK_DIR" "$OUT_DIR"
WORK_DIR=$(cd "$WORK_DIR" && pwd)
OUT_DIR=$(cd "$OUT_DIR" && pwd)
hash_file() { python3 -c 'import hashlib,sys; print(hashlib.file_digest(open(sys.argv[1], "rb"), "sha256").hexdigest())' "$1"; }
download_verified() {
  local url=$1 archive=$2 expected=$3
  if [ ! -f "$archive" ] || [ "$(hash_file "$archive")" != "$expected" ]; then
    curl -fL --retry 3 -o "$archive.part" "$url"
    [ "$(hash_file "$archive.part")" = "$expected" ] || die "checksum mismatch: $url"
    mv "$archive.part" "$archive"
  fi
}

if [ -n "$SDK" ]; then
  # Explicit SDK mode is useful for development and hosts not bootstrapped below.
  [ -f "$SDK/emsdk_env.sh" ] || die "--emsdk must point to an activated Emscripten SDK"
  source "$SDK/emsdk_env.sh" >/dev/null 2>&1
else
  [ "$(uname -s)-$(uname -m)" = Linux-x86_64 ] || die "automatic web toolchain currently supports Linux/WSL x86_64; pass --emsdk on other hosts"
  TOOLS="$WORK_DIR/tools/emscripten-$EMSCRIPTEN_VERSION-linux-x86_64"
  mkdir -p "$TOOLS/downloads" "$TOOLS/upstream" "$TOOLS/node"
  download_verified "https://storage.googleapis.com/webassembly/emscripten-releases-builds/linux/$EMSCRIPTEN_RELEASE/wasm-binaries.tar.xz" \
    "$TOOLS/downloads/compiler.tar.xz" e8516b903cd4dc16bf5aa2aacd826adcff5ff1d97d3d88e5e3871decd94cd8b2
  download_verified "https://storage.googleapis.com/webassembly/emscripten-releases-builds/deps/node-v22.16.0-linux-x64.tar.xz" \
    "$TOOLS/downloads/node.tar.xz" f4cb75bb036f0d0eddf6b79d9596df1aaab9ddccd6a20bf489be5abe9467e84e
  if [ ! -f "$TOOLS/.installed" ]; then
    log "extracting checksum-verified Emscripten $EMSCRIPTEN_VERSION and Node 22.16.0"
    tar -xf "$TOOLS/downloads/compiler.tar.xz" --strip-components=1 -C "$TOOLS/upstream"
    tar -xf "$TOOLS/downloads/node.tar.xz" --strip-components=1 -C "$TOOLS/node"
    python3 - "$TOOLS" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
(root / '.emscripten').write_text(
    f'LLVM_ROOT = {str(root / "upstream/bin")!r}\n'
    f'BINARYEN_ROOT = {str(root / "upstream")!r}\n'
    f'NODE_JS = [{str(root / "node/bin/node")!r}]\n')
PY
    touch "$TOOLS/.installed"
  fi
  export EM_CONFIG="$TOOLS/.emscripten" EM_CACHE="$TOOLS/cache"
  export PATH="$TOOLS/upstream/emscripten:$TOOLS/node/bin:$PATH"
fi
emcc --version | grep -q "$EMSCRIPTEN_VERSION" || die "web build requires Emscripten $EMSCRIPTEN_VERSION"

mkdir -p "$WORK_DIR/src" "$WORK_DIR/logs"
if [ -z "$SOURCE" ]; then
  SOURCE="$WORK_DIR/src/pari-$PARI_VERSION-work"
  SOURCE_REVISION=$(bash "$SCRIPT_DIR/pari-source.sh" --pari-version "$PARI_VERSION" \
    --destination "$SOURCE" --seed "$WORK_DIR/src/pari-$PARI_VERSION" \
    --workspace-key "$(git hash-object "${BASH_SOURCE[0]}"):web")
fi
SOURCE=$(cd "$SOURCE" && pwd)
[ -f "$SOURCE/Configure" ] || die "PARI sources do not contain Configure"
# Hash source contents, excluding Git internals; --pari-source is an explicit source override.
SOURCE_HASH=$(python3 - "$SOURCE" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1]); digest = hashlib.sha256()
for file in sorted(root.rglob('*')):
    relative = file.relative_to(root)
    if '.git' in relative.parts or not file.is_file(): continue
    digest.update(str(relative).encode()); digest.update(b'\0'); digest.update(file.read_bytes())
print(digest.hexdigest())
PY
)
INPUT_HASH=$(cat "${BASH_SOURCE[0]}" "$SCRIPT_DIR/pari-source.sh" "$SCRIPT_DIR/build-lock.sh" "$SCRIPT_DIR/web/bridge.c" "$SCRIPT_DIR/web/parikt.mjs" "$SCRIPT_DIR/web/package.json" \
  "$SCRIPT_DIR/paridroid/paridroid.c" "$SCRIPT_DIR/paridroid/paridroid.h" | python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())')
STAMP="pari=$PARI_VERSION source=$SOURCE_HASH gmp=$GMP_VERSION emscripten=$EMSCRIPTEN_VERSION inputs=$INPUT_HASH"
run_smoke() {
  [ "$SMOKE" = 1 ] || return 0
  local expected="$PARI_VERSION"
  if [ "$expected" = master ] || [ "$expected" = latest ]; then expected=""; fi
  node "$SCRIPT_DIR/web/smoke.mjs" "$OUT_DIR" "$expected"
}
if [ "$FORCE" = 0 ] && [ -f "$OUT_DIR/.stamp" ] && [ "$(cat "$OUT_DIR/.stamp")" = "$STAMP" ] && \
  [ -f "$OUT_DIR/parikt-engine.mjs" ] && [ -f "$OUT_DIR/parikt-engine.wasm" ] && [ -f "$OUT_DIR/parikt.mjs" ] && [ -f "$OUT_DIR/package.json" ]; then
  log "PARI $PARI_VERSION web artifacts are up to date at $OUT_DIR"
  run_smoke
  exit 0
fi
GMP_SRC="$WORK_DIR/src/gmp-$GMP_VERSION"
if [ ! -f "$GMP_SRC/configure" ]; then
  download_verified "https://ftp.gnu.org/gnu/gmp/gmp-$GMP_VERSION.tar.xz" "$WORK_DIR/src/gmp.tar.xz" \
    a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898
  tar -xf "$WORK_DIR/src/gmp.tar.xz" -C "$WORK_DIR/src"
fi
BUILD=$(mktemp -d "$WORK_DIR/build-$PARI_VERSION.XXXXXX")
PARI_LIBRARY_KEY=$(printf '%s' "$SOURCE_HASH $EMSCRIPTEN_VERSION $GMP_VERSION $INPUT_HASH" | python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest()[:16])')
STAGE="$WORK_DIR/pari-$PARI_VERSION-$PARI_LIBRARY_KEY"
GMP_STAGE="$WORK_DIR/gmp-$GMP_VERSION-emscripten-$EMSCRIPTEN_VERSION"
mkdir -p "$BUILD/gmp" "$STAGE"
JOBS=${PARIKT_WEB_JOBS:-4}
if [ "$FORCE" = 1 ] || [ ! -f "$GMP_STAGE/.ready-v1" ] || [ ! -f "$GMP_STAGE/lib/libgmp.a" ] || [ ! -f "$GMP_STAGE/include/gmp.h" ]; then
log "building static GMP $GMP_VERSION (log: $WORK_DIR/logs/gmp-$PARI_VERSION.log)"
(
  cd "$BUILD/gmp" &&
  CC_FOR_BUILD=cc emconfigure "$GMP_SRC/configure" --host=none --build="$(cc -dumpmachine)" \
    --disable-assembly --disable-shared --enable-static --prefix="$GMP_STAGE" ABI=standard CFLAGS=-O2 &&
  emmake make -j"$JOBS" MAKEINFO=true &&
  emmake make install-libLTLIBRARIES install-includeHEADERS install-nodist_includeexecHEADERS
) >"$WORK_DIR/logs/gmp-$PARI_VERSION.log" 2>&1 || { tail -n 60 "$WORK_DIR/logs/gmp-$PARI_VERSION.log" >&2; die "GMP web build failed"; }
touch "$GMP_STAGE/.ready-v1"
fi
if [ "$FORCE" = 1 ] || [ ! -f "$STAGE/.ready" ] || [ ! -f "$STAGE/lib/libpari.a" ] || [ ! -f "$STAGE/include/pari/paripriv.h" ]; then
cp -R "$SOURCE" "$BUILD/pari"
# Older PARI releases return a pointer from this long-returning, unreachable error branch.
# Current Clang diagnoses it when pipes are disabled for the browser. Patch only our build copy.
python3 - "$BUILD/pari/src/language/es.c" <<'PY'
import pathlib, sys
file = pathlib.Path(sys.argv[1])
text = file.read_text()
text = text.replace('pari_err(e_ARCH,"pipes");\n  return NULL;', 'pari_err(e_ARCH,"pipes");\n  return 0;')
file.write_text(text)
PY
log "building PARI $PARI_VERSION (log: $WORK_DIR/logs/pari-$PARI_VERSION.log)"
(
  cd "$BUILD/pari" &&
  CFLAGS='-O2 -std=gnu17' emconfigure ./Configure --host=wasm-emscripten --graphic=none \
    --without-readline --builddir=Oweb --prefix="$STAGE" --datadir=/pari-data \
    --with-gmp="$GMP_STAGE" &&
  # PARI's UNIX macro enables getpwuid, which has no browser implementation.
  echo '#undef UNIX' >> Oweb/paricfg.h &&
  emmake make -C Oweb -j"$JOBS" lib-sta &&
  emmake make -C Oweb install-include install-lib-sta
) >"$WORK_DIR/logs/pari-$PARI_VERSION.log" 2>&1 || { tail -n 80 "$WORK_DIR/logs/pari-$PARI_VERSION.log" >&2; die "PARI web build failed"; }
touch "$STAGE/.ready"
fi
log "linking PARI web bridge"
WRAPPER_FLAGS=(-DPARIKT_STATIC_PARI=1)
if grep -q 'functions_hash_MASK' "$STAGE/include/pari/paripriv.h"; then WRAPPER_FLAGS+=(-DPARIDROID_USE_HASH_MASK=1); fi
emcc -O2 -std=gnu17 "${WRAPPER_FLAGS[@]}" -I"$STAGE/include" -I"$STAGE/include/pari" -I"$GMP_STAGE/include" -I"$SCRIPT_DIR/paridroid" \
  "$SCRIPT_DIR/paridroid/paridroid.c" "$SCRIPT_DIR/web/bridge.c" "$STAGE/lib/libpari.a" "$GMP_STAGE/lib/libgmp.a" \
  --no-entry -sMODULARIZE=1 -sEXPORT_ES6=1 -sENVIRONMENT=web,node -sALLOW_MEMORY_GROWTH=1 \
  -sSTACK_SIZE=2097152 -sINITIAL_MEMORY=33554432 \
  '-sEXPORTED_FUNCTIONS=["_parikt_web_init","_parikt_web_eval","_parikt_web_command_count","_parikt_web_command","_parikt_web_version","_parikt_web_close"]' \
  '-sEXPORTED_RUNTIME_METHODS=["ccall"]' -o "$BUILD/parikt-engine.mjs"
cp "$BUILD/parikt-engine.mjs" "$BUILD/parikt-engine.wasm" "$SCRIPT_DIR/web/parikt.mjs" "$OUT_DIR/"
cp "$SCRIPT_DIR/web/package.json" "$OUT_DIR/"
printf '%s\n' "$STAMP" > "$OUT_DIR/.stamp"
log "built PARI $PARI_VERSION web artifacts at $OUT_DIR"
run_smoke
