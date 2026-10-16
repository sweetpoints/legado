#!/usr/bin/env python3
"""Merge owned feature ARB fragments; reject untranslated or duplicate messages."""
import argparse,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]/'apps/legado/lib/l10n'
parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
messages={}
for locale in ('en','zh'):
    merged={'@@locale':locale}
    for file in sorted((root/'fragments').glob('*_'+locale+'.arb')):
        for key,value in json.loads(file.read_text()).items():
            if key.startswith('@@'):continue
            if key in merged:raise SystemExit(f'Duplicate localization key {key} in {file.name}')
            merged[key]=value
    messages[locale]=merged
    content=json.dumps(merged,ensure_ascii=False,indent=2)+'\n'
    target=root/f'app_{locale}.arb'
    if args.check:
        if not target.exists() or target.read_text()!=content:raise SystemExit(f'Stale localization output {target.name}')
    else:target.write_text(content)
keys=lambda m:{k for k in m if not k.startswith('@')}
if keys(messages['en'])!=keys(messages['zh']):raise SystemExit('English/Chinese message keys differ')
print(f'Validated {len(keys(messages["en"]))} localized messages per language')
