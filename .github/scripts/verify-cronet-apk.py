"""Validate mandatory Flutter ABI packaging without bundling downloaded Cronet."""
import argparse
import json
from pathlib import Path
import zipfile

EXPECTED_ABIS = {'arm64-v8a', 'x86_64'}
MANDATORY_LIBRARIES = ('libapp.so', 'libflutter.so', 'libsource_v8.so')


def verify_apk(path):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        native = [name for name in names if name.startswith('lib/') and name.endswith('.so')]
        actual_abis = {name.split('/')[1] for name in native}
        if actual_abis != EXPECTED_ABIS:
            raise ValueError(f'Unexpected native ABI set: {actual_abis}')
        if any('libcronet' in name for name in names):
            raise ValueError('Cronet must be downloaded only for the device ABI')
        for library in MANDATORY_LIBRARIES:
            actual = {name.split('/')[1] for name in native if name.rsplit('/', 1)[-1] == library}
            if actual != EXPECTED_ABIS:
                raise ValueError(f'Mandatory Flutter library {library} has incomplete ABI set: {actual}')
    return Path(path).stat().st_size


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    size = verify_apk(args.apk)
    metadata = json.loads(Path('app/src/main/assets/cronet.json').read_text())
    print(f'Cronet {metadata["version"]}: no native Cronet libraries bundled in APK')
    print('Mandatory Flutter libraries: arm64-v8a, x86_64')
    print(f'APK size: {size} bytes')


if __name__ == '__main__':
    main()
