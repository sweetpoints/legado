#!/usr/bin/env python3
"""Link only Legado's Linux C bridge against authenticated released pure V8 SDKs.

Uses the SDK's Chromium libc++ headers and runtime, a SHA-pinned Chromium
Clang/LLD package, and the matching SHA-pinned Debian bullseye sysroot. Never
builds V8, registers a Flutter hook, signs or publishes an artifact.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import sys
import subprocess
import tarfile
import tempfile
import urllib.request

import prebuilt
import toolchains
import link_sdk

HERE = Path(__file__).resolve().parent
PACKAGE = link_sdk.PACKAGE
ALLOCATOR_WRAPS = ('strdup', 'strndup', 'realpath', 'getcwd', 'vasprintf', 'backtrace_symbols')
PROFILES = {'linux-x64': ('x86_64-linux-gnu', 62, 'amd64'),
            'linux-arm64': ('aarch64-linux-gnu', 183, 'arm64')}


def run(command):
    return subprocess.run([str(x) for x in command], check=True, text=True,
                          capture_output=True).stdout.strip()


def tree_inventory(root):
    """Fingerprint regular files and link text without following links."""
    files = {}
    for path in sorted(root.rglob('*')):
        if path == root / '.sysroot-receipt.json':
            continue
        name = path.relative_to(root).as_posix()
        if path.is_symlink():
            destination = path.resolve()
            if not destination.is_relative_to(root.resolve()):
                raise ValueError('Sysroot symlink escapes root')
            files[name] = {'link': os.readlink(path)}
        elif path.is_file():
            files[name] = {'sha256': prebuilt.sha(path), 'size': path.stat().st_size}
        elif not path.is_dir():
            raise ValueError('Sysroot contains special file')
    return files


def inventory_digest(files):
    return hashlib.sha256(json.dumps(files, sort_keys=True, separators=(',', ':')).encode()).hexdigest()



def sysroot_relative(name):
    # These Unix-only archives legitimately contain a literal backslash in a
    # systemd unit name. Windows SDK filename restrictions do not apply here.
    if (not name or name.startswith('/') or '\x00' in name or
            any(part in ('', '.', '..') for part in name.split('/'))):
        raise ValueError('Unsafe sysroot archive path')
    return name


def archive_inventory(archive):
    """Compute the exact content identity without host filesystem case folding."""
    import posixpath
    files = {}
    with tarfile.open(archive, 'r:xz') as source:
        members = source.getmembers()
        if len(members) > 50000 or sum(member.size for member in members) > 1024**3:
            raise ValueError('Sysroot archive unpacked size exceeds limit')
        for member in members:
            name = member.name.removeprefix('./').rstrip('/')
            if member.isdir() and name in ('', '.'):
                continue
            name = sysroot_relative(name)
            if member.isdir():
                continue
            if name in files:
                raise ValueError('Duplicate sysroot archive file')
            if member.issym():
                resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), member.linkname))
                if member.linkname.startswith('/') or resolved == '..' or resolved.startswith('../'):
                    raise ValueError('Sysroot archive symlink escapes root')
                files[name] = {'link': member.linkname}
            elif member.isfile() or member.islnk():
                if member.islnk():
                    sysroot_relative(member.linkname.removeprefix('./'))
                digest, size = hashlib.sha256(), 0
                with source.extractfile(member) as stream:
                    while data := stream.read(1024 * 1024):
                        digest.update(data)
                        size += len(data)
                        if size > 1024**3:
                            raise ValueError('Sysroot archive file exceeds size limit')
                files[name] = {'sha256': digest.hexdigest(), 'size': size}
            else:
                raise ValueError('Sysroot archive contains a special file')
    return files


def case_sensitive_filesystem(root):
    with tempfile.TemporaryDirectory(prefix='.case-probe-', dir=root) as temporary:
        lower = Path(temporary) / 'case-sensitive-check'
        lower.write_bytes(b'lower')
        return not (Path(temporary) / 'CASE-SENSITIVE-CHECK').exists()


def install_sysroot(target, cache, pins):
    if target not in PROFILES:
        raise ValueError('Unsupported Linux target')
    pin = pins['sysroots'][target]
    digest = prebuilt._digest(pin['sha256'])
    if pin['url'] != 'https://commondatastorage.googleapis.com/chrome-linux-sysroot/' + digest:
        raise ValueError('Sysroot URL must name its fixed official content hash')
    prebuilt._positive_size(pin['size'])
    prebuilt._digest(pin['filesSha256'])
    cache = Path(cache)
    if cache.is_symlink():
        raise ValueError('Sysroot cache must not be a symlink')
    cache.mkdir(parents=True, exist_ok=True)
    if not case_sensitive_filesystem(cache):
        raise ValueError('Linux sysroot cache requires a case-sensitive filesystem; use native Linux or case-sensitive APFS')
    destination = cache / (target + '-' + digest)

    def verify(root):
        if root.is_symlink() or (root / '.sysroot-receipt.json').is_symlink():
            raise ValueError('Sysroot root/receipt must not be symlinks')
        receipt = json.loads((root / '.sysroot-receipt.json').read_text())
        if receipt['pin'] != pin or inventory_digest(tree_inventory(root)) != pin['filesSha256']:
            raise ValueError('Sysroot cache differs from pinned authenticated archive')
        return root

    if destination.exists():
        return verify(destination)
    lock = cache / ('.' + target + '-' + digest + '.lock')
    try:
        lock.mkdir()
    except FileExistsError as error:
        raise ValueError('Sysroot installation already locked') from error
    try:
        with tempfile.TemporaryDirectory(prefix='.sysroot-', dir=cache) as directory:
            work = Path(directory)
            archive = work / 'sysroot.tar.xz'
            with urllib.request.urlopen(pin['url'], timeout=60) as response, archive.open('wb') as output:
                if response.geturl() != pin['url']:
                    raise ValueError('Unexpected sysroot download redirect')
                size = 0
                while data := response.read(1024 * 1024):
                    size += len(data)
                    if size > pin['size']:
                        raise ValueError('Sysroot archive exceeds fixed size')
                    output.write(data)
            if size != pin['size'] or prebuilt.sha(archive) != digest:
                raise ValueError('Sysroot archive SHA256/size mismatch')
            if inventory_digest(archive_inventory(archive)) != pin['filesSha256']:
                raise ValueError('Sysroot archive content inventory differs from reviewed pin')
            incoming = work / 'incoming'
            incoming.mkdir()
            with tarfile.open(archive, 'r:xz') as source:
                members = source.getmembers()
                if len(members) > 50000 or sum(m.size for m in members) > 1024**3:
                    raise ValueError('Sysroot archive unpacked size exceeds limit')
                # Official sysroots intentionally contain relative symlinks. The
                # data filter confines their resolved paths to this fresh root.
                source.extractall(incoming, filter='data')
            (incoming / '.sysroot-receipt.json').write_text(json.dumps({'pin': pin}, sort_keys=True))
            verify(incoming)
            os.replace(incoming, destination)
        return destination
    finally:
        lock.rmdir()


def contract(root, target, pins):
    if target not in PROFILES:
        raise ValueError('Unsupported Linux target')
    root = Path(root)
    manifest = json.loads((root / 'manifest.json').read_text())
    prebuilt.validate_sdk(root, manifest, pins, pins, target)
    linking = json.loads((root / target / 'linking.json').read_text())
    triple, _, architecture = PROFILES[target]
    expected = {
        'schemaVersion': 1, 'compilerStyle': 'clang++',
        'includeDirs': ['include', 'include/c++/config', 'include/c++/v1', 'include/c++abi'],
        'compileOptions': ['-std=c++20', '-nostdinc++', '-fPIC', '-fno-rtti', '-fno-exceptions',
                           '--target=' + triple, '-fcomplete-member-pointers', '-pthread'],
        'libraries': ['lib/libv8_monolith.a', 'lib/libv8_rust_runtime.a',
                      'lib/libv8_cxx_runtime.a', 'lib/libclang_rt.builtins.a'],
        'linkOptions': ['-nostdlib++', '--target=' + triple, '-fuse-ld=lld'],
        'systemLibraries': ['dl', 'rt', 'pthread', 'm'],
        'cxxRuntime': 'pinned Chromium libc++ (__Cr ABI)', 'crt': 'system glibc',
        'staticLibraryGrouping': 'rescan',
        'sysrootRequirement': {'kind': 'chromium-linux-sysroot', 'architecture': architecture,
            'distribution': 'debian-bullseye', 'sourceRevision': pins['v8']['revision']},
        'featureProfile': {'internationalization': True, 'temporal': True, 'icuData': 'embedded',
            'jit': 'upstream-default', 'webAssembly': 'upstream-default', 'experimentalRuntimeFlags': []}}
    if any(type(linking.get(k)) is not type(v) or linking.get(k) != v for k, v in expected.items()):
        raise ValueError('Linux SDK linking/feature contract differs')
    indexed = {item['path'] for item in manifest['targets'][target]['files']}
    for define in linking['defines']:
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:=[A-Za-z0-9_]+)?', define):
            raise ValueError('Invalid Linux SDK define')
    for relative in linking['libraries']:
        library = root / target / relative
        with library.open('rb') as archive:
            magic = archive.read(8)
        if target + '/' + relative not in indexed or magic != b'!<arch>\n':
            raise ValueError('Linux library must be an indexed self-contained archive')
    for relative in linking['includeDirs']:
        if not any(name.startswith(target + '/' + relative + '/') for name in indexed):
            raise ValueError('Linux include directory is not indexed')
    return manifest, linking


def command(root, target, linking, compiler_root, sysroot, output):
    platform_root = Path(root) / target
    args = [compiler_root / 'bin/clang++', *linking['compileOptions'], '--sysroot=' + str(sysroot),
            '--gcc-toolchain=' + str(Path(sysroot) / 'usr'),
            '-Oz', '-ffunction-sections', '-fdata-sections', '-fvisibility=hidden',
            '-fvisibility-inlines-hidden']
    args += ['-I' + str(platform_root / relative) for relative in linking['includeDirs']]
    args += ['-D' + define for define in linking['defines']]
    args += [PACKAGE / 'src/source_v8.cpp', HERE / 'linux_allocator_boundary.cpp', '-Wl,--start-group']
    args += [platform_root / relative for relative in linking['libraries']]
    args += ['-Wl,--end-group', *linking['linkOptions'], '-nodefaultlibs', '-lc',
             '--ld-path=' + str(compiler_root / 'bin/ld.lld'), '-shared', '-Wl,--no-undefined',
             '-Wl,--exclude-libs,ALL', '-Wl,--gc-sections', '-Wl,-O2', '-Wl,--icf=all',
             '-Wl,--version-script=' + str(PACKAGE / 'src/android_exports.map'),
             '-Wl,-soname,libsource_v8.so']
    # Chromium's Linux Clang driver normally adds the target GCC unwind runtime.
    # Use the authenticated sysroot's exact library, never host GCC discovery.
    triple = PROFILES[target][0]
    unwind = Path(sysroot) / 'lib' / triple / 'libgcc_s.so.1'
    if not unwind.is_file() or not unwind.resolve().is_relative_to(Path(sysroot).resolve()):
        raise ValueError('Pinned sysroot unwind runtime missing')
    args += [unwind]
    args += ['-Wl,--wrap=' + name for name in ALLOCATOR_WRAPS]
    args += ['-l' + library for library in linking['systemLibraries']]
    return args + ['-o', output]



def audit_imports(symbols, target, pins):
    names = sorted({parts[7] for line in symbols.splitlines() if len(parts := line.split()) >= 8
                    and parts[0].rstrip(':').isdigit() and parts[6] == 'UND'})
    digest = hashlib.sha256(('\n'.join(names) + '\n').encode()).hexdigest()
    if digest != pins['undefinedSymbolsSha256'][target]:
        raise ValueError('Linux SDK imports changed; allocator ownership audit required')
    version = '2.17' if target == 'linux-arm64' else '2.2.5'
    if '__libc_free@GLIBC_' + version not in names:
        raise ValueError('Allocator boundary must use versioned system glibc free')
    return digest


def inspect_elf(data, target):
    if target not in PROFILES:
        raise ValueError('Unsupported Linux target')
    if (len(data) < 64 or data[:6] != b'\x7fELF\x02\x01' or
            struct.unpack_from('<HH', data, 16) != (3, PROFILES[target][1])):
        raise ValueError('Expected target Linux ELF64 shared library')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    if size < 56 or not count or offset + size * count > len(data):
        raise ValueError('Invalid ELF program headers')
    if not any(struct.unpack_from('<I', data, offset + i * size)[0] == 1 for i in range(count)):
        raise ValueError('ELF shared library has no LOAD segments')
    return {'format': 'elf64', 'machine': PROFILES[target][1]}


def build(output, targets, *, sdk_cache=None, compiler_cache=None, sysroot_cache=None):
    output = Path(output).resolve()
    if output.exists():
        raise ValueError('Linux output must be a new directory')
    if not targets or len(set(targets)) != len(targets) or set(targets) - PROFILES.keys():
        raise ValueError('Explicit unique Linux targets required')
    pins = json.loads((HERE / 'linux-pins.json').read_text())
    release = json.loads((HERE / 'linux-release-pin.json').read_text())
    compiler = toolchains.install(compiler_cache)
    identity = link_sdk.compiler_identity(run([compiler / 'bin/clang++', '--version']))
    source_digest = link_sdk.source_digest()
    inputs = consumer_inputs()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.linux-bridge-', dir=output.parent) as temporary:
        work = Path(temporary)
        evidence = {}
        for target in targets:
            root = prebuilt.install(release, target, local_pins=pins, cache_root=sdk_cache)
            manifest, linking = contract(root, target, pins)
            if identity != link_sdk.compiler_identity(manifest['targets'][target]['toolchain']['clang']):
                raise ValueError('Linux bridge compiler differs from released SDK Clang')
            sysroot = install_sysroot(target, sysroot_cache or PACKAGE / '.cache/linux-sysroots', pins)
            folder = work / target
            folder.mkdir()
            binary = folder / 'libsource_v8.so'
            args = command(root, target, linking, compiler, sysroot, binary)
            run(args)
            # A separate fixture exercises ownership, caller-supplied buffers,
            # errno, counted embedded-NUL output and 2,000 allocation cycles.
            probe = folder / 'allocator-boundary-test.so'
            export_map = folder / 'allocator-test.map'
            export_map.write_text('{ global: sv8_linux_allocator_boundary_test; local: *; };\n')
            probe_args = [item for item in args if item != PACKAGE / 'src/source_v8.cpp']
            probe_args = [('-Wl,--version-script=' + str(export_map)) if str(item).startswith('-Wl,--version-script=')
                          else item for item in probe_args]
            probe_args = [probe if item == binary else item for item in probe_args]
            probe_args.insert(1, '-DSV8_LINUX_ALLOCATOR_BOUNDARY_TEST=1')
            run(probe_args)
            inspection = inspect_elf(binary.read_bytes(), target)
            exported = run([compiler / 'bin/llvm-readelf', '--dyn-syms', '--wide', binary])
            imports_digest = audit_imports(exported, target, pins)
            defined = {parts[7] for line in exported.splitlines() if len(parts := line.split()) >= 8
                       and parts[0].rstrip(':').isdigit() and parts[6] != 'UND'
                       and parts[4] in ('GLOBAL', 'WEAK')}
            if defined != link_sdk.EXPORTS:
                raise ValueError('Linux bridge exports differ from the ten sv8 ABI symbols')
            dynamic = run([compiler / 'bin/llvm-readelf', '--dynamic', '--version-info', binary])
            dependencies = re.findall(r'Shared library: \[([^\]]+)\]', dynamic)
            allowed = {'libc.so.6', 'libm.so.6', 'libdl.so.2', 'libpthread.so.0', 'librt.so.1',
                       'ld-linux-x86-64.so.2', 'ld-linux-aarch64.so.1', 'libgcc_s.so.1'}
            if not dependencies or set(dependencies) - allowed:
                raise ValueError('Linux bridge has an unexpected dynamic dependency')
            glibc = {tuple(map(int, item.split('.'))) for item in re.findall(r'GLIBC_(\d+\.\d+)', dynamic)}
            if not glibc or max(glibc) > (2, 31):
                raise ValueError('Linux bridge exceeds pinned glibc 2.31 baseline')
            contract(root, target, pins)
            install_sysroot(target, sysroot.parent, pins)
            shutil.copytree(root / 'licenses', work / 'licenses' / target)
            evidence[target] = {'librarySha256': prebuilt.sha(binary), 'sdkManifestSha256': prebuilt.sha(root / 'manifest.json'),
                'sysrootSha256': pins['sysroots'][target]['sha256'], 'inspection': inspection,
                'allocatorProbeSha256': prebuilt.sha(probe), 'undefinedSymbolsSha256': imports_digest,
                'command': [str(x) for x in args], 'dependencies': dependencies,
                'maxGlibcRequired': '.'.join(map(str, max(glibc))),
                'runtimeTested': False, 'sourceCompatibilityTested': False}
        if source_digest != link_sdk.source_digest() or any(prebuilt.sha(HERE / name) != value for name, value in inputs.items()):
            raise ValueError('Linux bridge sources/pins changed while building')
        (work / 'provenance.json').write_text(json.dumps({'schemaVersion': 1,
            'bridgeSourceSha256': source_digest, 'inputsSha256': inputs,
            'compilerHost': toolchains.host_key(),
            'compilerPackage': json.loads((HERE / 'toolchain-pins.json').read_text())['hosts'][toolchains.host_key()],
            'releaseManifestSha256': release['releaseManifestSha256'],
            'v8BuiltLocally': False, 'flutterHookEnabled': False, 'targets': evidence}, indent=2) + '\n')
        shutil.move(str(work), output)
    return output




def consumer_inputs():
    return {name: prebuilt.sha(HERE / name) for name in
            ('linux_sdk.py', 'linux_allocator_boundary.cpp', 'linux-pins.json',
             'linux-release-pin.json', 'prebuilt.py', 'toolchains.py')}


def prepare_dynamic(target, *, cache_root=None, sdk_cache=None, compiler_cache=None, sysroot_cache=None):
    """Prepare and reauthenticate a real native-asset package for one Linux target."""
    if target not in PROFILES:
        raise ValueError('Unsupported Linux dynamic target')
    cache = Path(cache_root or os.environ.get('SOURCE_V8_LINUX_ARTIFACT_CACHE') or PACKAGE / '.cache/linux-consumer')
    sdk_cache = sdk_cache or os.environ.get('SOURCE_V8_SDK_CACHE')
    compiler_cache = compiler_cache or os.environ.get('SOURCE_V8_COMPILER_CACHE')
    sysroot_cache = sysroot_cache or os.environ.get('SOURCE_V8_SYSROOT_CACHE') or PACKAGE / '.cache/linux-sysroots'
    if cache.is_symlink():
        raise ValueError('Linux consumer cache must not be a symlink')
    cache.mkdir(parents=True, exist_ok=True)
    with link_sdk._publish_lock(cache / '.prepare.lock'):
        pins = json.loads((HERE / 'linux-pins.json').read_text())
        release = json.loads((HERE / 'linux-release-pin.json').read_text())
        source = link_sdk.source_digest()
        inputs = consumer_inputs()
        identity = hashlib.sha256(json.dumps({'source': source, 'inputs': inputs, 'compilerHost': toolchains.host_key()}, sort_keys=True).encode()).hexdigest()
        package = cache / (target + '-' + identity)
        if package.is_symlink():
            raise ValueError('Linux consumer package must not be a symlink')
        if not package.exists():
            build(package, [target], sdk_cache=sdk_cache, compiler_cache=compiler_cache, sysroot_cache=sysroot_cache)
        receipt = json.loads((package / 'provenance.json').read_text())
        if receipt['bridgeSourceSha256'] != source or receipt['inputsSha256'] != inputs:
            raise ValueError('Linux consumer cache source/input provenance differs')
        # Cache reuse is never an excuse to skip SDK, toolchain, sysroot or
        # final-binary ABI/ownership validation before Flutter bundles bytes.
        sdk = prebuilt.install(release, target, local_pins=pins, cache_root=sdk_cache)
        contract(sdk, target, pins)
        compiler = toolchains.install(compiler_cache)
        compiler_pin = json.loads((HERE / 'toolchain-pins.json').read_text())['hosts'][toolchains.host_key()]
        if receipt['compilerHost'] != toolchains.host_key() or receipt['compilerPackage'] != compiler_pin:
            raise ValueError('Linux compiler package provenance differs')
        sysroot = install_sysroot(target, sysroot_cache, pins)
        original = receipt['targets'][target]
        binary = package / target / 'libsource_v8.so'
        if binary.is_symlink() or prebuilt.sha(binary) != original['librarySha256']:
            raise ValueError('Linux consumer cache binary differs')
        inspection = inspect_elf(binary.read_bytes(), target)
        symbols = run([compiler / 'bin/llvm-readelf', '--dyn-syms', '--wide', binary])
        imports = audit_imports(symbols, target, pins)
        defined = {parts[7] for line in symbols.splitlines() if len(parts := line.split()) >= 8
                   and parts[0].rstrip(':').isdigit() and parts[6] != 'UND' and parts[4] in ('GLOBAL', 'WEAK')}
        if defined != link_sdk.EXPORTS:
            raise ValueError('Linux native-asset exports differ')
        if (original['sdkManifestSha256'] != release['sdkManifestSha256'][target]
                or original['sysrootSha256'] != pins['sysroots'][target]['sha256']
                or original['undefinedSymbolsSha256'] != imports):
            raise ValueError('Linux native-asset SDK/sysroot/import provenance differs')
        manifest = {'schemaVersion': 1, 'v8': pins['v8'], 'depotTools': pins['depotTools'],
            'bridge': {'abi': 1, 'sourceSha256': source}, 'targets': {target: {
                'binary': target + '/libsource_v8.so', 'size': binary.stat().st_size,
                'sha256': prebuilt.sha(binary), 'minGlibc': pins['targets'][target]['minGlibc'],
                'linuxConsumerInputsSha256': inputs, 'linuxCompilerHost': toolchains.host_key(),
                'linuxCompilerPackage': compiler_pin,
                'linuxSysrootSha256': pins['sysroots'][target]['sha256'],
                'linuxSysrootFilesSha256': pins['sysroots'][target]['filesSha256'],
                'linuxUndefinedSymbolsSha256': imports,
                'sdkProvenance': {'target': target, 'manifestSha256': original['sdkManifestSha256'],
                                  'releaseManifestSha256': release['releaseManifestSha256']},
                'binaryInspection': inspection,
                'validation': {'built': True, 'runtimeTested': False, 'sourceCompatibilityTested': False}}}}
        if source != link_sdk.source_digest() or inputs != consumer_inputs():
            raise ValueError('Linux native-asset inputs changed while verifying')
        # The immutable, content-identified package is published as one unit by
        # build(); only its complete manifest is atomically replaced afterwards.
        with tempfile.NamedTemporaryFile(mode='w', dir=package, delete=False) as stream:
            json.dump(manifest, stream, indent=2)
            incoming = Path(stream.name)
        incoming.replace(package / 'manifest.json')
        return package


def _native_probe(package, target):
    """Explicit same-architecture Linux runtime probe; no emulation claim."""
    import ctypes
    import threading
    import time
    host = {'x86_64': 'linux-x64', 'amd64': 'linux-x64',
            'aarch64': 'linux-arm64', 'arm64': 'linux-arm64'}.get(platform.machine().lower())
    if platform.system() != 'Linux' or target != host:
        raise ValueError('Native runtime requires matching Linux host architecture')
    package = Path(package).resolve()
    provenance = json.loads((package / 'provenance.json').read_text())
    binary = package / target / 'libsource_v8.so'
    if prebuilt.sha(binary) != provenance['targets'][target]['librarySha256']:
        raise ValueError('Runtime library differs from package evidence')
    inspect_elf(binary.read_bytes(), target)
    probe = package / target / 'allocator-boundary-test.so'
    if prebuilt.sha(probe) != provenance['targets'][target]['allocatorProbeSha256']:
        raise ValueError('Allocator boundary probe differs from package evidence')
    allocator = ctypes.CDLL(str(probe))
    allocator.sv8_linux_allocator_boundary_test.argtypes = []
    allocator.sv8_linux_allocator_boundary_test.restype = ctypes.c_int
    allocation_result = allocator.sv8_linux_allocator_boundary_test()
    if allocation_result != 0:
        raise ValueError('Allocator boundary semantics/stress failed: ' + str(allocation_result))
    library = ctypes.CDLL(str(binary))
    signatures = {
        'sv8_create': ([ctypes.c_int, ctypes.c_int], ctypes.c_void_p),
        'sv8_start': ([ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p], None),
        'sv8_poll': ([ctypes.c_void_p], ctypes.c_void_p),
        'sv8_resolve': ([ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int], None),
        'sv8_sync_poll': ([ctypes.c_void_p], ctypes.c_void_p),
        'sv8_sync_reply': ([ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int], None),
        'sv8_cancel': ([ctypes.c_void_p], None), 'sv8_destroy': ([ctypes.c_void_p], None),
        'sv8_free': ([ctypes.c_void_p], None), 'sv8_version': ([], ctypes.c_char_p)}
    for name, (arguments, result) in signatures.items():
        function = getattr(library, name)
        function.argtypes, function.restype = arguments, result
    runtime = library.sv8_create(2000, 32)
    if not runtime:
        raise ValueError('Linux V8 runtime allocation failed')
    checks = ['allocator-ownership-2000-cycles']
    def start(script):
        library.sv8_start(runtime, script.encode(), b'{}', b'')
    def poll(function=library.sv8_poll):
        value = function(runtime)
        if not value:
            return None
        try:
            return json.loads(ctypes.string_at(value))
        finally:
            library.sv8_free(value)
    def expect(value):
        actual = poll()
        if actual != {'status': 'done', 'value': value}:
            raise ValueError('Linux runtime result differs: ' + repr(actual))
    try:
        start('JSON.stringify({answer:6*7,wasm:typeof WebAssembly,intl:typeof Intl,temporal:typeof Temporal})')
        result = poll()
        if not result or result.get('status') != 'done' or json.loads(result.get('value', '{}')) != {
                'answer': 42, 'wasm': 'object', 'intl': 'object', 'temporal': 'object'}:
            raise ValueError('Linux V8 feature profile differs: ' + repr(result))
        checks += ['javascript', 'wasm', 'intl', 'temporal']
        start('(globalThis.savedValue = 41, savedValue + 1)')
        expect(42)
        start('savedValue + 2')
        expect(43)
        checks.append('retained-session')
        start("__sourceHost('echo', []).then(value => value + 1)")
        response = poll()
        request = response.get('requests', [{}])[0] if response else None
        if not request or type(request.get('id')) is not int:
            raise ValueError('Async host request missing')
        library.sv8_resolve(runtime, request['id'], b'41', 0)
        expect(42)
        checks.append('async-host')
        worker = threading.Thread(target=start, args=("__sourceHostSync('echo', []) + 1",))
        worker.start()
        request = None
        for _ in range(100):
            response = poll(library.sv8_sync_poll)
            requests = response if isinstance(response, list) else []
            request = requests[0] if requests else None
            if request:
                break
            time.sleep(0.01)
        if not request or type(request.get('id')) is not int:
            library.sv8_cancel(runtime)
            worker.join(5)
            raise ValueError('Sync host request missing')
        library.sv8_sync_reply(runtime, request['id'], b'41', 0)
        worker.join(5)
        if worker.is_alive():
            raise ValueError('Sync host request did not finish')
        expect(42)
        checks.append('sync-host')
        worker = threading.Thread(target=start, args=('while (true) {}',))
        worker.start()
        time.sleep(0.05)
        library.sv8_cancel(runtime)
        worker.join(5)
        if worker.is_alive() or 'execution_timeout' not in json.dumps(poll()):
            raise ValueError('Linux runtime cancellation failed')
        start('savedValue + 3')
        expect(44)
        checks += ['cancellation', 'recovery-after-cancellation']
        for iteration in range(100):
            start("JSON.stringify({n:Array.from({length:2000},(_,i)=>({i,value:'value'+i})).length," +
                  "b:new Uint8Array(65536).length,t:new Temporal.PlainDate(2026,10,17).toString()," +
                  "i:new Intl.NumberFormat('zh-CN').format(1234567)," +
                  "w:WebAssembly.validate(new Uint8Array([0,97,115,109,1,0,0,0]))})")
            result = poll()
            value = json.loads(result.get('value', '{}')) if result else {}
            if not result or result.get('status') != 'done' or value != {
                    'n': 2000, 'b': 65536, 't': '2026-10-17', 'i': '1,234,567', 'w': True}:
                raise ValueError('Linux allocation/Intl/Temporal/Wasm stress differs')
        checks.append('v8-memory-feature-stress-100-cycles')
        receipt = {'schemaVersion': 1, 'target': target, 'hostMachine': platform.machine(),
                   'librarySha256': prebuilt.sha(binary), 'v8Version': library.sv8_version().decode(),
                   'checks': checks, 'applicationRuntimeAccepted': False,
                   'sourceCompatibilityTested': False}
        return receipt
    finally:
        library.sv8_destroy(runtime)



def run_native(package, target, execution_mode='native'):
    """Accept a probe only after the child process completes normal teardown."""
    if execution_mode not in ('native', 'emulated'):
        raise ValueError('Explicit native/emulated execution mode required')
    host = {'x86_64': 'linux-x64', 'amd64': 'linux-x64',
            'aarch64': 'linux-arm64', 'arm64': 'linux-arm64'}.get(platform.machine().lower())
    if platform.system() != 'Linux' or target != host:
        raise ValueError('Native runtime requires matching Linux host architecture')
    result = subprocess.run([sys.executable, str(HERE / 'linux_sdk.py'), '--probe-worker',
                             str(Path(package).resolve()), target], capture_output=True, text=True, timeout=30)
    if result.returncode != 0:
        raise ValueError('Linux probe failed, including process teardown (exit ' + str(result.returncode) + '): ' +
                         result.stderr[-8192:])
    receipt = json.loads(result.stdout)
    if receipt.get('target') != target or receipt.get('applicationRuntimeAccepted') is not False:
        raise ValueError('Linux runtime probe receipt differs')
    receipt['executionMode'] = execution_mode
    receipt['processExitCode'] = result.returncode
    (Path(package) / (target + '-runtime.json')).write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def main():
    if len(sys.argv) == 4 and sys.argv[1] == '--probe-worker':
        print(json.dumps(_native_probe(sys.argv[2], sys.argv[3])), flush=True)
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--prepare-dynamic', choices=PROFILES)
    parser.add_argument('--target', action='append', choices=PROFILES)
    parser.add_argument('--sdk-cache', type=Path)
    parser.add_argument('--compiler-cache', type=Path)
    parser.add_argument('--sysroot-cache', type=Path)
    parser.add_argument('--verify-existing', action='store_true')
    parser.add_argument('--run-native', action='store_true')
    parser.add_argument('--execution-mode', choices=('native', 'emulated'), default='native',
                        help='Explicitly label emulated container execution when applicable')
    args = parser.parse_args()
    if args.prepare_dynamic:
        print(json.dumps({'artifactRoot': str(prepare_dynamic(args.prepare_dynamic, sdk_cache=args.sdk_cache,
            compiler_cache=args.compiler_cache, sysroot_cache=args.sysroot_cache))}))
        return
    if args.output is None or not args.target:
        raise ValueError('--output and --target are required for standalone build')
    if not args.verify_existing:
        print(build(args.output, args.target, sdk_cache=args.sdk_cache, compiler_cache=args.compiler_cache,
                    sysroot_cache=args.sysroot_cache))
    elif not args.run_native:
        raise ValueError('--verify-existing requires --run-native')
    if args.run_native:
        if len(args.target) != 1:
            raise ValueError('--run-native requires exactly one host target')
        print(json.dumps(run_native(args.output, args.target[0], args.execution_mode), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        detail = getattr(error, 'stderr', None) or getattr(error, 'stdout', None) or str(error)
        raise SystemExit('Linux V8 bridge build failed: ' + detail)
