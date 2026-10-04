#!/usr/bin/env python3
"""Install an APK on an emulator and exercise its real launcher twice."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("package")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"com\.legado\.app\.(debug|release|releaseA)", args.package):
        parser.error("Unexpected application package")
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*command, check=True):
        return subprocess.run(["adb", *command], check=check, capture_output=True, timeout=90)

    def shell(*command):
        return adb("shell", *command).stdout.decode().strip()

    def nodes():
        shell("uiautomator", "dump", "/sdcard/legado-startup.xml")
        return list(ET.fromstring(shell("cat", "/sdcard/legado-startup.xml")).iter("node"))

    def tap(node):
        left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    # Clearing data is permitted only on the disposable emulator, never a physical device.
    assert shell("getprop", "ro.kernel.qemu") == "1", "An Android emulator is required"
    assert args.apk.is_file(), args.apk
    adb("install", "--no-incremental", "-r", "-t", str(args.apk))
    assert shell("pm", "clear", args.package) == "Success"
    results = []
    previous_pid = None
    for phase in ("first-launch", "process-restart"):
        folder = args.output / phase
        folder.mkdir()
        shell("am", "force-stop", args.package)
        adb("logcat", "-c", check=False)
        try:
            launch = shell("am", "start", "-W", "-a", "android.intent.action.MAIN",
                           "-c", "android.intent.category.LAUNCHER", "-n",
                           f"{args.package}/io.legado.app.ui.welcome.WelcomeActivity")
            (folder / "launch.txt").write_text(launch)
            assert "Error" not in launch and "Status: ok" in launch, launch
            deadline = time.monotonic() + 45
            stable_since = None
            while time.monotonic() < deadline:
                pid = adb("shell", "pidof", args.package, check=False).stdout.decode().strip()
                activities = shell("dumpsys", "activity", "activities")
                resumed = any("ResumedActivity" in line and args.package in line and
                              "io.legado.app.ui.main.MainActivity" in line
                              for line in activities.splitlines())
                if pid and resumed:
                    stable_since = stable_since or time.monotonic()
                    if time.monotonic() - stable_since >= 10:
                        assert pid != previous_pid, "Force-stop must create a new process"
                        previous_pid = pid
                        break
                else:
                    stable_since = None
                time.sleep(0.5)
            else:
                raise AssertionError("MainActivity did not remain resumed for 10 seconds")
            # Exercise the first-run dialogs rather than leaving initialization suspended
            # behind the privacy prompt. Text is read from the actual accessibility tree.
            dialogs_deadline = time.monotonic() + 90
            password_back_sent = False
            while time.monotonic() < dialogs_deadline:
                tree = nodes()
                if any(node.attrib.get("content-desc") in {"Me", "我的"} for node in tree):
                    break
                buttons = [node for node in tree if node.attrib.get("text", "").upper()
                           in {"AGREE", "同意", "CANCEL", "取消"}]
                password_dialog = any(node.attrib.get("text") in
                                      {"Setting the local password", "设置本地密码"}
                                      for node in tree)
                if password_dialog and not password_back_sent:
                    # Small API 26 screens can put Cancel behind the first-run keyboard.
                    # Dismiss the IME, then find the button again in the next UI snapshot.
                    shell("input", "keyevent", "KEYCODE_BACK")
                    password_back_sent = True
                elif buttons:
                    tap(buttons[0])
                elif any(node.attrib.get("text") in {"Help", "帮助"} for node in tree):
                    shell("input", "keyevent", "KEYCODE_BACK")  # Close first-run help.
                time.sleep(2)
            else:
                raise AssertionError("First-run dialogs did not reveal the main navigation")
            destinations = [
                {"Me", "我的"}, {"RSS feeds", "订阅"},
                {"Discovery", "发现"}, {"Bookshelf", "书架"},
            ]
            for index, descriptions in enumerate(destinations):
                # First-run IME dismissal and focus restoration can move the bottom bar.
                # Use current, stable bounds and require the selected accessibility state.
                deadline = time.monotonic() + 20
                previous_bounds = None
                while time.monotonic() < deadline:
                    tree = nodes()
                    if any(node.attrib.get("selected") == "true" and
                           any(child.attrib.get("content-desc") in descriptions
                               for child in node.iter("node")) for node in tree):
                        break
                    candidates = [node for node in tree
                                  if node.attrib.get("content-desc") in descriptions]
                    assert len(candidates) == 1, f"Navigation tab missing or blocked: {descriptions}"
                    bounds = candidates[0].attrib["bounds"]
                    if bounds == previous_bounds:
                        tap(candidates[0])
                    previous_bounds = bounds
                    time.sleep(0.5)
                else:
                    raise AssertionError(f"Navigation tab did not become selected: {descriptions}")
                (folder / f"tab-{index}.png").write_bytes(adb("exec-out", "screencap", "-p").stdout)
            time.sleep(5)
            assert shell("pidof", args.package) == pid, "App process changed during navigation"
            results.append({"phase": phase, "pid": pid, "mainActivityStableSeconds": 10,
                            "navigationTabs": 4})
        finally:
            snapshot = adb("shell", "cat", "/sdcard/legado-startup.xml", check=False)
            (folder / "ui.xml").write_bytes(snapshot.stdout)
            log = adb("logcat", "-d").stdout.decode(errors="replace")
            (folder / "logcat.txt").write_text(log)
            (folder / "activities.txt").write_text(shell("dumpsys", "activity", "activities"))
            (folder / "screen.png").write_bytes(adb("exec-out", "screencap", "-p").stdout)
            adb("pull", f"/sdcard/Android/data/{args.package}/cache/crash",
                str(folder / "crash"), check=False)
        for block in log.split("FATAL EXCEPTION:")[1:]:
            assert f"Process: {args.package}, PID:" not in block, "App crashed; inspect logcat.txt"
        assert not list((folder / "crash").glob("*.log")), "App recorded a crash"
    (args.output / "result.json").write_text(json.dumps({
        "package": args.package, "apk": args.apk.name,
        "api": shell("getprop", "ro.build.version.sdk"), "serial": os.getenv("ANDROID_SERIAL"),
        "status": "passed", "launches": results,
    }, indent=2))
    print(f"APK_STARTUP_PASSED: {args.package}; first launch and process restart")


if __name__ == "__main__":
    main()
