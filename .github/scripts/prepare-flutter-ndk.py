#!/usr/bin/env python3
"""Install Flutter's pinned NDK before Gradle, using the configured SDK CLI."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


def required_version(flutter_root: Path) -> str:
    extension = flutter_root / 'packages/flutter_tools/gradle/src/main/kotlin/FlutterExtension.kt'
    match = re.search(r'val ndkVersion: String = "([0-9]+\.[0-9]+\.[0-9]+)"', extension.read_text())
    if not match:
        raise RuntimeError('Cannot determine the pinned Flutter SDK NDK version')
    return match[1]


def installed(sdk_root: Path, version: str) -> bool:
    ndk = sdk_root / 'ndk' / version
    properties = ndk / 'source.properties'
    if not properties.is_file():
        return False
    revision = re.search(r'^Pkg.Revision\s*=\s*(\S+)\s*$', properties.read_text(), re.MULTILINE)
    binaries = ['ndk-build', 'toolchains/llvm/prebuilt/linux-x86_64/bin/clang',
                'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip']
    return bool(revision and revision[1] == version
                and all(os.access(ndk / binary, os.X_OK) for binary in binaries))


def prepare(sdk_root: Path, flutter_root: Path, sdkmanager: str, *, run=subprocess.run, sleep=time.sleep):
    version = required_version(flutter_root)
    if installed(sdk_root, version):
        print(f'Flutter NDK {version} is already installed and verified.')
        return
    # This runs before any Gradle process. Use setup-android's PATH entry rather
    # than Gradle's hard-coded cmdline-tools/latest (an older runner installation).
    for attempt in range(1, 4):
        print(f'Preparing Flutter NDK {version}, attempt {attempt}/3 with {sdkmanager}', flush=True)
        result = run([sdkmanager, f'--sdk_root={sdk_root}', f'ndk;{version}'],
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=600)
        print(result.stdout, end='', flush=True)
        if result.returncode == 0 and installed(sdk_root, version):
            return
        # Retry only the observed transient extraction failure. Configuration,
        # license, unavailable-package and incomplete-success errors stay fatal.
        if 'Error on ZipFile unknown archive' not in result.stdout or attempt == 3:
            raise RuntimeError(f'Flutter NDK {version} preparation failed (exit {result.returncode})')
        sleep(attempt * 5)


def main():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    flutter = os.environ.get('FLUTTER_ROOT')
    manager = shutil.which('sdkmanager')
    if not sdk or not flutter or not manager:
        raise RuntimeError('ANDROID_HOME, FLUTTER_ROOT and sdkmanager on PATH are required')
    prepare(Path(sdk), Path(flutter), manager)


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f'NDK preparation error: {error}', file=sys.stderr)
        sys.exit(1)
