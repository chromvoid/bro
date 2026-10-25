#!/usr/bin/env python3
"""Signed direct Android APK and Google Play AAB for one HTTPS origin.

The Mac mini runs it from .forgejo/workflows/android-release.yml. The key
comes from the environment (ANDROID_SIGNING_*), lives only in a private temporary file while
apksigner reads it, and its passwords reach apksigner through the environment, never argv.
Nothing here selects or installs on a phone. Without a key the unsigned build is still checked,
then the run fails: an APK the phone cannot install as an update is never published. The version
code grows with the commit time, so the phones' own update (deploy/production/android_publish.py)
always moves forward, and a key other than the one pinned in mobile/release-certificate.sha256
is refused before anything leaves the runner.
"""
from __future__ import annotations

import argparse
import base64
import binascii
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
import urllib.request
import zipfile

SPEC = importlib.util.spec_from_file_location('android_ci', Path(__file__).resolve().with_name('android_ci.py'))
ci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci)
CheckError, ROOT = ci.CheckError, ci.ROOT

SIGNING = ('ANDROID_SIGNING_KEYSTORE', 'ANDROID_SIGNING_STORE_PASSWORD', 'ANDROID_SIGNING_KEY_ALIAS',
           'ANDROID_SIGNING_KEY_PASSWORD')
PACKAGE = 'dev.assistant.shell'
PIN = ROOT / 'mobile/release-certificate.sha256'
EPOCH = 1767225600  # 2026-01-01T00:00:00Z: version codes count minutes from here


def production_release(environ):
    """Last published primary artifact, from a successful main release's successful publish job.

    The CI repository token reads release metadata only. No family session or production SSH is
    needed. Historical artifacts may contain multiple APKs; only the primary manifest is read.
    Missing/expired artifacts fail closed instead of treating an unknown production code as zero.
    """
    api = environ.get('GITHUB_API_URL', '').rstrip('/')
    repository, token = environ.get('GITHUB_REPOSITORY'), environ.get('GITHUB_TOKEN')
    if not api or not repository or not token:
        raise CheckError('Production version check needs the CI repository API and token')
    base = f'{api}/repos/{repository}'

    def fetch(path, *, binary=False):
        request = urllib.request.Request(base + path, headers={'Authorization': f'token {token}'})
        with urllib.request.urlopen(request, timeout=120) as response:
            content = response.read()
        return content if binary else json.loads(content)

    page = 1
    while True:
        result = fetch(f'/actions/runs?page={page}&per_page=30')
        runs = result['workflow_runs']
        if not runs:
            break
        for run in runs:
            if (run.get('workflow_id') != 'android-release.yml' or run.get('prettyref') != 'main'
                    or run.get('status') != 'success'):
                continue
            jobs = fetch(f'/actions/runs/{run["id"]}/jobs')
            if not any(job.get('name') == 'publish' and job.get('status') == 'success' for job in jobs):
                continue
            artifacts = fetch(f'/actions/runs/{run["id"]}/artifacts')
            artifact = next((item for item in artifacts if item.get('name', '').startswith('android-release-')
                             and not item.get('expired')), None)
            if artifact is None:
                raise CheckError(f'Published release run {run["index_in_repo"]} has no retained artifact')
            with zipfile.ZipFile(io.BytesIO(fetch(f'/actions/artifacts/{artifact["id"]}/zip', binary=True))) as archive:
                for name in archive.namelist():
                    if Path(name).name != 'release.json':
                        continue
                    report = json.loads(archive.read(name))
                    if report.get('package') != PACKAGE:
                        continue
                    code = report.get('version_code')
                    if (not isinstance(code, int) or isinstance(code, bool) or not 1 < code <= 2_100_000_000
                            or report.get('commit') != run.get('commit_sha')
                            or report.get('certificate_sha256') != pinned()):
                        raise CheckError('Published production artifact has invalid identity, code or certificate')
                    return {'version_code': code, 'run': run['index_in_repo'], 'commit': report['commit']}
        if page * 30 >= result.get('total_count', page * 30):
            break
        page += 1
    raise CheckError('No successful published primary Android release was found')


def require_newer(code, production):
    if code <= production['version_code']:
        raise CheckError(f'VersionCode {code} must exceed production {production["version_code"]} '
                         f'(release run {production["run"]})')


def origin(value):
    """The server the app opens: an HTTPS origin, as the Gradle release build also insists."""
    parts = urlsplit(value or '')
    if (parts.scheme != 'https' or not parts.hostname or parts.username or parts.password
            or parts.path not in ('', '/') or parts.query or parts.fragment):
        raise CheckError('The release origin must be https://host[:port] without path, query or credentials')
    return f'https://{parts.netloc}'


def signing(environ):
    """The key from the environment; the keystore is base64 so a secret can hold it.

    A pasted secret keeps the line breaks of `base64 … | pbcopy` (and of wrapping encoders), so
    whitespace is dropped before the strict decode."""
    missing = [name for name in SIGNING if not environ.get(name)]
    if missing:
        raise CheckError('Signing key is not configured; set ' + ', '.join(missing) +
                         ' (shell/README.md, «Боевой APK»). The unsigned build passed its checks.')
    try:
        keystore = base64.b64decode(''.join(environ['ANDROID_SIGNING_KEYSTORE'].split()), validate=True)
    except (binascii.Error, ValueError):
        raise CheckError('ANDROID_SIGNING_KEYSTORE must be the keystore file in base64') from None
    if not keystore:
        raise CheckError('ANDROID_SIGNING_KEYSTORE is empty')
    return keystore, environ['ANDROID_SIGNING_KEY_ALIAS']


def version(commit, committed):
    """Version code and name of a commit: minutes since 2026 by its commit time, so main only grows."""
    code = (committed - EPOCH) // 60
    if not 1 < code <= 2_100_000_000:
        raise CheckError('The commit time gives no valid version code')
    return code, f'{datetime.fromtimestamp(committed, timezone.utc):%Y.%m.%d}-{commit[:8]}'


def head():
    """The commit being built and its commit time (a shallow checkout has both)."""
    output = subprocess.run(['git', 'log', '-1', '--format=%H %ct'], cwd=ROOT, text=True, capture_output=True)
    fields = output.stdout.split()
    if output.returncode or len(fields) != 2:
        raise CheckError('The release needs a git checkout of the commit it builds')
    return fields[0], int(fields[1])


def pinned():
    """The certificate the phones already trust; missing trust is a release failure."""
    if not PIN.is_file():
        raise CheckError(f'{PIN.relative_to(ROOT)} is missing')
    words = PIN.read_text().split()
    value = words[0] if words else ''
    if len(value) != 64 or set(value) - set('0123456789abcdef'):
        raise CheckError(f'{PIN.relative_to(ROOT)} must start with a lowercase SHA-256')
    return value


def identity(aapt2, apk, env):
    """Package, version code and name the APK itself declares (aapt2's first badging line)."""
    output = ci.run([aapt2, 'dump', 'badging', apk], env=env, capture=True).stdout
    if 'application-debuggable' in output.splitlines():
        raise CheckError('The release APK must not be debuggable')
    line = output.partition('\n')[0]
    kind, _, fields = line.partition(' ')
    found = dict(field.partition('=')[::2] for field in fields.split()) if kind == 'package:' else {}
    return tuple(found.get(key, '').strip("'") or None for key in ('name', 'versionCode', 'versionName'))


def verify_origin_metadata(aapt2, apk, env, target, previous):
    """Check the exact application metadata consumed by AndroidNativeOrigins, independent of R8."""
    output = ci.run([aapt2, 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk],
                    env=env, capture=True).stdout
    stack, metadata = [], []
    attributes = None
    for line in output.splitlines():
        content = line.lstrip()
        indent = len(line) - len(content)
        if content.startswith('E: '):
            while stack and stack[-1][0] >= indent:
                stack.pop()
            element = content[3:].partition(' ')[0]
            attributes = None
            if element == 'meta-data' and [name for _, name in stack] == ['manifest', 'application']:
                attributes = {}
                metadata.append(attributes)
            stack.append((indent, element))
        elif attributes is not None:
            attribute = re.fullmatch(r'A: http://schemas\.android\.com/apk/res/android:(name|value)'
                                     r'\(0x[0-9a-fA-F]+\)="([^"]*)"'
                                     r'(?: \(Raw: "[^"]*"\))?', content)
            if attribute:
                attributes[attribute[1]] = attribute[2]
    for name, value in (('assistant.origin', target), ('assistant.previous_origin', previous or '')):
        if [item.get('value') for item in metadata if item.get('name') == name] != [value]:
            raise CheckError(f'The release manifest must declare {name} exactly as {value!r}')


def certificate(apksigner, apk, env):
    """SHA-256 of the single signer: what decides whether the phone accepts it as an update."""
    output = ci.run([apksigner, 'verify', '--print-certs', '--verbose', apk], env=env, capture=True).stdout
    digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})', output)
    if len(digests) != 1 or 'Verified using v2 scheme (APK Signature Scheme v2): true' not in output:
        raise CheckError('The APK must carry exactly one v2 signer')
    return digests[0]


def sign_apk(unsigned_apk, apk, tools, env, environ=os.environ):
    """Sign a CI APK with the existing installation key, without putting secrets in argv."""
    keystore, alias = signing(environ)
    with tempfile.TemporaryDirectory(prefix='android-release-') as private:
        path = Path(private) / 'release.keystore'
        path.touch(mode=0o600)
        path.write_bytes(keystore)
        sign_env = {**env, 'ANDROID_SIGNING_STORE_PASSWORD': environ['ANDROID_SIGNING_STORE_PASSWORD'],
                    'ANDROID_SIGNING_KEY_PASSWORD': environ['ANDROID_SIGNING_KEY_PASSWORD']}
        ci.run([tools / 'apksigner', 'sign', '--ks', path, '--ks-key-alias', alias,
                '--ks-pass', 'env:ANDROID_SIGNING_STORE_PASSWORD', '--key-pass', 'env:ANDROID_SIGNING_KEY_PASSWORD',
                '--v4-signing-enabled', 'false', '--out', apk, unsigned_apk], env=sign_env)


def sign_bundle(source, bundle, env, environ=os.environ):
    """AAB uses a JAR signature, with the same pinned certificate as direct installation."""
    keystore, alias = signing(environ)
    java = Path(env['JAVA_HOME']) / 'bin'
    with tempfile.TemporaryDirectory(prefix='android-bundle-') as private:
        path = Path(private) / 'release.keystore'
        path.touch(mode=0o600)
        path.write_bytes(keystore)
        sign_env = {**env, 'ANDROID_SIGNING_STORE_PASSWORD': environ['ANDROID_SIGNING_STORE_PASSWORD'],
                    'ANDROID_SIGNING_KEY_PASSWORD': environ['ANDROID_SIGNING_KEY_PASSWORD']}
        ci.run([java / 'jarsigner', '-keystore', path, '-storepass:env', 'ANDROID_SIGNING_STORE_PASSWORD',
                '-keypass:env', 'ANDROID_SIGNING_KEY_PASSWORD', '-signedjar', bundle, source, alias], env=sign_env)
    verified = ci.run([java / 'jarsigner', '-J-Duser.language=en', '-J-Duser.country=US',
                       '-verify', bundle], env=env, capture=True).stdout
    if 'jar verified.' not in verified or 'unsigned entries' in verified:
        raise CheckError('The bundle must have a valid signature covering every entry')
    cert = ci.run([java / 'keytool', '-J-Duser.language=en', '-J-Duser.country=US',
                   '-printcert', '-jarfile', bundle], env=env, capture=True).stdout
    fingerprints = re.findall(r'SHA256: ([0-9A-F:]+)', cert)
    if len(fingerprints) != 1 or fingerprints[0].replace(':', '').lower() != pinned():
        raise CheckError('The bundle must carry the pinned release certificate')


def verify_play(apk, bundle, tools, env, target, previous, code, name):
    """Check the Play variant's native libraries, identity and restricted permissions."""
    ci.verify_apk(apk, tools / 'zipalign', env=env, require_ring=False, abis=ci.MOBILE.abis)
    verify_origin_metadata(tools / 'aapt2', apk, env, target, previous)
    if identity(tools / 'aapt2', apk, env) != (PACKAGE, str(code), name):
        raise CheckError('The Play variant has an unexpected package or version')
    permissions = ci.run([tools / 'aapt2', 'dump', 'permissions', apk], env=env, capture=True).stdout
    for permission in ('REQUEST_INSTALL_PACKAGES', 'READ_EXTERNAL_STORAGE', 'READ_MEDIA_IMAGES',
                       'READ_MEDIA_VISUAL_USER_SELECTED', 'USE_EXACT_ALARM', 'FOREGROUND_SERVICE_CONNECTED_DEVICE',
                       'FOREGROUND_SERVICE_LOCATION', 'ACTIVITY_RECOGNITION'):
        if f"android.permission.{permission}'" in permissions:
            raise CheckError(f'The Play variant must not request {permission}')
    manifest = ci.run([tools / 'aapt2', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk],
                      env=env, capture=True).stdout
    if 'BIND_ACCESSIBILITY_SERVICE' in manifest or 'assistant.mobile.platform.ring.' in manifest:
        raise CheckError('The Play variant must not declare ring components or accessibility access')
    with zipfile.ZipFile(apk) as archive:
        if any(name.endswith('/libherzring.so') for name in archive.namelist()):
            raise CheckError('The Play APK must not contain the ring native library')
    with zipfile.ZipFile(bundle) as archive:
        if any(name.endswith('/libherzring.so') for name in archive.namelist()):
            raise CheckError('The Play bundle must not contain the ring native library')
        for required in ('BundleConfig.pb', 'base/manifest/AndroidManifest.xml', 'base/dex/classes.dex'):
            if required not in archive.namelist():
                raise CheckError(f'The Android App Bundle is missing {required}')


def build(args, environ=os.environ):
    project = ci.MOBILE
    unsigned_apk = project.apk('release')
    target = origin(args.origin or environ.get('ASSISTANT_ORIGIN'))
    previous_value = (args.previous_origin if args.previous_origin is not None
                      else environ.get('ASSISTANT_PREVIOUS_ORIGIN'))
    previous = origin(previous_value) if previous_value else None
    if previous:
        target_url, previous_url = urlsplit(target), urlsplit(previous)
        try:
            target_port, previous_port = target_url.port, previous_url.port
        except ValueError:
            raise CheckError('Release origins must have valid ports') from None
        if (previous_url.hostname, previous_port if previous_port is not None else 443) == \
                (target_url.hostname, target_port if target_port is not None else 443):
            raise CheckError('The previous origin must differ from the release origin')
    commit, committed = head()
    code, name = version(commit, committed)
    expected = pinned()
    production = production_release(environ) if args.check_production else None
    if production:
        require_newer(code, production)
        print(f'VersionCode {code} > production {production["version_code"]} '
              f'(release run {production["run"]})', flush=True)
    env = ci.tool_environment(args.cache, bootstrap=args.bootstrap, project=project)
    tools = Path(env['ANDROID_HOME']) / f'build-tools/{ci.BUILD_TOOLS}'
    ci.run([project.root / 'gradlew', f':{project.module}:assembleRelease',
            f':{project.module}:assemblePlay', f':{project.module}:bundlePlay',
            f':{project.module}:verifyPlayOwnAndroidClasses', f'-PassistantOrigin={target}',
            f'-PassistantPreviousOrigin={previous or ""}',
            f'-PassistantVersionCode={code}', f'-PassistantVersionName={name}',
            '--console=plain', '--no-daemon'], env=env, cwd=project.root)
    unsigned = ci.verify_apk(unsigned_apk, tools / 'zipalign', env=env, require_ring=project.ring, abis=project.abis)
    verify_origin_metadata(tools / 'aapt2', unsigned_apk, env, target, previous)
    # Check credentials before creating an output directory for a release.
    signing(environ)
    args.out.mkdir(parents=True, exist_ok=True)
    apk = args.out / f'bro-{commit[:8]}.apk'
    sign_apk(unsigned_apk, apk, tools, env, environ)
    signed = ci.verify_apk(apk, tools / 'zipalign', env=env, require_ring=project.ring, abis=project.abis)
    if identity(tools / 'aapt2', apk, env) != (PACKAGE, str(code), name):
        raise CheckError(f'The APK must declare {PACKAGE} version {code} ({name})')
    signer = certificate(tools / 'apksigner', apk, env)
    if expected and signer != expected:
        raise CheckError(f'Signed with {signer}, not the pinned key {expected}: phones would refuse it as an update')
    bundle = args.out / f'bro-{commit[:8]}-play.aab'
    outputs = project.root / project.module / 'build/outputs'
    source_bundle = outputs / f'bundle/play/{project.module}-play.aab'
    play_apk = outputs / f'apk/play/{project.module}-play-unsigned.apk'
    verify_play(play_apk, source_bundle, tools, env, target, previous, code, name)
    sign_bundle(source_bundle, bundle, env, environ)
    bundle_digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
    (args.out / (bundle.name + '.sha256')).write_text(f'{bundle_digest}  {bundle.name}\n')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    report = {'apk': apk.name, 'sha256': digest, 'size': apk.stat().st_size, 'origin': target,
              'previous_origin': previous, 'commit': commit,
              'package': PACKAGE, 'version_code': code, 'version_name': name, 'certificate_sha256': signer,
              'libraries': len(signed['libraries']), 'unsigned_libraries': len(unsigned['libraries'])}
    report['play_bundle'] = {'file': bundle.name, 'sha256': bundle_digest, 'size': bundle.stat().st_size,
                             'certificate_sha256': signer}
    if production:
        report['production'] = production
    (args.out / (apk.name + '.sha256')).write_text(f'{digest}  {apk.name}\n')
    (args.out / 'release.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2), flush=True)
    return report


def validate_update(apk, report, installed_code, expected_pin, tools, env):
    """Check a signed artifact as an update, including its declared identity, not just its report."""
    if report.get('package') != PACKAGE:
        raise CheckError('The update package is not the expected package')
    code, name = report.get('version_code'), report.get('version_name')
    if (not isinstance(code, int) or isinstance(code, bool) or not installed_code < code <= 2_100_000_000
            or not isinstance(name, str) or not name):
        raise CheckError('The update versionCode must grow')
    if apk.stat().st_size != report.get('size'):
        raise CheckError('The update size does not match')
    if hashlib.sha256(apk.read_bytes()).hexdigest() != report.get('sha256'):
        raise CheckError('The update hash does not match')
    if identity(tools / 'aapt2', apk, env) != (PACKAGE, str(code), name):
        raise CheckError('The APK identity differs from its update manifest')
    if (not expected_pin or report.get('certificate_sha256') != expected_pin
            or certificate(tools / 'apksigner', apk, env) != expected_pin):
        raise CheckError('The update certificate does not match the shared pin')


def check_updates(args):
    """CI acceptance and refusal checks on the signed primary APK; never publish or install."""
    env = ci.tool_environment(args.cache, bootstrap=args.bootstrap, project=ci.MOBILE)
    tools = Path(env['ANDROID_HOME']) / f'build-tools/{ci.BUILD_TOOLS}'
    expected_pin = pinned()
    report = json.loads((args.out / 'release.json').read_text())
    if not isinstance(report, dict) or not isinstance(report.get('apk'), str):
        raise CheckError('The release report is not an update manifest')
    apk = args.out / report['apk']
    if apk.parent != args.out or apk.suffix != '.apk':
        raise CheckError('The release names no APK in its directory')
    validate_update(apk, report, 1, expected_pin, tools, env)
    checks = []
    cases = (
        ('wrong package with the same signature', {**report, 'package': 'dev.other'}, 1, expected_pin),
        ('wrong hash', {**report, 'sha256': '0' * 64}, 1, expected_pin),
        ('wrong size', {**report, 'size': report['size'] + 1}, 1, expected_pin),
        ('wrong certificate', {**report, 'certificate_sha256': '0' * 64}, 1, '0' * 64),
        ('equal versionCode', report, report['version_code'], expected_pin),
        ('downgrade', report, report['version_code'] + 1, expected_pin),
    )
    for label, manifest, installed, pin in cases:
        try:
            validate_update(apk, manifest, installed, pin, tools, env)
        except CheckError:
            checks.append(f'{PACKAGE}: refused {label}')
        else:
            raise CheckError(f'{PACKAGE}: accepted {label}')
    print('\n'.join(checks), flush=True)
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', help='https origin the app opens (default: ASSISTANT_ORIGIN)')
    parser.add_argument('--previous-origin', help='Previous https origin (default: ASSISTANT_PREVIOUS_ORIGIN)')
    parser.add_argument('--check-production', action='store_true',
                        help='Require a versionCode newer than the last published production CI artifact')
    parser.add_argument('--check-updates', action='store_true', help='Verify signed APK and update refusals')
    parser.add_argument('--out', type=Path, default=ROOT / 'build/android-release')
    parser.add_argument('--bootstrap', action='store_true', help='Provision official tooling in a private cache')
    parser.add_argument('--cache', type=Path, default=Path(os.environ.get('ANDROID_CI_CACHE') or
                                                        Path.home() / '.cache/device-assistant-android-ci'))
    args = parser.parse_args()
    try:
        if args.check_updates:
            check_updates(args)
        else:
            build(args)
        return 0
    except (CheckError, subprocess.SubprocessError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f'Android release failed: {error}', flush=True)
        return 1


if __name__ == '__main__':
    sys.exit(main())
