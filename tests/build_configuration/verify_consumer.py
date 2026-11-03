"""Build/run the independent consumer with two PARI versions, then restore its configuration.

Requires the same Windows/WSL or Linux prerequisites as the example. This is an integration
check: the first run can download tools and compile engines. Run from the repository root.
"""
from pathlib import Path
import os
import subprocess


root = Path(__file__).resolve().parents[2]
consumer = root / "examples" / "consumer"
properties = consumer / "parikt.properties"
original = properties.read_bytes()
logs = root / "build" / "consumer-verification"
logs.mkdir(parents=True, exist_ok=True)
windows = os.name == "nt"
wrapper = [str(root / "gradlew.bat")] if windows else ["bash", str(root / "gradlew")]
tasks = [
    ":app:runJvm",
    ":app:runDebugExecutableMingwX64" if windows else ":app:runDebugExecutableLinuxX64",
    ":app:linkDebugExecutableLinuxX64",
    ":app:jsNodeDevelopmentRun",
    ":app:wasmJsNodeDevelopmentRun",
    ":pariktAndroidNative:assembleDebug",
]
try:
    for version in ("2.15.0", "2.19.0"):
        properties.write_text(f"pari.version={version}\n", encoding="utf-8")
        marker = f"Consumer PARI {version}: arithmetic and factorization passed"
        log = logs / f"{version}.log"
        print(f"Building consumer PARI {version}; output: {log}", flush=True)
        with log.open("w", encoding="utf-8") as output:
            result = subprocess.run(
                [*wrapper, "-p", str(consumer), *tasks, "--console=plain"],
                cwd=root, stdout=output, stderr=subprocess.STDOUT,
            )
        text = log.read_text(encoding="utf-8")
        assert result.returncode == 0, text[-10000:]
        assert text.count(marker) == 4, text[-10000:]
        if windows:
            # KGP disables the Linux run task on Windows; run its linked executable via WSL.
            executable = "./examples/consumer/app/build/bin/linuxX64/debugExecutable/app.kexe"
            result = subprocess.run(["wsl", executable], cwd=root, text=True, capture_output=True)
            assert result.returncode == 0 and marker in result.stdout, result.stdout + result.stderr
            (logs / f"{version}-linux.log").write_text(result.stdout, encoding="utf-8")
        print(f"PASS: consumer PARI {version}", flush=True)
finally:
    properties.write_bytes(original)
