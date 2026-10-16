#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$workspace_root"
flutter pub get

for package in app_core app_storage app_backup source_engine source_legacy source_migration source_v8 source_tools source_conformance; do
    (
        cd "packages/$package"
        dart analyze
        dart run test:test
    )
done

for project in packages/source_platform modules/source_host apps/legado; do
    (
        cd "$project"
        flutter analyze
        flutter test
    )
done

python3 tool/app-migration-inventory.py
python3 tool/merge-app-localizations.py --check
