#!/usr/bin/env python3
"""Run the actual R8 release source engine twice in an isolated Android application."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

PACKAGE = "com.legado.app.sourceenginesmoke"
RUNNER = "io.legado.app.model.sourceEngine.SourceEngineRuntimeInstrumentation"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, default=Path("app/build/source-engine-runtime"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    adb = shutil.which("adb") or str(sdk / "platform-tools/adb")
    analyzer = shutil.which("apkanalyzer") or str(sdk / "cmdline-tools/latest/bin/apkanalyzer")

    def run(command, timeout=180):
        result = subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout)
        return result.stdout

    def device(*command, timeout=180):
        return run([adb, "-s", args.serial, *command], timeout)

    assert args.apk.is_file(), args.apk
    package = run([analyzer, "manifest", "application-id", str(args.apk)]).strip()
    debuggable = run([analyzer, "manifest", "debuggable", str(args.apk)]).strip()
    assert package == PACKAGE, f"Refusing to install or clear non-fixture package: {package}"
    assert debuggable == "false", f"Requires a genuinely non-debuggable release APK: {debuggable}"
    manifest = run([analyzer, "manifest", "print", str(args.apk)])
    assert RUNNER in manifest, "Release runtime instrumentation is absent"
    report = {"apkSha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "package": package, "debuggable": False, "serial": args.serial}
    print(json.dumps(report))
    device("install", "-r", "-t", str(args.apk))
    assert "Success" in device("shell", "pm", "clear", PACKAGE)
    token = str(uuid.uuid4())
    for phase in ("cold", "restart"):
        if phase == "restart":
            device("shell", "am", "force-stop", PACKAGE)
        output = device("shell", "am", "instrument", "-w", "-r", "-e", "phase", phase,
                        "-e", "token", token, f"{PACKAGE}/{RUNNER}")
        (args.output / f"{phase}-instrumentation.txt").write_text(output)
        print(output)
        assert f"SOURCE_ENGINE_RELEASE_RUNTIME_PASSED phase={phase}" in output, output
        assert "INSTRUMENTATION_CODE: -1" in output, output
        destination = args.output / f"{phase}.json"
        device("pull", f"/sdcard/Android/data/{PACKAGE}/files/source-engine-runtime/{phase}.json",
               str(destination))
        proof = json.loads(destination.read_text())
        assert proof["phase"] == phase and proof["debuggable"] is False
        assert proof["stableOriginVerified"] is True
        if phase == "restart":
            assert proof["fixtureOrigin"] == report["cold"]["fixtureOrigin"]
            assert proof["fixturePort"] == report["cold"]["fixturePort"]
        for key in ("sessionReadWrite", "closeReopenRestore", "sessionCookieRestored",
                    "legacySearchInfoTocContent", "trailingNewlineExplore"):
            assert proof[key] is True, (phase, key)
        if phase == "restart":
            assert "/session/set" not in proof["requests"], "Restart must read, not reseed, storage"
        report[phase] = proof
    (args.output / "proof.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Actual shrunk release Flutter/V8 storage, restart and old-source pipeline passed.")


if __name__ == "__main__":
    main()
