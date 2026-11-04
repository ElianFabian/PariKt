#!/usr/bin/env bash
# Existing Linux build prerequisites are reused; all Gradle/native caches start empty.
set -euo pipefail
source_dir=$1
logs=$2
version=$3
gradle_executable=${4:-$source_dir/gradlew}
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Explicit release required' >&2; exit 1; }
for tool in java javac gcc git curl tar xz make m4 sed sha256sum; do
  command -v "$tool" >/dev/null || { echo "Missing Linux prerequisite: $tool" >&2; exit 1; }
done
if [ -n "${5:-}" ]; then
  cold_root=$5
  [[ "$cold_root" == "$HOME/.cache/parikt-verification/"cold-jvm.* ]] && [ -d "$cold_root" ] || { echo 'Invalid resume root' >&2; exit 1; }
  [ "$(cat "$logs/linux-root.txt")" = "$cold_root" ] && [ -f "$logs/cold.log" ] || { echo 'Missing original cold evidence' >&2; exit 1; }
  grep -Fxq "pari.version=$version" "$cold_root/consumer/parikt.properties" || { echo 'Resume version differs' >&2; exit 1; }
  grep -Fxq "Gradle executable: $gradle_executable" "$logs/environment.log" || { echo 'Resume Gradle executable differs' >&2; exit 1; }
else
  mkdir -p "$HOME/.cache/parikt-verification"
  cold_root=$(mktemp -d "$HOME/.cache/parikt-verification/cold-jvm.XXXXXX")
  printf '%s\n' "$cold_root" > "$logs/linux-root.txt"
fi
printf 'Isolated Linux root: %s\n' "$cold_root"
# Retain this new root for diagnosis; never delete or repurpose existing user caches.
export GRADLE_USER_HOME="$cold_root/gradle-home"
export PARIKT_SOURCE_CACHE_DIR="$cold_root/pari-sources"
export PARIKT_ENGINE_CACHE_DIR="$cold_root/engines"
export PARIKT_JVM_WORK_DIR="$cold_root/native-work"
export KONAN_DATA_DIR="$cold_root/konan"
export JAVA_HOME
JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
unset PARIKT_SOURCE_REMOTE
fixture="$cold_root/consumer"
if [ -z "${5:-}" ]; then
mkdir -p "$fixture/app/src/main/kotlin/probe"
cat > "$fixture/settings.gradle.kts" <<'KOTLIN'
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "ColdLinuxJvmConsumer"
extra["pariktSourceDir"] = file(providers.gradleProperty("pariktSourceDir").get())
apply(from = File(extra["pariktSourceDir"] as File, "gradle/parikt.settings.gradle.kts"))
include(":app")
KOTLIN
cat > "$fixture/build.gradle.kts" <<'KOTLIN'
plugins {
    alias(pariktLibs.plugins.kotlin.multiplatform) apply false
    alias(pariktLibs.plugins.kotlin.jvm) apply false
}
KOTLIN
cat > "$fixture/gradle.properties" <<'PROPERTIES'
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
kotlin.native.ignoreDisabledTargets=true
kotlin.mpp.enableCInteropCommonization=true
kotlin.compiler.execution.strategy=in-process
PROPERTIES
printf 'pari.version=%s\n' "$version" > "$fixture/parikt.properties"
cat > "$fixture/app/build.gradle.kts" <<'KOTLIN'
plugins { id("org.jetbrains.kotlin.jvm"); application }
dependencies { implementation(project(":parikt")) }
application { mainClass.set("probe.MainKt") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
KOTLIN
cat > "$fixture/app/src/main/kotlin/probe/Main.kt" <<'KOTLIN'
package probe
import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.getVersionName
suspend fun main() {
    PariKt.init()
    try {
        val expected = System.getProperty("parikt.expected.version")
        check(PariKt.getVersionName() == expected)
        fun result(command: String) = PariKt.eval(command).substringAfterLast('=').trim()
        check(result("6*7") == "42")
        check(result("factor(123456)==[2,6;3,1;643,1]") == "1")
        check("factor" in PariKt.getSupportedCommands())
        check(PariKt.eval("if(1,quit())").contains("PariKt.close"))
        check(result("6*7") == "42")
        println("PASS cold consumer PARI $expected arithmetic/factorization/commands/error recovery")
    } finally { PariKt.close() }
}
KOTLIN
for directory in "$GRADLE_USER_HOME" "$PARIKT_SOURCE_CACHE_DIR" "$PARIKT_ENGINE_CACHE_DIR" "$PARIKT_JVM_WORK_DIR" "$KONAN_DATA_DIR" "$fixture/build"; do
  [ ! -e "$directory" ] || { echo "Cache was not empty: $directory" >&2; exit 1; }
done
{
  uname -a
  java -version 2>&1
  gcc --version | head -1
  printf 'Gradle: %s\nPARI sources: %s\nEngines: %s\nNative work: %s\n' "$GRADLE_USER_HOME" "$PARIKT_SOURCE_CACHE_DIR" "$PARIKT_ENGINE_CACHE_DIR" "$PARIKT_JVM_WORK_DIR"
  printf 'Gradle executable: %s\n' "$gradle_executable"
} > "$logs/environment.log"
fi
run_build() {
  bash "$gradle_executable" -p "$fixture" "-PpariktSourceDir=$source_dir" :app:run :app:verifyPariJvmGraph \
    --no-daemon --no-parallel --console=plain --configuration-cache --configuration-cache-problems=fail > "$logs/$1.log" 2>&1
}
cold_log=cold
if [ -n "${5:-}" ]; then cold_log="cold-retry-$(date +%s)-$$"; fi
if ! run_build "$cold_log"; then tail -100 "$logs/$cold_log.log"; exit 1; fi
if [ "$cold_log" = cold ]; then
  cp "$logs/cold.log" "$logs/cold-combined.log"
else
  # Acquisition may succeed during an earlier failed retry. Preserve the entire
  # experiment instead of losing that evidence when a later attempt reuses it.
  cat "$logs/cold.log" "$logs"/cold-retry-*.log > "$logs/cold-combined.log"
fi
if [ $# -lt 4 ]; then grep -Fq 'Downloading https:' "$logs/cold.log" || { echo 'No fresh wrapper download' >&2; exit 1; }; fi
for expected in '[pari-source] fetching' '[jvm-native] downloading GMP' '[jvm-native] configuring PARI' "PASS cold consumer PARI $version" 'Configuration cache entry stored.'; do
  grep -Fq "$expected" "$logs/cold-combined.log" || { echo "Missing cold evidence: $expected" >&2; exit 1; }
done
engine="$fixture/build/parikt/native/jvm-$version/linux-x86_64"
cp "$engine/.stamp" "$logs/cold.stamp"
cp "$engine/.engine-generation" "$logs/cold.generation"
echo 'PASS cold Linux JVM download/build/runtime'
if ! run_build warm; then tail -100 "$logs/warm.log"; exit 1; fi
for expected in 'Reusing configuration cache.' 'artifacts are up to date' "PASS cold consumer PARI $version"; do
  grep -Fq "$expected" "$logs/warm.log" || { echo "Missing warm evidence: $expected" >&2; exit 1; }
done
if grep -Eq 'fetching|downloading GMP|configuring PARI|building shared GMP' "$logs/warm.log"; then echo 'Warm run rebuilt/downloaded an engine' >&2; exit 1; fi
cmp "$logs/cold.stamp" "$engine/.stamp"
cmp "$logs/cold.generation" "$engine/.engine-generation"
echo 'PASS warm Linux JVM cache reuse'
