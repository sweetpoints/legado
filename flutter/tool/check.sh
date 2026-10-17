#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$workspace_root"
if (( $# > 1 )); then
    echo 'Usage: check.sh [all|--engine-only]' >&2
    exit 2
fi

case "${1:-all}" in
    all)
        packages=(app_core app_storage app_backup source_engine source_legacy source_migration source_v8 source_tools source_conformance)
        projects=(packages/source_platform modules/source_host apps/legado)
        ;;
    --engine-only)
        # Application contracts run in flutter-app.yml. Keep every engine and
        # host suite here without repeating the App or rebuilding Android on macOS.
        packages=(source_engine source_legacy source_migration source_v8 source_tools source_conformance)
        projects=(packages/source_platform modules/source_host)
        ;;
    *) echo 'Usage: check.sh [all|--engine-only]' >&2; exit 2 ;;
esac

flutter pub get

for package in "${packages[@]}"; do
    (
        cd "packages/$package"
        dart analyze
        dart run test:test
    )
done

for project in "${projects[@]}"; do
    (
        cd "$project"
        flutter analyze
        flutter test
    )
done

if [[ "${1:-all}" == all ]]; then
    python3 tool/app-migration-inventory.py
    python3 tool/merge-app-localizations.py --check
fi
