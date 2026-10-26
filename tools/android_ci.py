#!/usr/bin/env python3
"""Native Android CI and signed shell upgrade acceptance on an owned 16 KiB AVD.

``--bootstrap`` provisions tooling into ANDROID_CI_CACHE rather than the
user's installed SDK/JDK/Rust. No command selects a connected phone or the shared
ADB server. ``verify --apk ...`` is a read-only APK/ELF check.
"""
from __future__ import annotations

import argparse
import base64
from functools import cached_property
import hashlib
import io
import json
from types import SimpleNamespace
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import socket
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RUST = '1.97.1'
NDK = '29.0.13846066'
JDK = '21.0.8+9'
BUILD_TOOLS = '36.0.0'
CMDLINE_TOOLS = '22.0'
FILAMENT = '1.78.0'
# The emulator requires 1.2 * 6 GiB for userdata; keep room for boot writes too.
EMULATOR_DISK_RESERVE = 8 * 1024 ** 3
RUST_CACHE_LIMIT = 6 * 1024 ** 3
# Leave time for diagnostics (~1 min), emulator shutdown (<=45 s) and artifact uploads.
JOB_RESERVE_SECONDS = 300
ABIS = {'arm64-v8a': 183, 'x86_64': 62}
RUST_TARGETS = {'arm64-v8a': 'aarch64-linux-android', 'x86_64': 'x86_64-linux-android'}


class AndroidProject:
    """Compile/output/instrumentation boundaries of the native APK."""

    def __init__(self, root, *, abis=tuple(ABIS)):
        self.root = ROOT / root
        self.abis = abis

    @property
    def rust_targets(self):
        return tuple(RUST_TARGETS[abi] for abi in self.abis)

    @cached_property
    def configuration(self):
        # Keep the smoke contract alongside its Gradle root.
        return json.loads((self.root / 'android-ci.json').read_text())

    @property
    def module(self):
        return self.configuration['module']

    @property
    def application_id(self):
        return self.configuration['application_id']

    @property
    def smoke_classes(self):
        return tuple(self.configuration['smoke_classes'])

    @property
    def minimum_tests(self):
        return self.configuration['minimum_tests']

    @property
    def ring(self):
        return self.configuration['ring']

    @property
    def outputs(self):
        return self.root / self.module / 'build/outputs/apk'

    @property
    def reports(self):
        return self.root / self.module / 'build/reports/native-ci'

    def apk(self, variant='debug'):
        suffix = '-unsigned' if variant == 'release' else ''
        return self.outputs / variant / f'{self.module}-{variant}{suffix}.apk'

    @property
    def test_apk(self):
        return self.outputs / 'androidTest/debug' / f'{self.module}-debug-androidTest.apk'


MOBILE = AndroidProject('mobile', abis=('arm64-v8a',))
# Screens the instrumented tests save in the app's files for review (NativeScreenCapture.kt).
SCREEN_CAPTURES = 'native-ci-screens'
SCREEN_CAPTURE_LIMIT = 64 * 1024 * 1024
PROJECTS = {'mobile': MOBILE}
# Last shell tree, before PR26 removes its sources. Used only inside a disposable CI fixture.
LEGACY_SHELL_COMMIT = '10af1fd060fd7825e6ef3ad19ad95ad4641d901b'


CMDLINE_DOWNLOADS = {
    ('Darwin', 'arm64'): ('commandlinetools-mac_arm64-15859902_latest.zip',
                           '835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e'),
    ('Darwin', 'x86_64'): ('commandlinetools-mac_x86_64-15859902_latest.zip',
                            'c5a6378ab5cf7e0d5701921405115befff13e9ff7417fb588389338f8bd050f3'),
    ('Linux', 'x86_64'): ('commandlinetools-linux-15859902_latest.zip',
                           '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583'),
}

# Published archive digests from google/filament's v1.78.0 release. The mac
# executable is arm64; do not silently try that binary on an Intel host.
FILAMENT_DOWNLOADS = {
    ('Darwin', 'arm64'): ('filament-v1.78.0-mac.tgz',
                           'dc2fbc7681998842e85ccc211ed1292b15631a97731fa49ef07c2698870ce9c5'),
    ('Linux', 'x86_64'): ('filament-v1.78.0-linux.tgz',
                           '6fde729259ef0aaca2b4c1a3768ba2c02ac9a808275bed5f8ae8c15a86c4f6fe'),
}


class CheckError(RuntimeError):
    pass


def run(args, *, env=None, cwd=ROOT, capture=False, timeout=1800, input_text=None):
    print('+ ' + ' '.join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True,
                          text=True, capture_output=capture, timeout=timeout, input=input_text)


def read_url(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'device-assistant-native-ci/1'})
    return urllib.request.urlopen(request, timeout=60)


def download(url, target, sha256):
    if target.exists():
        existing = hashlib.sha256()
        with target.open('rb') as source:
            while block := source.read(1024 * 1024):
                existing.update(block)
        if existing.hexdigest() == sha256:
            return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + '.part')
    digest = hashlib.sha256()
    try:
        with read_url(url) as response, temporary.open('wb') as output:
            while block := response.read(1024 * 1024):
                output.write(block)
                digest.update(block)
        if digest.hexdigest() != sha256:
            raise CheckError(f'Checksum mismatch for {target.name}')
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def unpack(archive, destination):
    """Reject archive traversal; retain executable permissions from SDK archives."""
    destination.mkdir(parents=True, exist_ok=True)
    base = destination.resolve()

    def inside(name):
        candidate = (base / name).resolve()
        if os.path.commonpath([base, candidate]) != str(base):
            raise CheckError(f'Archive member outside destination: {name}')

    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as source:
            for item in source.infolist():
                inside(item.filename)
                if (item.external_attr >> 16) & 0o170000 == 0o120000:
                    raise CheckError('SDK zip contains a symbolic link')
            source.extractall(destination)
            for item in source.infolist():
                permissions = (item.external_attr >> 16) & 0o777
                if permissions:
                    (destination / item.filename).chmod(permissions)
    else:
        with tarfile.open(archive) as source:
            for item in source.getmembers():
                inside(item.name)
                if item.issym():
                    inside(str(Path(item.name).parent / item.linkname))
                elif item.islnk():
                    inside(item.linkname)
                elif not item.isfile() and not item.isdir():
                    raise CheckError(f'Unsupported archive member: {item.name}')
            source.extractall(destination)


def filament_environment(cache, env, *, bootstrap):
    """Install only the material compiler, from the checked matching release."""
    host = platform.system(), platform.machine()
    if host not in FILAMENT_DOWNLOADS:
        if bootstrap:
            raise CheckError(f'Filament {FILAMENT} material compiler supports macOS arm64 or Linux x86_64; got {host}')
        return
    compiler = cache / f'filament-{FILAMENT}' / '-'.join(host) / 'bin/matc'
    if bootstrap:
        filename, checksum = FILAMENT_DOWNLOADS[host]
        archive = cache / 'downloads' / filename
        download(f'https://github.com/google/filament/releases/download/v{FILAMENT}/{filename}', archive, checksum)
        compiler.parent.mkdir(parents=True, exist_ok=True)
        temporary = compiler.with_suffix('.part')
        try:
            with tarfile.open(archive) as source:
                member = source.getmember('filament/bin/matc')
                if not member.isfile():
                    raise CheckError('Filament release did not contain a regular matc executable')
                with source.extractfile(member) as binary, temporary.open('wb') as output:
                    shutil.copyfileobj(binary, output)
            temporary.chmod(0o700)
            temporary.replace(compiler)
        finally:
            temporary.unlink(missing_ok=True)
    env['FILAMENT_MATC'] = str(compiler)
    env['FILAMENT_VERSION'] = FILAMENT


def prepare_gradle_graph_home(cache, host_key, graph_key, *, ci, disk_path=None):
    """Bound only this serial CI runner's generated Gradle homes for one ABI.

    Old global Gradle caches, other hosts and unrecognized directory names are
    untouched. No Gradle daemon survives a job (--no-daemon), and macos-xcode
    runs one job at a time, so every other graph is inactive here.
    """
    if host_key not in {'-'.join(host) for host in CMDLINE_DOWNLOADS}:
        raise CheckError('Unrecognized Gradle cache host ABI: ' + host_key)
    if not re.fullmatch(r'[0-9a-f]{20}', graph_key):
        raise CheckError('Invalid Gradle graph cache key')
    graph_root = cache / 'gradle' / host_key
    current = graph_root / graph_key
    for owned_path in (cache, cache / 'gradle', graph_root, current):
        if owned_path.is_symlink():
            raise CheckError('Owned Gradle cache path must not be a symbolic link: ' + str(owned_path))
    current.mkdir(parents=True, exist_ok=True)
    os.utime(current, None)
    if ci:
        others = sorted((entry for entry in graph_root.iterdir()
                         if entry != current and not entry.is_symlink() and entry.is_dir()
                         and re.fullmatch(r'[0-9a-f]{20}', entry.name)),
                        key=lambda entry: (entry.stat().st_mtime_ns, entry.name), reverse=True)
        # Retain the latest preceding dependency graph for a warm rebase.
        for stale in others[1:]:
            print('Removing inactive CI Gradle graph cache: ' + str(stale), flush=True)
            shutil.rmtree(stale)
        # Prefer keeping the preceding graph warm, but never keep an inactive graph
        # at the expense of the required emulator's userdata allocation.
        free = shutil.disk_usage(disk_path or graph_root).free
        if others and free < EMULATOR_DISK_RESERVE:
            print(f'CI Gradle cache pressure: {free} free bytes, reserve {EMULATOR_DISK_RESERVE}', flush=True)
            shutil.rmtree(others[0])
            print('Removed last inactive CI Gradle graph cache: ' + str(others[0]), flush=True)
    return current


def prepare_rust_cache(cache, *, ci, disk_path=None):
    """Evict only host debug artifacts, retaining the Android cross-target builds."""
    if not ci:
        return
    target = cache / 'cargo-target'
    debug = target / 'debug'
    for owned_path in (cache, target, debug):
        if owned_path.is_symlink():
            raise CheckError('Owned Rust cache path must not be a symbolic link: ' + str(owned_path))
    if not debug.exists():
        return
    if disk_path is None:
        size = sum(path.stat().st_size for path in target.rglob('*')
                   if not path.is_symlink() and path.is_file())
        if size <= RUST_CACHE_LIMIT:
            return
        pressure = f'{size} cached bytes, limit {RUST_CACHE_LIMIT}'
    else:
        free = shutil.disk_usage(disk_path).free
        if free >= EMULATOR_DISK_RESERVE:
            return
        pressure = f'{free} free bytes, reserve {EMULATOR_DISK_RESERVE}'
    print('CI Rust cache pressure: ' + pressure, flush=True)
    shutil.rmtree(debug)
    print('Removed CI Rust host debug cache: ' + str(debug), flush=True)


def prepare_smoke_disk_space(env, cache):
    """Recheck the userdata disk after downloads/builds, only for the owned CI cache."""
    if env.get('CI', '').lower() != 'true':
        return
    host_key = '-'.join((platform.system(), platform.machine()))
    current = Path(env.get('GRADLE_USER_HOME', ''))
    if current.parent != cache / 'gradle' / host_key:
        raise CheckError('Smoke cache cleanup requires the owned Gradle graph home')
    userdata_disk = Path(tempfile.gettempdir())
    before = shutil.disk_usage(userdata_disk).free
    print(f'Owned AVD free bytes before pressure cleanup: {before}', flush=True)
    prepare_gradle_graph_home(cache, host_key, current.name, ci=True, disk_path=userdata_disk)
    prepare_rust_cache(cache, ci=True, disk_path=userdata_disk)
    after = shutil.disk_usage(userdata_disk).free
    print(f'Owned AVD free bytes after pressure cleanup: {after}; reserve {EMULATOR_DISK_RESERVE}', flush=True)


def tool_environment(cache, *, bootstrap, project=MOBILE):
    env = os.environ.copy()
    if not bootstrap:
        sdk = Path(env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT') or
                   Path.home() / 'Library/Android/sdk')
        env['ANDROID_HOME'] = env['ANDROID_SDK_ROOT'] = str(sdk)
        if not env.get('JAVA_HOME'):
            candidates = [Path('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'),
                          Path('/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home')]
            for candidate in candidates:
                if (candidate / 'bin/java').is_file():
                    env['JAVA_HOME'] = str(candidate)
                    break
        if env.get('JAVA_HOME'):
            env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
        if project is MOBILE:
            filament_environment(cache, env, bootstrap=False)
        return env

    host = platform.system(), platform.machine()
    if host not in CMDLINE_DOWNLOADS:
        raise CheckError(f'Bootstrap supports macOS arm64/x86_64 and Linux x86_64; got {host}')
    cache.mkdir(parents=True, exist_ok=True)
    sdk = cache / 'sdk'
    jdk = cache / ('jdk-' + JDK.replace('+', '-'))
    env['ANDROID_HOME'] = env['ANDROID_SDK_ROOT'] = str(sdk)
    env['ANDROID_USER_HOME'] = str(cache / 'sdk-user')
    Path(env['ANDROID_USER_HOME']).mkdir(parents=True, exist_ok=True)
    # The cache follows the wrapper/catalog/locks/verification and host ABI, so
    # neither another APK graph nor an incompatible host reuses its task cache.
    dependency_key = hashlib.sha256()
    dependency_files = [project.root / 'gradle/libs.versions.toml',
                        project.root / 'gradle/wrapper/gradle-wrapper.properties',
                        project.root / 'gradle/verification-metadata.xml',
                        *sorted(project.root.glob('**/*lockfile'))]
    for dependency_file in dependency_files:
        if dependency_file.is_file():
            dependency_key.update(str(dependency_file.relative_to(project.root)).encode())
            dependency_key.update(dependency_file.read_bytes())
    host_key = '-'.join(host)
    env['GRADLE_USER_HOME'] = str(prepare_gradle_graph_home(
        cache, host_key, dependency_key.hexdigest()[:20], ci=env.get('CI', '').lower() == 'true'))
    env['CARGO_HOME'] = str(cache / 'cargo')
    env['RUSTUP_HOME'] = str(cache / 'rustup')
    # Each run checks out into a fresh folder; Rust's build output and Gradle's build cache outlive it here.
    # The Mac runner takes one job at a time (macos-xcode), so no two builds share the folder at once.
    env['CARGO_TARGET_DIR'] = str(cache / 'cargo-target')

    java_home = next(jdk.glob('*/Contents/Home'), None) if host[0] == 'Darwin' else next(jdk.glob('*'), None)
    if java_home is None or not (java_home / 'bin/java').is_file():
        arch = 'aarch64' if host[1] == 'arm64' else 'x64'
        system = 'mac' if host[0] == 'Darwin' else 'linux'
        filename = f'OpenJDK21U-jdk_{arch}_{system}_hotspot_21.0.8_9.tar.gz'
        url = f'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/{filename}'
        checksum = read_url(url + '.sha256.txt').read().decode().split()[0]
        archive = cache / 'downloads' / filename
        download(url, archive, checksum)
        unpack(archive, jdk)
        java_home = next(jdk.glob('*/Contents/Home'), None) if host[0] == 'Darwin' else next(jdk.glob('*'), None)
    if java_home is None:
        raise CheckError('Temurin archive did not contain a JDK')
    env['JAVA_HOME'] = str(java_home)
    env['PATH'] = str(java_home / 'bin') + ':' + str(cache / 'cargo/bin') + ':' + env['PATH']

    if project is MOBILE:
        filament_environment(cache, env, bootstrap=True)

    sdkmanager = sdk / f'cmdline-tools/{CMDLINE_TOOLS}/bin/sdkmanager'
    if not sdkmanager.is_file():
        filename, checksum = CMDLINE_DOWNLOADS[host]
        archive = cache / 'downloads' / filename
        download('https://dl.google.com/android/repository/' + filename, archive, checksum)
        with tempfile.TemporaryDirectory(dir=cache) as staging:
            unpack(archive, Path(staging))
            sdkmanager.parent.parent.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(Path(staging) / 'cmdline-tools'), str(sdkmanager.parent.parent))
    # These are Google's SDK licenses in a private CI cache, never the developer's SDK.
    run([sdkmanager, f'--sdk_root={sdk}', '--licenses'], env=env, input_text='y\n' * 100)
    image_abi = 'arm64-v8a' if host[1] == 'arm64' else 'x86_64'
    run([sdkmanager, f'--sdk_root={sdk}', 'platform-tools', f'build-tools;{BUILD_TOOLS}',
         'platforms;android-37.0', 'emulator', f'system-images;android-35;google_apis_ps16k;{image_abi}'], env=env)
    # The plan fixes r29-beta3's package ID, not whatever is newest in the preview channel.
    if project.ring:
        run([sdkmanager, f'--sdk_root={sdk}', '--channel=1', f'ndk;{NDK}'], env=env)
    else:
        return env

    rustup = cache / 'cargo/bin/rustup'
    if not rustup.is_file():
        triple = {'Darwin': ('aarch64-apple-darwin' if host[1] == 'arm64' else 'x86_64-apple-darwin'),
                  'Linux': 'x86_64-unknown-linux-gnu'}[host[0]]
        url = f'https://static.rust-lang.org/rustup/archive/1.28.2/{triple}/rustup-init'
        checksum = read_url(url + '.sha256').read().decode().split()[0]
        initializer = cache / 'downloads' / ('rustup-init-' + triple)
        download(url, initializer, checksum)
        initializer.chmod(0o700)
        run([initializer, '-y', '--no-modify-path', '--default-toolchain', 'none'], env=env)
    run([rustup, 'toolchain', 'install', RUST, '--profile', 'minimal', '--component', 'clippy,rustfmt'], env=env)
    run([rustup, 'target', 'add', '--toolchain', RUST, *project.rust_targets], env=env)
    return env


def verify_elf(data, expected_machine):
    if len(data) < 64 or data[:7] != b'\x7fELF\x02\x01\x01':
        raise CheckError('Expected a little-endian ELF64 library')
    if struct.unpack_from('<H', data, 18)[0] != expected_machine:
        raise CheckError('ELF architecture does not match its APK ABI directory')
    offset = struct.unpack_from('<Q', data, 32)[0]
    entry_size, count = struct.unpack_from('<HH', data, 54)
    if not count or count > 1024 or entry_size < 56 or offset + entry_size * count > len(data):
        raise CheckError('Invalid ELF program header table')
    loads = []
    relros = []
    for index in range(count):
        kind, flags, file_offset, address, _, _, size, align = struct.unpack_from('<IIQQQQQQ', data, offset + index * entry_size)
        if kind == 1:  # PT_LOAD
            loads.append((address, size, flags))
            if align < 16384 or align & (align - 1) or (address - file_offset) % 16384:
                raise CheckError('ELF LOAD segment is not aligned to 16 KiB')
        elif kind == 0x6474E552:  # PT_GNU_RELRO
            relros.append((address, size))
    if not loads:
        raise CheckError('ELF has no LOAD segments')
    if len(relros) > 1:
        raise CheckError('ELF has multiple GNU_RELRO segments')
    for address, size in relros:
        # AOSP phdr_table_get_relro_min_align: a RELRO covering the whole RW LOAD
        # needs no end alignment check. Only a partial prefix can protect writable
        # data accidentally when mprotect rounds up to the 16 KiB boundary.
        # https://android.googlesource.com/platform/bionic/+/android16-qpr2-release/linker/linker_phdr_16kib_compat.cpp
        covers_load = any(start == address and length <= size and flags & 2 for start, length, flags in loads)
        if not covers_load and (address + size) % 16384:
            raise CheckError('ELF GNU_RELRO partial prefix end is not aligned to 16 KiB')
    return len(loads)


def webview_dex_diagnostics(data, *, zipalign, env):
    """Explain the forbidden reference on CI without changing the failed verdict."""
    if zipalign is None or (env or {}).get('CI', '').lower() != 'true':
        return
    dexdump = Path(zipalign).with_name('dexdump')
    if not dexdump.is_file():
        return
    try:
        with tempfile.TemporaryDirectory(prefix='assistant-dex-diagnostic-') as directory:
            dex = Path(directory) / 'classes.dex'
            dump = Path(directory) / 'dexdump.txt'
            dex.write_bytes(data)
            with dump.open('w') as listing:
                subprocess.run([str(dexdump), '-d', str(dex)], env=env, text=True,
                               stdout=listing, stderr=subprocess.STDOUT, timeout=60, check=False)
            owner = member = ''
            references = []
            with dump.open(errors='replace') as listing:
                for line in listing:
                    if 'Class descriptor' in line:
                        owner, member = line.strip(), ''
                    elif line.lstrip().startswith('name '):
                        member = line.strip()
                    if 'android/webkit/WebView' in line or 'android.webkit.WebView' in line:
                        references.append('\n'.join((owner, member, line.rstrip()[:2048])))
                        if len(references) == 16:
                            break
            print('Forbidden WebView DEX references:\n' +
                  diagnostic_text('\n'.join(references), maximum=32 * 1024), flush=True)
    except (OSError, subprocess.SubprocessError) as error:
        print('WebView DEX diagnostic unavailable: ' + str(error), flush=True)


def verify_apk(apk, zipalign=None, *, env=None, require_ring=True, abis=tuple(ABIS)):
    result = []
    required = {f'lib/{abi}/libherzring.so' for abi in abis} if require_ring else set()
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names):
            raise CheckError('APK contains duplicate ZIP entries')
        for name in names:
            if re.fullmatch(r'classes[0-9]*\.dex', name):
                data = archive.read(name)
                if b'Landroid/webkit/WebView;' in data:
                    webview_dex_diagnostics(data, zipalign=zipalign, env=env)
                    raise CheckError('APK DEX references android.webkit.WebView: ' + name)
        missing = required.difference(names)
        if missing:
            raise CheckError('APK is missing ring libraries: ' + ', '.join(sorted(missing)))
        for item in archive.infolist():
            if not item.filename.startswith('lib/') or not item.filename.endswith('.so'):
                continue
            parts = item.filename.split('/')
            if len(parts) != 3 or parts[1] not in abis:
                raise CheckError('Unexpected native ABI: ' + item.filename)
            if item.compress_type != zipfile.ZIP_STORED:
                raise CheckError('Native library must be uncompressed: ' + item.filename)
            if item.file_size > 64 * 1024 * 1024:
                raise CheckError('Native library exceeds verification bound')
            segments = verify_elf(archive.read(item), ABIS[parts[1]])
            result.append({'library': item.filename, 'load_segments': segments, 'page_alignment': 16384})
    if zipalign is not None:
        run([zipalign, '-c', '-P', '16', '4', apk], env=env)
    return {'apk': str(apk), 'libraries': result}


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def emulator_ports():
    # Android Emulator needs an even console port in its supported range.
    for console in range(5580, 5680, 2):
        first, second = socket.socket(), socket.socket()
        try:
            first.bind(('127.0.0.1', console))
            second.bind(('127.0.0.1', console + 1))
            return console, console + 1
        except OSError:
            pass
        finally:
            first.close()
            second.close()
    raise CheckError('No pair of unused emulator ports in the CI range')


def require_instrumentation_success(output, minimum_tests=1, *, expected_classes=()):
    completed = re.search(r'OK \(([1-9][0-9]*) tests?\)', output)
    if ('FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output or 'INSTRUMENTATION_ABORTED' in output
            or 'INSTRUMENTATION_RESULT: shortMsg=' in output
            or not completed or int(completed.group(1)) < minimum_tests):
        raise CheckError('Instrumentation did not report the required passing tests')
    if expected_classes:
        passed_classes = set()
        passed_methods = set()
        for status in output.split('INSTRUMENTATION_STATUS_CODE: 0')[:-1]:
            classes = re.findall(r'^INSTRUMENTATION_STATUS: class=(.+)$', status, re.MULTILINE)
            if classes:
                name = classes[-1].strip()
                passed_classes.add(name)
                methods = re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$', status, re.MULTILINE)
                if methods:
                    passed_methods.add(name + '#' + methods[-1].strip())
        missing = set(expected_classes) - passed_classes - passed_methods
        if missing:
            raise CheckError('Instrumentation did not execute required classes: ' + ', '.join(sorted(missing)))


def diagnostic_text(value, *, maximum=256 * 1024):
    # TimeoutExpired carries bytes even when subprocess.run(text=True) was used.
    if isinstance(value, bytes):
        value = value.decode('utf-8', errors='replace')
    text = value or ''
    if len(text) > maximum:
        return '[earlier diagnostic output omitted]\n' + text[-maximum:]
    return text


def owned_instrumentation_diagnostics(adb, server, serial, reports, *, env, project=MOBILE):
    """Bounded failure evidence from this job's disposable AVD before teardown."""
    if server == 5037 or not serial.startswith('127.0.0.1:'):
        raise CheckError('Emulator ADB must use its private server and loopback transport')
    selected = [str(adb), '-P', str(server), '-s', serial]
    commands = (
        ('adb-state', ['get-state'], 8),
        ('processes', ['shell', 'ps', '-A'], 8),
        ('instrumentation', ['shell', 'dumpsys', '-t', '6', 'activity', 'processes', project.application_id], 8),
        ('java-stacks', ['shell', f'for pid in $(pidof {project.application_id}); do debuggerd -j $pid; done'], 15),
        ('logcat', ['shell', 'logcat', '-d', '-t', '1200', '-v', 'threadtime'], 8),
    )
    evidence = {'server': server, 'serial': serial, 'commands': []}
    # Diagnostics never extend or retry the failed instrumentation command. Each
    # probe is optional, has its own small bound, and cannot replace its failure.
    for name, arguments, timeout in commands:
        command = selected + arguments
        record = {'name': name, 'timeout_seconds': timeout}
        try:
            result = subprocess.run(command, env=env, capture_output=True, text=True,
                                    timeout=timeout, check=False)
            stdout, stderr = result.stdout, result.stderr
            record['returncode'] = result.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr = error.output, error.stderr
            record['timed_out'] = True
        except OSError as error:
            stdout, stderr = '', str(error)
            record['error'] = str(error)
        (reports / f'instrumentation-{name}.txt').write_text(
            diagnostic_text(stdout) + diagnostic_text(stderr))
        evidence['commands'].append(record)
    emulator_log = reports / 'emulator.log'
    if emulator_log.is_file():
        # Keep a bounded .txt copy as well: the prior failing artifacts retained
        # no emulator.log, although the upload step reported three input files.
        with emulator_log.open('rb') as log:
            log.seek(0, os.SEEK_END)
            log.seek(max(0, log.tell() - 256 * 1024))
            (reports / 'emulator-failure-tail.txt').write_text(diagnostic_text(log.read()))
    (reports / 'instrumentation-diagnostics.json').write_text(json.dumps(evidence, indent=2) + '\n')


def pull_screen_captures(adb, server, serial, reports, *, env, project=MOBILE):
    """Copy the screens instrumented tests saved for review; evidence only, never a verdict."""
    # Runs in a finally after the instrumentation: it must never raise over that verdict.
    if server == 5037 or not serial.startswith('127.0.0.1:'):
        return []
    command = [str(adb), '-P', str(server), '-s', serial, 'exec-out', 'run-as', project.application_id,
               'tar', '-cf', '-', '-C', 'files', SCREEN_CAPTURES]
    try:
        result = subprocess.run(command, env=env, capture_output=True, timeout=60, check=False)
    except (subprocess.TimeoutExpired, OSError):
        return []
    if result.returncode != 0 or not result.stdout:
        return []
    target = reports / 'screens'
    saved, total = [], 0
    try:
        with tarfile.open(fileobj=io.BytesIO(result.stdout)) as archive:
            for member in archive.getmembers():
                name = Path(member.name).name
                if not member.isfile() or not name.endswith(('.png', '.mp4')) or total + member.size > SCREEN_CAPTURE_LIMIT:
                    continue
                target.mkdir(parents=True, exist_ok=True)
                (target / name).write_bytes(archive.extractfile(member).read())
                total += member.size
                saved.append(name)
    except tarfile.TarError:
        return saved
    return sorted(saved)


def job_deadline():
    try:
        return int(os.environ.get('ANDROID_CI_DEADLINE', ''))
    except ValueError as error:
        raise CheckError('ANDROID_CI_DEADLINE must be set to the job deadline in Unix seconds') from error


def instrumentation_timeout(project, *, deadline, now):
    """One instrumentation run grows with its suite: a fixed budget cut off 43 of 52 tests (run 3404)."""
    timeout = min(120 + 15 * project.minimum_tests, deadline - now - JOB_RESERVE_SECONDS)
    if timeout < 60:
        raise CheckError('No time left before the job deadline for an instrumentation run')
    return timeout


def stream_instrumentation(command, log_path, *, env, timeout, heartbeat=60):
    print('+ ' + ' '.join(map(str, command)), flush=True)
    started = time.monotonic()
    with log_path.open('w') as log:
        process = subprocess.Popen(list(map(str, command)), cwd=ROOT, env=env,
                                   stdout=log, stderr=subprocess.STDOUT)
        while True:
            remaining = max(0, timeout - (time.monotonic() - started))
            try:
                code = process.wait(timeout=min(heartbeat, remaining))
                break
            except subprocess.TimeoutExpired:
                remaining = timeout - (time.monotonic() - started)
                if remaining <= 0:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                    raise subprocess.TimeoutExpired(command, timeout,
                                                    output=log_path.read_text(encoding='utf-8', errors='replace'))
                completed, current = 0, None
                test_class = test = None
                for line in log_path.read_text(encoding='utf-8', errors='replace').splitlines():
                    if line.startswith('INSTRUMENTATION_STATUS: class='):
                        test_class = line.removeprefix('INSTRUMENTATION_STATUS: class=').strip()
                    elif line.startswith('INSTRUMENTATION_STATUS: test='):
                        test = line.removeprefix('INSTRUMENTATION_STATUS: test=').strip()
                    elif line.startswith('INSTRUMENTATION_STATUS_CODE:'):
                        status = line.removeprefix('INSTRUMENTATION_STATUS_CODE:').strip()
                        if status == '1':
                            current = f'{test_class}#{test}'
                        elif status in ('0', '-1', '-2', '-3', '-4'):
                            completed += 1
                            current = None
                        test_class = test = None
                print(f'Instrumentation progress: {completed} completed; running {current or "(none)"}; '
                      f'{remaining:.0f}s remaining', flush=True)
    output = log_path.read_text(encoding='utf-8', errors='replace')
    if code:
        raise subprocess.CalledProcessError(code, command, output=output)
    return output


def prepare_targeted_application(adb, server, serial, *, env, project=MOBILE):
    selected = [adb, '-P', server, '-s', serial]
    # The full suite opens the native UI before tests seed encrypted sessions.
    # MainActivity blocks its first draw until restore and Compose are ready;
    # -W waits for that first launch without extending any test's deadline.
    launch = run(selected + ['shell', 'am', 'start', '-W', '-n',
                             project.application_id + '/assistant.mobile.android.MainActivity'],
                 env=env, capture=True, timeout=120)
    print(launch.stdout + launch.stderr, flush=True)
    if 'Status: ok' not in launch.stdout:
        raise CheckError('Targeted instrumentation requires a completed native first launch')
    # Keep first-launch storage, but discard the activity, runtime and origin
    # coordinator so instrumentation starts with a fresh process and test seams.
    run(selected + ['shell', 'am', 'force-stop', project.application_id], env=env, timeout=30)


def run_owned_instrumentation(adb, server, serial, reports, *, env, project=MOBILE, classes=None):
    if server == 5037 or not serial.startswith('127.0.0.1:'):
        raise CheckError('Emulator ADB must use its private server and loopback transport')
    selected_classes = project.smoke_classes if classes is None else tuple(classes)
    command = [adb, '-P', server, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e',
               'class', ','.join(selected_classes)]
    for key, value in getattr(project, 'arguments', {}).items():
        command.extend(('-e', key, value))
    command.append(project.application_id + '.test/androidx.test.runner.AndroidJUnitRunner')
    started = time.monotonic()
    evidence = {'timeout_seconds': None, 'server': server, 'serial': serial,
                'application_id': project.application_id,
                'instrumentation': selected_classes, 'passed': False}
    output = ''
    try:
        deadline = job_deadline()
        evidence['deadline'] = deadline
        if classes is not None and project is MOBILE:
            prepare_targeted_application(adb, server, serial, env=env, project=project)
        timeout = instrumentation_timeout(project, deadline=deadline, now=time.time())
        evidence['timeout_seconds'] = timeout
        try:
            output = stream_instrumentation(command, reports / 'jni-smoke.txt', env=env, timeout=timeout)
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
            output = error.output or ''
            evidence['timed_out'] = isinstance(error, subprocess.TimeoutExpired)
            evidence['error'] = str(error)
            raise
        require_instrumentation_success(output, minimum_tests=project.minimum_tests if classes is None else 1,
                                        expected_classes=selected_classes)
        evidence['passed'] = True
    except (CheckError, subprocess.SubprocessError, OSError) as error:
        # Publish the original failure even if the device has disappeared or a
        # diagnostic file cannot be saved. No second instrumentation attempt.
        print('Instrumentation failure output:\n' + diagnostic_text(output, maximum=32 * 1024), flush=True)
        evidence.setdefault('error', str(error))
        evidence['elapsed_seconds'] = time.monotonic() - started
        try:
            owned_instrumentation_diagnostics(adb, server, serial, reports, env=env,
                                              project=project)
        except (CheckError, subprocess.SubprocessError, OSError) as error:
            evidence['diagnostics_error'] = str(error)
        raise
    finally:
        evidence.setdefault('elapsed_seconds', time.monotonic() - started)
        (reports / 'instrumentation-run.json').write_text(json.dumps(evidence, indent=2) + '\n')


def owned_adb_wrapper(path, adb, server, serial):
    if server == 5037 or not serial.startswith('127.0.0.1:'):
        raise CheckError('Emulator ADB must use its private server and loopback transport')
    path.write_text(f'#!{sys.executable}\nimport os, sys\n'
                    'args = sys.argv[1:]; kept = []; index = 0\n'
                    'while index < len(args):\n'
                    '    arg = args[index]; index += 1\n'
                    '    if arg in ("-e", "-d", "-a"): continue\n'
                    '    if arg in ("-s", "-P", "-L", "-H"): index += 1; continue\n'
                    '    kept.extend(args[index - 1:]); break\n'
                    f'os.execv({str(adb)!r}, [{str(adb)!r}, "-P", {str(server)!r}, "-s", {serial!r}] + kept)\n')
    path.chmod(0o700)


def become_root(adb, server, serial, process, *, env):
    """`adb root` restarts adbd, which can drop this very connection ("closed", exit 1): whether the
    disposable image runs adbd as root is decided by the probe after it, never by that exit status."""
    command = [str(adb), '-P', str(server), '-s', serial, 'root']
    print('+ ' + ' '.join(command), flush=True)
    try:
        subprocess.run(command, env=env, timeout=30, check=False)
    except subprocess.TimeoutExpired:
        pass
    wait_for_owned_adb(adb, server, serial, process, ['shell', 'id', '-u'], '0', env=env, timeout=30, interval=1,
                       failure_message='Disposable image is not rootable; cannot disable 16 KiB compatibility fallback')


def wait_for_owned_adb(adb, server, serial, process, command, expected, *, env, timeout, interval,
                       failure_message):
    """Retry transient ADB stalls within one deadline for this disposable AVD."""
    if server == 5037 or not serial.startswith('127.0.0.1:'):
        raise CheckError('Emulator ADB must use its private server and loopback transport')
    deadline = time.monotonic() + timeout
    connect = [str(adb), '-P', str(server), 'connect', serial]
    selected = [str(adb), '-P', str(server), '-s', serial]
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise CheckError(f'Owned emulator exited with {process.returncode}; see emulator.log')
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            subprocess.run(connect, env=env, capture_output=True, timeout=min(10, remaining),
                           check=False)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            result = subprocess.run(selected + command, env=env, capture_output=True, text=True,
                                    timeout=min(10, remaining), check=False)
            if result.returncode == 0 and result.stdout.strip() == expected and time.monotonic() <= deadline:
                return
        except subprocess.TimeoutExpired:
            # Cold boot and adbd's root restart can each stall a single command.
            # Neither extends the caller's deadline or bypasses the required probe.
            print('Owned emulator ADB command timed out; retrying within deadline', flush=True)
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(interval, remaining))
    raise CheckError(failure_message)


def prepare_body_fixtures(adb, server, serial, *, env):
    """Supply exact public web assets to the fake server without APK payloads."""
    if server == 5037 or not serial.startswith('127.0.0.1:'):
        raise CheckError('BodyAtlas fixtures require this job\'s private ADB server and loopback transport')
    selected = [str(adb), '-P', str(server), '-s', serial]
    destination = 'files/body-fixtures'
    run(selected + ['shell', 'run-as', MOBILE.application_id, 'mkdir', '-p', destination], env=env)
    sources = sorted((ROOT / 'web/src/assets/body').iterdir())
    sources.append(ROOT / 'tools/assets/ROKOKO_ANIMATION_NOTICE.md')
    count, size = 0, 0
    for source in sources:
        if not source.is_file():
            continue
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', source.name):
            raise CheckError('Unsupported BodyAtlas fixture filename: ' + source.name)
        # exec-in sends raw bytes, including NULs, without a host shell or PTY.
        # Only the remote redirection requires shell quoting; the file is opened
        # by run-as, so fixtures never become public app storage.
        command = f'run-as {MOBILE.application_id} sh -c {shlex.quote("cat > " + destination + "/" + source.name)}'
        print('+ BodyAtlas fixture ' + source.name, flush=True)
        with source.open('rb') as binary:
            subprocess.run(selected + ['exec-in', command], stdin=binary, env=env,
                           check=True, timeout=120)
        count += 1
        size += source.stat().st_size
    print(f'BodyAtlas private CI fixtures: {count} files, {size} bytes; APK contains none', flush=True)


def smoke(env, reports, *, timeout=300, project=MOBILE, exercise=None, classes=None):
    sdk = Path(env['ANDROID_HOME'])
    emulator = sdk / 'emulator/emulator'
    adb = sdk / 'platform-tools/adb'
    managers = sorted((sdk / 'cmdline-tools').glob('*/bin/avdmanager'))
    # Older SDK installs can use cmdline-tools/bin instead of a version directory.
    if not managers and (sdk / 'cmdline-tools/bin/avdmanager').is_file():
        managers = [sdk / 'cmdline-tools/bin/avdmanager']
    if not emulator.is_file() or not adb.is_file() or not managers:
        raise CheckError('Required SDK emulator/platform-tools/cmdline-tools are absent; run all --bootstrap')
    try:
        acceleration = run([emulator, '-accel-check'], env=env, capture=True, timeout=30)
    except subprocess.CalledProcessError as error:
        raise CheckError('Android hardware acceleration is unavailable; JNI smoke is required, not skipped') from error
    (reports / 'acceleration.txt').write_text(acceleration.stdout + acceleration.stderr)
    image_abi = 'arm64-v8a' if platform.machine() == 'arm64' else 'x86_64'
    image = f'system-images;android-35;google_apis_ps16k;{image_abi}'
    if not (sdk / f'system-images/android-35/google_apis_ps16k/{image_abi}/system.img').is_file():
        raise CheckError(f'Required 16 KiB image {image} is absent; run all --bootstrap')
    console, bridge = emulator_ports()
    server = free_port()
    serial = f'127.0.0.1:{bridge}'
    own_env = env.copy()
    own_env['ADB_SERVER_SOCKET'] = f'tcp:127.0.0.1:{server}'
    own_env['ANDROID_ADB_SERVER_PORT'] = str(server)
    process = None
    with tempfile.TemporaryDirectory(prefix='assistant-ring-ci-') as directory:
        own_env['ANDROID_AVD_HOME'] = str(Path(directory) / 'avd')
        own_env['ANDROID_USER_HOME'] = str(Path(directory) / 'android')
        own_env['ANDROID_EMULATOR_HOME'] = own_env['ANDROID_USER_HOME']
        Path(own_env['ANDROID_AVD_HOME']).mkdir()
        Path(own_env['ANDROID_USER_HOME']).mkdir()
        # Emulator's internal `adb -e` calls are ambiguous when it registers its local
        # transport and we also connect over TCP. Route every internal call explicitly
        # to this server/AVD, regardless of the owner's default ADB state.
        internal_adb = Path(directory) / 'owned-adb'
        owned_adb_wrapper(internal_adb, adb, server, serial)
        name = 'assistant-ring-ci-' + uuid.uuid4().hex[:12]
        run([managers[-1], 'create', 'avd', '--name', name, '--package', image],
            env=own_env, input_text='no\n', timeout=120)
        # API 24+ clamps fresh userdata to at least 6 GiB. Use that minimum for
        # this owned smoke AVD instead of the SDK image's 10 GiB default; the
        # emulator checks for 1.2x the partition size before creating userdata.
        # https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android-qemu2-glue/main.cpp
        avd_config = Path(own_env['ANDROID_AVD_HOME']) / (name + '.avd') / 'config.ini'
        settings = [line for line in avd_config.read_text().splitlines()
                    if line.partition('=')[0].strip() != 'disk.dataPartition.size']
        if project is MOBILE:
            # The native smoke asserts the actual software IME, not just focus.
            settings = [line for line in settings if line.partition('=')[0].strip() != 'hw.keyboard']
            settings.append('hw.keyboard=no')
        avd_config.write_text('\n'.join(settings) + '\ndisk.dataPartition.size=6G\n')
        try:
            run([adb, '-P', server, 'start-server'], env=own_env, timeout=30)
            with (reports / 'emulator.log').open('w') as log:
                process = subprocess.Popen([str(emulator), '-avd', name, '-ports', f'{console},{bridge}',
                                            '-no-window', '-no-audio', '-no-snapshot', '-no-boot-anim',
                                            '-gpu', 'swiftshader', '-accel', 'on', '-no-direct-adb',
                                            '-adb-path', str(internal_adb)],
                                           env=own_env, stdout=log, stderr=subprocess.STDOUT)
                selected = [adb, '-P', server, '-s', serial]
                wait_for_owned_adb(adb, server, serial, process, ['shell', 'getprop', 'sys.boot_completed'], '1',
                                   env=own_env, timeout=timeout, interval=2,
                                   failure_message='Owned 16 KiB emulator did not boot before deadline; JNI smoke remains failed')
                page_size = run(selected + ['shell', 'getconf', 'PAGE_SIZE'], env=own_env,
                                capture=True, timeout=30).stdout.strip()
                if page_size != '16384':
                    raise CheckError(f'Emulator page size is {page_size}, required 16384')
                # Property writes need root on Google's userdebug image. Restart only
                # this disposable AVD's adbd, then reconnect its explicit TCP transport.
                become_root(adb, server, serial, process, env=own_env)
                # Disable Android 16 KiB compatibility fallback only inside this disposable AVD.
                run(selected + ['shell', 'setprop', 'bionic.linker.16kb.app_compat.enabled', 'false'], env=own_env)
                run(selected + ['shell', 'setprop', 'pm.16kb.app_compat.disabled', 'true'], env=own_env)
                if project is MOBILE:
                    run(selected + ['shell', 'settings', 'put', 'secure',
                                    'show_ime_with_hard_keyboard', '1'], env=own_env)
                if exercise is not None:
                    return exercise(adb, server, serial, own_env)
                run(selected + ['install', '-r', '-t', project.apk()], env=own_env)
                run(selected + ['install', '-r', '-t', project.test_apk], env=own_env)
                if project is MOBILE:
                    prepare_body_fixtures(adb, server, serial, env=own_env)
                try:
                    run_owned_instrumentation(adb, server, serial, reports, env=own_env, project=project,
                                              classes=classes)
                finally:
                    if project is MOBILE:
                        pull_screen_captures(adb, server, serial, reports, env=own_env, project=project)
                return {'page_size': 16384, 'image': image, 'application_id': project.application_id,
                        'instrumentation': project.smoke_classes if classes is None else tuple(classes), 'passed': True}
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)
            # This is the private ADB server started above, never the user's port 5037.
            subprocess.run([str(adb), '-P', str(server), 'kill-server'], env=own_env,
                           capture_output=True, timeout=15, check=False)


def legacy_shell_tree(destination, *, env):
    """Recover the archived implementation as a same-package synthetic predecessor on CI."""
    if env.get('CI', '').lower() != 'true':
        raise CheckError('Synthetic legacy shell preparation is CI-only')
    try:
        run(['git', 'cat-file', '-e', LEGACY_SHELL_COMMIT + '^{commit}'], env=env, capture=True)
    except subprocess.CalledProcessError:
        repository = env.get('GITHUB_SERVER_URL', '').rstrip('/') + '/' + env.get('GITHUB_REPOSITORY', '')
        if not repository.startswith('https://') and not repository.startswith('http://'):
            raise CheckError('CI repository URL is required to recover the legacy fixture')
        token = env.get('GITHUB_TOKEN')
        if not token:
            raise CheckError('CI token is required to recover the legacy fixture')
        fetch_env = {**env, 'GIT_CONFIG_COUNT': '1', 'GIT_CONFIG_KEY_0': 'http.extraHeader',
                     'GIT_CONFIG_VALUE_0': 'Authorization: basic ' +
                     base64.b64encode(('x-access-token:' + token).encode()).decode()}
        run(['git', 'fetch', '--no-tags', '--depth=1', repository, LEGACY_SHELL_COMMIT], env=fetch_env)
    archive = destination.parent / 'legacy-shell.tar'
    try:
        run(['git', 'archive', '--format=tar', '--output', archive, LEGACY_SHELL_COMMIT], env=env)
        unpack(archive, destination)
    finally:
        archive.unlink(missing_ok=True)
    # Android cannot upgrade across application IDs. Rebuild this archived implementation
    # under today's ID to exercise same-ID data/Keystore retention, never a real old install.
    gradle = destination / 'shell/android/app/build.gradle.kts'
    original = gradle.read_text()
    assignment = 'applicationId = "dev.assistant.shell"'
    if original.count(assignment) != 1:
        raise CheckError('Archived upgrade fixture has an unexpected application ID')
    gradle.write_text(original.replace(assignment, f'applicationId = "{MOBILE.application_id}"'))
    tests = destination / 'shell/android/app/src/androidTest'
    shutil.rmtree(tests)
    seed = tests / 'kotlin/assistant/shell'
    seed.mkdir(parents=True)
    shutil.copyfile(ROOT / 'tools/android-upgrade/LegacyUpgradeSeedTest.kt', seed / 'LegacyUpgradeSeedTest.kt')
    return destination / 'shell/android'


def installed_uid(selected, *, env):
    output = run(selected + ['shell', 'pm', 'list', 'packages', '-U', MOBILE.application_id],
                 env=env, capture=True, timeout=30).stdout.strip()
    # pm's filter is a substring match: the instrumentation APK matches too.
    entries = [re.fullmatch(r'package:([A-Za-z0-9_.]+) uid:([0-9]+)', line)
               for line in output.splitlines()]
    matches = [entry for entry in entries if entry is not None and entry.group(1) == MOBILE.application_id]
    if any(entry is None for entry in entries) or len(matches) != 1:
        raise CheckError('Could not read the synthetic installation UID: ' + output)
    return int(matches[0].group(2))


def exercise_upgrade(adb, server, serial, env, reports, *, legacy_apk, legacy_test, apk, test_apk, origin):
    """Upgrade a same-package synthetic predecessor without clearing data or copying credentials."""
    if server == 5037 or not serial.startswith('127.0.0.1:'):
        raise CheckError('Upgrade acceptance requires the private CI emulator')
    selected = [adb, '-P', server, '-s', serial]
    seed_reports = reports / 'legacy-seed'
    seed_reports.mkdir(parents=True, exist_ok=True)
    native_reports = reports / 'upgrade'
    native_reports.mkdir(parents=True, exist_ok=True)
    jni_reports = reports / 'release-jni'
    jni_reports.mkdir(parents=True, exist_ok=True)
    seed = SimpleNamespace(application_id=MOBILE.application_id, minimum_tests=1,
                           smoke_classes=('assistant.shell.LegacyUpgradeSeedTest',), arguments={'origin': origin})
    native = SimpleNamespace(application_id=MOBILE.application_id, minimum_tests=1,
                             smoke_classes=('assistant.mobile.android.NativeUpgradeSmokeTest',))
    release_jni = SimpleNamespace(application_id=MOBILE.application_id, minimum_tests=4,
                                 smoke_classes=('assistant.mobile.android.NativeReleaseJniSmokeTest',))
    # Acceptance uses the exact production artifact, with network disabled before either app runs.
    run(selected + ['shell', 'svc', 'wifi', 'disable'], env=env)
    run(selected + ['shell', 'svc', 'data', 'disable'], env=env)
    run(selected + ['shell', 'settings', 'put', 'global', 'airplane_mode_on', '1'], env=env)
    run(selected + ['shell', 'am', 'broadcast', '-a', 'android.intent.action.AIRPLANE_MODE', '--ez', 'state', 'true'], env=env)
    run(selected + ['install', '-t', legacy_apk], env=env)
    before = installed_uid(selected, env=env)
    run(selected + ['install', '-t', legacy_test], env=env)
    run_owned_instrumentation(adb, server, serial, seed_reports, env=env, project=seed)
    run(selected + ['shell', 'am', 'force-stop', MOBILE.application_id], env=env)
    run(selected + ['install', '-r', apk], env=env)
    after = installed_uid(selected, env=env)
    if before != after:
        raise CheckError(f'Upgrade changed the app UID: {before} -> {after}')
    run(selected + ['install', '-r', '-t', test_apk], env=env)
    run_owned_instrumentation(adb, server, serial, native_reports, env=env, project=native)
    # Reuse this installed signed/R8 APK and emulator; retain separate JNI failure evidence.
    run_owned_instrumentation(adb, server, serial, jni_reports, env=env, project=release_jni)
    return {'passed': True, 'legacy_commit': LEGACY_SHELL_COMMIT, 'uid_before': before,
            'uid_after': after, 'application_id': MOBILE.application_id,
            'instrumentation': native.smoke_classes + release_jni.smoke_classes}


def upgrade(release_dir, env, reports, cache):
    """Acceptance of the exact signed/minified release APK, with the same signing identity."""
    import android_release as release

    verify_native_sources()
    report = json.loads((release_dir / 'release.json').read_text())
    apk = release_dir / report['apk']
    tools = Path(env['ANDROID_HOME']) / f'build-tools/{BUILD_TOOLS}'
    release.validate_update(apk, report, 1, release.pinned(), tools, env)
    artifacts = release_dir / 'upgrade'
    artifacts.mkdir(parents=True, exist_ok=True)
    # Release instrumentation consumes AGP's obfuscation mapping for this app variant.
    run([MOBILE.root / 'gradlew', ':androidApp:assembleReleaseAndroidTest',
         '-PassistantTestBuildType=release', f'-PassistantOrigin={report["origin"]}',
         f'-PassistantPreviousOrigin={report.get("previous_origin") or ""}',
         f'-PassistantVersionCode={report["version_code"]}', f'-PassistantVersionName={report["version_name"]}',
         '--dependency-verification', 'strict', '--console=plain', '--no-daemon', '--build-cache'],
        env=env, cwd=MOBILE.root)
    test_apk = artifacts / 'native-upgrade-test.apk'
    release.sign_apk(MOBILE.outputs / 'androidTest/release/androidApp-release-androidTest.apk', test_apk, tools, env)
    with tempfile.TemporaryDirectory(prefix='assistant-legacy-upgrade-') as directory:
        old_root = legacy_shell_tree(Path(directory) / 'old', env=env)
        # Both archived ABIs are provisioned only for this old fixture, never the native app.
        rustup = shutil.which('rustup', path=env['PATH'])
        run([rustup, 'target', 'add', '--toolchain', RUST, *RUST_TARGETS.values()], env=env)
        run([old_root / 'gradlew', ':app:assembleDebug', ':app:assembleDebugAndroidTest',
             '-PassistantVersionCode=1', f'-PassistantOrigin={report["origin"]}',
             f'-PassistantDevOrigin={report["origin"]}', '--dependency-verification', 'strict',
             '--console=plain', '--no-daemon', '--build-cache'], env=env, cwd=old_root)
        old_apk = artifacts / 'synthetic-legacy.apk'
        old_test = artifacts / 'synthetic-legacy-test.apk'
        release.sign_apk(old_root / 'app/build/outputs/apk/debug/app-debug.apk', old_apk, tools, env)
        release.sign_apk(old_root / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', old_test, tools, env)
        for signed in (old_apk, old_test, test_apk):
            if release.certificate(tools / 'apksigner', signed, env) != report['certificate_sha256']:
                raise CheckError('Upgrade fixture signer differs from the release APK')
    prepare_smoke_disk_space(env, cache)
    result = smoke(env, reports, exercise=lambda adb, server, serial, own_env:
                   exercise_upgrade(adb, server, serial, own_env, reports, legacy_apk=old_apk,
                                    legacy_test=old_test, apk=apk, test_apk=test_apk, origin=report['origin']))
    (artifacts / 'upgrade.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def rust_checks(cargo, env, reports):
    """Report independent Rust failures together and export formatting on CI."""
    if env.get('CI', '').lower() == 'true':
        # Fresh CI checkouts cannot reuse incremental work; test inherits dev's
        # debug setting, avoiding macOS unpacked debuginfo object accumulation.
        env = {**env, 'CARGO_INCREMENTAL': '0', 'CARGO_PROFILE_DEV_DEBUG': '0'}
    workspace = ROOT / 'shell/ring'
    failures = []
    checks = (
        ('tests', ['test', '--locked', '--workspace']),
        ('clippy', ['clippy', '--locked', '--workspace', '--all-targets', '--', '-D', 'warnings']),
        ('format', ['fmt', '--all', '--', '--check']),
    )
    for name, command in checks:
        try:
            run([cargo, f'+{RUST}', *command], env=env, cwd=workspace)
        except subprocess.SubprocessError as error:
            failures.append(name)
            print(f'Rust {name} failed: {error}', flush=True)
            if name == 'format':
                # Never turn a failed check green by rewriting the checkout.
                # This disposable CI edit is a proposal for source review.
                try:
                    run([cargo, f'+{RUST}', 'fmt', '--all'], env=env, cwd=workspace)
                    patch = run(['git', 'diff', '--binary', '--', 'shell/ring'],
                                env=env, capture=True)
                    reports.mkdir(parents=True, exist_ok=True)
                    (reports / 'rust-format.patch').write_text(patch.stdout)
                except (subprocess.SubprocessError, OSError) as proposal_error:
                    print(f'Rust formatting proposal unavailable: {proposal_error}', flush=True)
    if failures:
        raise CheckError('Rust checks failed: ' + ', '.join(failures))


def verify_native_sources(root=ROOT):
    for folder in (root / 'mobile', *(root / 'plugins').glob('*/mobile')):
        for source in folder.rglob('*'):
            if source.is_file() and source.suffix in ('.kt', '.java') and 'build' not in source.relative_to(folder).parts:
                content = source.read_text()
                if 'android.webkit.WebView' in content or 'android.webkit.*' in content:
                    raise CheckError('Native source references android.webkit.WebView: ' + str(source.relative_to(root)))


def build(env, reports, *, project=MOBILE):
    verify_native_sources()
    rust_failure = None
    if project.ring:
        cargo = shutil.which('cargo', path=env['PATH'])
        if not cargo:
            raise CheckError('Cargo is absent; run all --bootstrap')
        try:
            rust_checks(cargo, env, reports)
        except CheckError as error:
            # Collect Kotlin/JNI failures too, retaining the Rust failed verdict.
            rust_failure = error
    sdk = Path(env['ANDROID_HOME'])
    if project.ring and not (sdk / f'ndk/{NDK}/source.properties').is_file():
        raise CheckError(f'Pinned NDK {NDK} is absent; run all --bootstrap')
    java = run(['java', '-version'], env=env, capture=True, timeout=30)
    if not re.search(r'version "21\.', java.stderr + java.stdout):
        raise CheckError('Android CI requires JDK 21')
    try:
        tasks = [f':{project.module}:{task}' for task in
                 ('testDebugUnitTest', 'lintDebug', 'assembleDebug', 'assembleDebugAndroidTest')]
        if project.ring:
            tasks.extend((f':{project.module}:assembleRelease', '-PassistantOrigin=https://assistant.invalid',
                          '-PassistantDevOrigin=http://127.0.0.1:8766'))
        if project is MOBILE:
            tasks.append(':androidApp:verifyNativeRuntime')
        run([project.root / 'gradlew', *tasks, '--dependency-verification', 'strict',
             '--console=plain', '--no-daemon', '--build-cache'],
            env=env, cwd=project.root)
    except subprocess.SubprocessError as error:
        if rust_failure is not None:
            raise CheckError(f'{rust_failure}; Android build failed: {error}') from error
        raise
    if rust_failure is not None:
        raise rust_failure


def unit_tests(env, filters, *, project=MOBILE):
    modules = [project.module]
    platform_sources = project.root / 'androidPlatform/src'
    if any(source.suffix in ('.kt', '.java')
           for source_set in ('test', 'testDebug')
           for source in (platform_sources / source_set).rglob('*')):
        modules.append('androidPlatform')
    tasks = []
    for module in modules:
        tasks.append(f':{module}:testDebugUnitTest')
        for pattern in filters:
            tasks.extend(('--tests', pattern))
    result_directories = [project.root / module / 'build/test-results/testDebugUnitTest'
                          for module in modules]
    # A successful module must not hide another module's stale filtered results.
    # Missing outputs are executed again or restored from Gradle's matching cache.
    for directory in result_directories:
        if directory.exists():
            shutil.rmtree(directory)
    with tempfile.TemporaryDirectory(prefix='android-unit-') as directory:
        init_script = Path(directory) / 'targeted-unit.gradle'
        init_script.write_text('''allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        if (name == 'testDebugUnitTest') {
            filter.setFailOnNoMatchingTests(false)
        }
    }
}
''')
        run([project.root / 'gradlew', *tasks, '--init-script', init_script,
             '--dependency-verification', 'strict', '--console=plain', '--no-daemon', '--build-cache'],
            env=env, cwd=project.root)
    matched = sum(len(ET.parse(report).getroot().findall('testcase'))
                  for directory in result_directories for report in directory.glob('TEST-*.xml'))
    if not matched:
        raise CheckError('No Android unit tests matched the requested filters')
    print(f'Android unit tests matched: {matched}', flush=True)


def build_instrumentation(env, *, project=MOBILE):
    run([project.root / 'gradlew', f':{project.module}:assembleDebug',
         f':{project.module}:assembleDebugAndroidTest', '--dependency-verification', 'strict',
         '--console=plain', '--no-daemon', '--build-cache'], env=env, cwd=project.root)


def instrumentation_class(value):
    name, separator, method = value.partition('#')
    if (not re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*', name)
            or (separator and not re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*', method))):
        raise argparse.ArgumentTypeError('Expected an instrumentation Class or Class#method')
    return value


def select_classes(base, *, project=MOBILE, root=ROOT):
    """Select PR instrumentation from Git pathspecs, without preparing Android tooling."""
    def names(command):
        output = subprocess.check_output(command, cwd=root)
        return set(output.decode('utf-8', errors='surrogateescape').rstrip('\0').split('\0')) - {''}

    changed = names(['sh', '.forgejo/lanes.sh', 'changed', 'mobile_android', base])
    if not changed:
        return 'all'

    def matching(paths):
        return names(['git', 'diff', '--name-only', '-z', '--no-renames', base, 'HEAD', '--', *paths])

    if changed & matching(project.configuration['full_suite_paths']):
        return 'all'
    selected = {'assistant.mobile.android.NativeSmokeTest',
                'assistant.mobile.android.NativeShellSmokeTest'}
    covered = set()
    for name, paths in project.configuration['smoke_classes'].items():
        matches = changed & matching(paths)
        if matches:
            selected.add(name)
            covered.update(matches)
    if changed - covered or selected == set(project.smoke_classes):
        return 'all'
    return ','.join(name for name in project.smoke_classes if name in selected)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('all', 'build', 'smoke', 'unit', 'verify', 'dependencies', 'upgrade', 'select'))
    parser.add_argument('--project', choices=PROJECTS, default='mobile', help='Independent Gradle/APK root')
    parser.add_argument('--bootstrap', action='store_true', help='Provision official tooling in a private cache')
    parser.add_argument('--cache', type=Path, default=Path(os.environ.get('ANDROID_CI_CACHE') or
                                                        Path.home() / '.cache/device-assistant-android-ci'))
    parser.add_argument('--release', type=Path, help='Signed release directory for synthetic shell upgrade acceptance')
    parser.add_argument('--apk', type=Path, help='APK for verify (requires Android SDK zipalign)')
    parser.add_argument('--reports', type=Path, help='Defaults to the selected module reports/native-ci')
    parser.add_argument('--tests', action='append', help='Gradle --tests filter for unit (repeatable)')
    parser.add_argument('--base', help='Merge-base commit for select (requires only Git and Python)')
    parser.add_argument('--class', dest='classes', type=instrumentation_class, action='append',
                        help='Instrumentation Class or Class#method for all or smoke (repeatable)')
    args = parser.parse_args(argv)
    if args.tests is not None and args.action != 'unit':
        parser.error('--tests is only accepted for unit')
    if args.action == 'unit' and (not args.tests or any(not pattern.strip() for pattern in args.tests)):
        parser.error('unit requires nonempty --tests filters')
    if args.classes is not None and args.action not in ('all', 'smoke'):
        parser.error('--class is only accepted for all or smoke')
    if args.action == 'select' and not args.base:
        parser.error('select requires --base')
    if args.base is not None and args.action != 'select':
        parser.error('--base is only accepted for select')
    if args.bootstrap and args.action not in ('all', 'dependencies', 'upgrade', 'unit') and not args.classes:
        parser.error('--bootstrap is only accepted for all, dependencies, upgrade, unit or smoke --class')
    if args.action == 'verify' and args.apk is None:
        parser.error('verify requires --apk')
    if args.action == 'upgrade' and args.release is None:
        parser.error('upgrade requires --release')
    return args


def main():
    args = parse_args()
    project = PROJECTS[args.project]
    if args.action == 'select':
        print(select_classes(args.base, project=project))
        return 0
    args.reports = args.reports or project.reports
    args.reports.mkdir(parents=True, exist_ok=True)
    outcome = {'passed': False, 'project': args.project, 'rust': RUST if project.ring else None,
               'ndk': NDK if project.ring else None}
    try:
        if args.action in ('all', 'smoke', 'upgrade'):
            job_deadline()
        env = tool_environment(args.cache, bootstrap=args.bootstrap, project=project)
        zipalign = Path(env['ANDROID_HOME']) / f'build-tools/{BUILD_TOOLS}/zipalign'
        if args.action == 'upgrade':
            outcome['upgrade'] = upgrade(args.release, env, args.reports, args.cache)
        if args.action == 'dependencies':
            # Exercise the actual tasks too: aapt2, lint and compiler plugins
            # may resolve detached configurations absent from resolveAndLockAll.
            # This only proposes reviewed hashes/locks; normal builds stay strict.
            refresh = ['--write-locks', '--write-verification-metadata', 'sha256',
                       '--no-daemon', '--console=plain']
            # Persist resolved locks even if a newly added source fails compilation afterwards.
            run([project.root / 'gradlew', 'resolveAndLockAll', *refresh], env=env, cwd=project.root)
            if project is MOBILE:
                tasks = [':core:jvmTest', ':plugin-api:jvmTest', ':plugin-trading:jvmTest', ':androidApp:testDebugUnitTest',
                         ':androidApp:lintDebug', ':androidApp:assembleDebug',
                         ':androidApp:assembleDebugAndroidTest', ':androidApp:verifyNativeRuntime']
                run([project.root / 'gradlew', *tasks, *refresh], env=env, cwd=project.root)
        if args.action in ('all', 'build'):
            if args.bootstrap:
                prepare_rust_cache(args.cache, ci=env.get('CI', '').lower() == 'true')
            build(env, args.reports, project=project)
        if args.action == 'unit':
            unit_tests(env, args.tests, project=project)
        if args.classes and args.action == 'smoke':
            build_instrumentation(env, project=project)
        if args.action in ('all', 'build', 'verify'):
            apks = [args.apk] if args.action == 'verify' else [project.apk()]
            if args.action != 'verify' and project.ring:
                apks.append(project.apk('release'))
            outcome['apks'] = [verify_apk(apk, zipalign, env=env, require_ring=project.ring,
                                        abis=project.abis) for apk in apks]
        if args.action in ('all', 'smoke'):
            if args.bootstrap:
                prepare_smoke_disk_space(env, args.cache)
            outcome['smoke'] = smoke(env, args.reports, project=project, classes=args.classes)
        outcome['passed'] = True
        outcome['action'] = args.action
        return 0
    except (CheckError, subprocess.SubprocessError, OSError, ValueError, zipfile.BadZipFile) as error:
        outcome['error'] = str(error)
        print(f'Android native CI failed: {error}', flush=True)
        return 1
    finally:
        (args.reports / 'native-ci.json').write_text(json.dumps(outcome, indent=2) + '\n')


if __name__ == '__main__':
    raise SystemExit(main())
