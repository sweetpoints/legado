#!/usr/bin/env bash
set -euo pipefail
workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$workspace_root/tool/merge-app-localizations.py"
cd "$workspace_root/apps/legado"
flutter gen-l10n
dart run build_runner build
dart format lib test integration_test
# Freezed 4.0.1 emits whitespace-only lines. Normalize generated output, not logic.
python3 - <<'PY'
from pathlib import Path
for path in Path('lib').rglob('*.freezed.dart'):
    path.write_text(''.join(line.rstrip() + '\n' for line in path.read_text().splitlines()))
PY
