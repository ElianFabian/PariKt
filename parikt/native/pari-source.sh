#!/usr/bin/env bash
# One acquisition cache; callers own separate mutable build workspaces.
# stdout is only the resolved commit, so builders can include it in fingerprints.
set -euo pipefail
version=""
destination=""
workspace_key=""
seeds=()
cache=${PARIKT_SOURCE_CACHE_DIR:-$HOME/.cache/parikt/sources}
remote=${PARIKT_SOURCE_REMOTE:-https://pari.math.u-bordeaux.fr/git/pari.git}
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
log() { printf '[pari-source] %s\n' "$*" >&2; }
while [ $# -gt 0 ]; do
  case "$1" in
    --pari-version) version=$2; shift 2;;
    --destination) destination=$2; shift 2;;
    --workspace-key) workspace_key=$2; shift 2;;
    --seed) seeds+=("$2"); shift 2;;
    *) die "unknown source-cache option: $1";;
  esac
done
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)*$ || "$version" = master || "$version" = latest ]] || die "invalid --pari-version: $version"
[ -n "$destination" ] || die "--destination is required"
[ ! -L "$destination" ] || die "source workspace must not be a symlink: $destination"
mkdir -p "$cache" "$(dirname "$destination")"
cache=$(cd "$cache" && pwd -P)
destination="$(cd "$(dirname "$destination")" && pwd -P)/$(basename "$destination")"
case "$destination" in "$cache"|"$cache/"*|*/.|*/..) die "workspace must be outside the acquisition cache: $destination";; esac
source "$(dirname "${BASH_SOURCE[0]}")/build-lock.sh"
parikt_lock_work_directory "$cache"
store="$cache/pari.git"
if [ ! -d "$store" ]; then
  # Publish a fully initialized store through one atomic symlink operation.
  # Never expose Git's partially copied template/config files at the public path.
  initialized=$(mktemp -d "$cache/repository.XXXXXX")
  if ! git init --bare -q "$initialized" || ! git --git-dir="$initialized" config remote.origin.url "$remote"; then
    rm -rf "$initialized"
    die "cannot initialize PARI acquisition cache"
  fi
  if ! ln -s "$(basename "$initialized")" "$store" 2>/dev/null; then
    rm -rf "$initialized"
    [ -d "$store" ] || die "cannot publish PARI acquisition cache at $store"
  fi
fi
[ "$(git --git-dir="$store" config remote.origin.url)" = "$remote" ] || die "source cache belongs to a different remote; choose a separate PARIKT_SOURCE_CACHE_DIR"
ref="pari-$version"
moving=0
case "$version" in master|latest) ref=master; moving=1;; esac
cached_ref="refs/parikt/$ref"
if [ "$moving" = 1 ] || ! git --git-dir="$store" rev-parse --verify "$cached_ref^{commit}" >/dev/null 2>&1; then
  imported=0
  # Existing downloads can seed the new store without trusting patched working files.
  # Import only the immutable tag's Git objects; never copy a mutable working tree.
  if [ "$moving" = 0 ]; then
    seeds+=("$HOME/.cache/parikt-jvm-native/src/pari-$version"
      "$HOME/.cache/parikt-kotlin-native/src/pari-$version"
      "$HOME/.cache/parikt-web/src/pari-$version"
      "$HOME/.cache/paridroid-native/src/pari-$version")
    for seed in "${seeds[@]}"; do
      if git -C "$seed" rev-parse --verify "refs/tags/$ref^{commit}" >/dev/null 2>&1; then
        log "importing cached $ref Git objects from $seed"
        git --git-dir="$store" fetch -q --depth 1 --no-tags "$seed" "refs/tags/$ref:$cached_ref"
        imported=1
        break
      fi
    done
  fi
  if [ "$imported" = 0 ]; then
    log "fetching $ref from $remote"
    if [ "$moving" = 1 ]; then source_ref=refs/heads/master; else source_ref="refs/tags/$ref"; fi
    git --git-dir="$store" fetch -q --depth 1 --no-tags "$remote" "+$source_ref:$cached_ref"
  fi
else
  log "reusing cached $ref"
fi
revision=$(git --git-dir="$store" rev-parse --verify "$cached_ref^{commit}")
git --git-dir="$store" cat-file -e "$revision:Configure" || die "PARI $ref does not contain Configure"
snapshot="$cache/pari/$revision"
if [ ! -f "$snapshot/Configure" ] || [ "$(cat "$snapshot/.pari-source-revision" 2>/dev/null || true)" != "$revision" ]; then
  mkdir -p "$cache/pari"
  pending="$snapshot.pending"
  rm -rf "$pending"
  mkdir "$pending"
  git --git-dir="$store" archive "$revision" | tar -xf - -C "$pending"
  printf '%s\n' "$revision" > "$pending/.pari-source-revision"
  [ ! -e "$snapshot" ] || rm -rf "$snapshot"
  mv "$pending" "$snapshot"
fi
workspace_identity="$revision:$workspace_key:$(git hash-object "${BASH_SOURCE[0]}")"
if [ "$(cat "$destination/.pari-source-workspace" 2>/dev/null || true)" != "$workspace_identity" ] || [ ! -f "$destination/Configure" ]; then
  if [ -e "$destination" ] && [ ! -f "$destination/.pari-source-revision" ]; then
    die "refusing to replace an unmanaged source workspace: $destination"
  fi
  pending="$destination.pending"
  [ ! -L "$pending" ] || die "source workspace staging path is a symlink: $pending"
  rm -rf "$pending"
  mkdir "$pending"
  cp -R "$snapshot/." "$pending/"
  printf '%s\n' "$workspace_identity" > "$pending/.pari-source-workspace"
  [ ! -e "$destination" ] || rm -rf "$destination"
  mv "$pending" "$destination"
fi
printf '%s\n' "$revision"
