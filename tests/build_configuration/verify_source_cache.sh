#!/usr/bin/env bash
set -euo pipefail
repo=$(pwd)
helper="$repo/parikt/native/pari-source.sh"
scratch=$(mktemp -d "${TMPDIR:-/tmp}/parikt source cache.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
export PARIKT_SOURCE_CACHE_DIR="$scratch/cache"
export PARIKT_SOURCE_REMOTE="$scratch/remote"
git init -q -b master "$PARIKT_SOURCE_REMOTE"
printf 'pristine\n' > "$PARIKT_SOURCE_REMOTE/Configure"
git -C "$PARIKT_SOURCE_REMOTE" add Configure
git -C "$PARIKT_SOURCE_REMOTE" -c user.name=Test -c user.email=test@example.invalid commit -qm initial
git -C "$PARIKT_SOURCE_REMOTE" tag pari-99.0.0
original=$(git -C "$PARIKT_SOURCE_REMOTE" rev-parse HEAD)
# Keep a real acquisition in flight while two peers contend. Do not manipulate
# lock directories owned by workers; delay only the test remote's Git transport.
mkdir "$scratch/bin"
export PARIKT_TEST_REAL_GIT=$(command -v git)
export PARIKT_TEST_DELAY_FILE="$scratch/delay-fetch"
touch "$PARIKT_TEST_DELAY_FILE"
cat > "$scratch/bin/git" <<'SH'
#!/usr/bin/env bash
for argument in "$@"; do
  if [ "$argument" = fetch ] && [ -f "$PARIKT_TEST_DELAY_FILE" ]; then sleep 2; fi
done
exec "$PARIKT_TEST_REAL_GIT" "$@"
SH
chmod +x "$scratch/bin/git"
export PATH="$scratch/bin:$PATH"
for backend in jvm android other; do
  bash "$helper" --pari-version 99.0.0 --destination "$scratch/$backend" >"$scratch/$backend.revision" 2>"$scratch/$backend.log" &
  case "$backend" in jvm) first=$!;; android) second=$!;; other) third=$!;; esac
done
wait "$first" || { cat "$scratch/jvm.log" "$scratch/android.log" >&2; exit 1; }
wait "$second" || { cat "$scratch/jvm.log" "$scratch/android.log" >&2; exit 1; }
wait "$third" || { cat "$scratch/other.log" >&2; exit 1; }
rm "$PARIKT_TEST_DELAY_FILE"
[ "$(cat "$scratch/jvm.revision")" = "$original" ]
cmp "$scratch/jvm.revision" "$scratch/android.revision"
cmp "$scratch/jvm.revision" "$scratch/other.revision"
[ "$(cat "$scratch/jvm.log" "$scratch/android.log" "$scratch/other.log" | grep -c '\[pari-source\] fetching')" = 1 ]
echo 'PASS: concurrent backends fetch a release once and resolve the same commit'
printf 'backend patch\n' > "$scratch/jvm/Configure"
bash "$helper" --pari-version 99.0.0 --destination "$scratch/web" > /dev/null 2>"$scratch/later.log"
! grep -q fetching "$scratch/later.log"
[ "$(cat "$scratch/web/Configure")" = pristine ]
[ "$(cat "$PARIKT_SOURCE_CACHE_DIR/pari/$original/Configure")" = pristine ]
echo 'PASS: later backend reuses pristine canonical sources despite workspace patches'
bash "$helper" --pari-version 99.0.0 --destination "$scratch/jvm" --workspace-key next-recipe > /dev/null 2>"$scratch/recipe.log"
! grep -q fetching "$scratch/recipe.log"
[ "$(cat "$scratch/jvm/Configure")" = pristine ]
echo 'PASS: changed build recipe restores a pristine workspace without another download'
rm "$PARIKT_SOURCE_CACHE_DIR/pari/$original/Configure"
bash "$helper" --pari-version 99.0.0 --destination "$scratch/native" > /dev/null 2>"$scratch/repair.log"
! grep -q fetching "$scratch/repair.log"
[ "$(cat "$scratch/native/Configure")" = pristine ]
echo 'PASS: incomplete snapshot is repaired from cached Git objects without downloading'
bash "$helper" --pari-version master --destination "$scratch/master" >"$scratch/master.revision" 2>"$scratch/master.log"
[ "$(cat "$scratch/master.revision")" = "$original" ]
[ "$(find "$PARIKT_SOURCE_CACHE_DIR/pari" -mindepth 1 -maxdepth 1 -type d | wc -l)" = 1 ]
printf 'updated\n' > "$PARIKT_SOURCE_REMOTE/Configure"
git -C "$PARIKT_SOURCE_REMOTE" add Configure
git -C "$PARIKT_SOURCE_REMOTE" -c user.name=Test -c user.email=test@example.invalid commit -qm update
updated=$(git -C "$PARIKT_SOURCE_REMOTE" rev-parse HEAD)
bash "$helper" --pari-version latest --destination "$scratch/latest" >"$scratch/latest.revision" 2>"$scratch/latest.log"
[ "$(cat "$scratch/latest.revision")" = "$updated" ]
bash "$helper" --pari-version master --destination "$scratch/master" >"$scratch/master.revision" 2>"$scratch/master.log"
[ "$(cat "$scratch/master.revision")" = "$updated" ]
[ "$(cat "$scratch/master/Configure")" = updated ]
[ "$(find "$PARIKT_SOURCE_CACHE_DIR/pari" -mindepth 1 -maxdepth 1 -type d | wc -l)" = 2 ]
echo 'PASS: master/latest refresh revisions and share snapshots by commit'
export PARIKT_SOURCE_CACHE_DIR="$scratch/seeded cache"
printf 'dirty legacy source\n' > "$PARIKT_SOURCE_REMOTE/Configure"
bash "$helper" --pari-version 99.0.0 --destination "$scratch/seeded" --seed "$PARIKT_SOURCE_REMOTE" > /dev/null 2>"$scratch/seed.log"
grep -q 'importing cached' "$scratch/seed.log"
! grep -q fetching "$scratch/seed.log"
[ "$(cat "$scratch/seeded/Configure")" = pristine ]
echo 'PASS: legacy imports use immutable tag objects, not dirty working files'
if bash "$helper" --pari-version 99.0.1 --destination "$scratch/retry" > /dev/null 2>"$scratch/failure.log"; then exit 1; fi
[ ! -e "$scratch/retry" ]
[ ! -e "$PARIKT_SOURCE_CACHE_DIR/.parikt-build.lock.owner" ]
git -C "$PARIKT_SOURCE_REMOTE" tag pari-99.0.1
bash "$helper" --pari-version 99.0.1 --destination "$scratch/retry" > /dev/null 2>"$scratch/retry.log"
[ "$(cat "$scratch/retry/Configure")" = updated ]
[ ! -d "$PARIKT_SOURCE_CACHE_DIR/.parikt-build.lock" ]
[ ! -e "$PARIKT_SOURCE_CACHE_DIR/.parikt-build.lock.owner" ]
echo 'PASS: failed acquisition publishes no workspace and releases locks for retry'
mkdir "$scratch/unmanaged"
printf 'keep\n' > "$scratch/unmanaged/keep"
if bash "$helper" --pari-version 99.0.0 --destination "$scratch/unmanaged" > /dev/null 2>&1; then exit 1; fi
[ "$(cat "$scratch/unmanaged/keep")" = keep ]
if bash "$helper" --pari-version ../../invalid --destination "$scratch/bad" > /dev/null 2>&1; then exit 1; fi
[ ! -e "$scratch/bad" ]
echo 'PASS: invalid requests and unmanaged workspaces are rejected without overwriting'
for file in "$repo"/parikt/native/*.sh; do bash -n "$file"; done
echo 'PASS: all native scripts pass Bash syntax checks'
