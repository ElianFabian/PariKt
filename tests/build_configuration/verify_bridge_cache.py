"""Prove a JNI source edit invalidates the engine cache without changing PARI's version.

Requires a successfully built Windows/Linux JVM consumer. Temporarily appends a C comment,
restores the exact original source, and runs real public-API checks after each rebuild.
"""
from pathlib import Path
import os
import subprocess


root = Path(__file__).resolve().parents[2]
consumer = root / "examples" / "consumer"
bridge = root / "parikt" / "native" / "paridroid" / "jni_bridge.c"
original = bridge.read_bytes()
modified = original + b"\n/* PariKt bridge cache integration check. */\n"
windows = os.name == "nt"
wrapper = [str(root / "gradlew.bat")] if windows else ["bash", str(root / "gradlew")]
host = "windows-x86_64" if windows else "linux-x86_64"
version = next(line.split("=", 1)[1].strip() for line in
               (consumer / "parikt.properties").read_text().splitlines() if line.startswith("pari.version="))
stamp = consumer / "build" / "parikt" / "native" / f"jvm-{version}" / host / ".stamp"
initial = stamp.read_bytes()
logs = root / "build" / "consumer-verification"
logs.mkdir(parents=True, exist_ok=True)


def run(name):
    log = logs / f"bridge-{name}.log"
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run([*wrapper, "-p", str(consumer), ":app:runJvm", "--console=plain"],
                                cwd=root, stdout=output, stderr=subprocess.STDOUT)
    text = log.read_text(encoding="utf-8")
    assert result.returncode == 0 and f"Consumer PARI {version}: arithmetic" in text, text[-10000:]
    return text


try:
    bridge.write_bytes(modified)
    run("changed")
    assert stamp.read_bytes() != initial, "JNI source edit did not change the cache fingerprint"
finally:
    if bridge.read_bytes() != modified:
        raise RuntimeError("JNI source changed concurrently; refusing to overwrite it")
    bridge.write_bytes(original)
run("restored")
assert stamp.read_bytes() == initial, "Restoring the source did not restore the original fingerprint"
assert "artifacts are up to date" in run("reused"), "Unchanged engine was rebuilt"
print(f"PASS: JNI source invalidation, restoration and cache reuse with PARI {version}")
