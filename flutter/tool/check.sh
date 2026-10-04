#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$workspace_root"
flutter pub get

for package in source_engine source_legacy source_migration source_v8 source_tools source_conformance; do
    (
        cd "packages/$package"
        dart analyze
        dart run test:test
    )
done

for project in packages/source_platform modules/source_host; do
    (
        cd "$project"
        flutter analyze
        flutter test
    )
done
