#!/usr/bin/env python3
"""Snapshot the Android surface; this inventory is scope evidence, not parity proof."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'flutter/docs/app-migration/android-inventory.json'

def inventory():
    files = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files'], text=True).splitlines()
    ui = 'app/src/main/java/io/legado/app/ui/'
    categories = {
        'screensAndRoutes': [p for p in files if p.startswith(ui) and p.endswith(('Screen.kt', 'Route.kt'))],
        'repositories': [p for p in files if p.startswith('app/src/main/java/io/legado/app/data/repository/') and p.endswith('.kt')],
        'entities': [p for p in files if p.startswith('app/src/main/java/io/legado/app/data/entities/') and p.endswith('.kt')],
        'services': [p for p in files if p.startswith('app/src/main/java/io/legado/app/service/') and p.endswith('.kt')],
        'formatImplementations': [p for p in files if p.startswith('modules/') and p.endswith(('.java', '.kt', '.cpp', '.h'))],
    }
    return {
        'schemaVersion': 1,
        'baseline': '23e6c1c154ecff1c90121dc775f8969a8856f5a2',
        'targetPlatforms': ['android', 'ios', 'macos', 'windows', 'linux'],
        'scope': {key: sorted(value) for key, value in categories.items()},
        'uiAreas': sorted({p[len(ui):].split('/')[0] for p in files if p.startswith(ui)}),
        'note': 'Presence in this inventory is not a migrated status. Completion requires the acceptance matrix in README.md.',
    }

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='Regenerate the reviewed scope snapshot')
    args = parser.parse_args()
    expected = json.dumps(inventory(), ensure_ascii=False, indent=2) + '\n'
    if args.write:
        TARGET.write_text(expected)
    elif not TARGET.exists() or TARGET.read_text() != expected:
        raise SystemExit('Android scope changed; review and regenerate app-migration inventory')
    data = json.loads(expected)
    print(json.dumps({key: len(value) for key, value in data['scope'].items()}))
