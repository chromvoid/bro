#!/usr/bin/env python3
"""Prepare pinned mobile VAD assets at build time; model weights stay outside Git.

The app never fetches a model. Each build verifies its cache and copies the
original Silero ONNX bytes together with their committed MIT license and the
original ONNX Runtime license and third-party notices.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'server/models/silero.json'
LICENSE = ROOT / 'server/models/SILERO-LICENSE'
RUNTIME_LICENSE = ROOT / 'shell/licenses/ONNXRUNTIME-LICENSE.txt'
RUNTIME_NOTICES = ROOT / 'shell/licenses/ONNXRUNTIME-ThirdPartyNotices.txt'
DEFAULT_CACHE = ROOT / 'runtime/native-vad'
MODEL_NAME, MODEL_VERSION, MODEL_FILE = 'silero-vad', '6.2.1', 'silero-v6.2.1.onnx'
MAX_MODEL_BYTES, MAX_MANIFEST_BYTES, MAX_LICENSE_BYTES = 8 * 1024 * 1024, 16384, 65536
MAX_NOTICE_BYTES = 1024 * 1024
CHUNK_BYTES, REQUEST_TIMEOUT, DOWNLOAD_SECONDS = 65536, 20, 30


class AssetError(ValueError):
    """A readable, fail-closed build error, never an invitation to run unverified bytes."""


def _bounded_file(path, limit, label):
    try:
        with Path(path).open('rb') as source:
            data = source.read(limit + 1)
    except OSError as error:
        raise AssetError(f'Cannot read {label}: {error}') from error
    if not data or len(data) > limit:
        raise AssetError(f'{label} must be nonempty and at most {limit} bytes.')
    return data


def read_manifest(path):
    try:
        value = json.loads(_bounded_file(path, MAX_MANIFEST_BYTES, 'Silero manifest'))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise AssetError('Invalid Silero manifest JSON.') from error
    if not isinstance(value, dict):
        raise AssetError('Silero manifest must be an object.')
    if (value.get('name') != MODEL_NAME or value.get('version') != MODEL_VERSION
            or value.get('file') != MODEL_FILE):
        raise AssetError(f'Expected the pinned {MODEL_NAME} {MODEL_VERSION} manifest and {MODEL_FILE} filename.')
    commit, digest = value.get('commit'), value.get('sha256')
    if not isinstance(commit, str) or not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise AssetError('Silero manifest requires a full lowercase upstream commit.')
    if not isinstance(digest, str) or not re.fullmatch(r'[0-9a-f]{64}', digest):
        raise AssetError('Silero manifest requires a lowercase SHA-256.')
    if value.get('source') != f'https://github.com/snakers4/silero-vad/tree/{commit}':
        raise AssetError('Silero source must identify the same pinned upstream commit.')
    return value


def _digest(path):
    digest, size = hashlib.sha256(), 0
    with Path(path).open('rb') as source:
        while chunk := source.read(CHUNK_BYTES):
            size += len(chunk)
            if size > MAX_MODEL_BYTES:
                raise AssetError(f'Silero model exceeds {MAX_MODEL_BYTES} bytes.')
            digest.update(chunk)
    return digest.hexdigest(), size


def _verified(path, expected):
    try:
        if path.is_symlink() or not path.is_file():
            return False
        digest, size = _digest(path)
        return 0 < size <= MAX_MODEL_BYTES and digest == expected
    except (OSError, AssetError):
        return False


def _download(destination, manifest, opener):
    url = ('https://raw.githubusercontent.com/snakers4/silero-vad/' + manifest['commit'] +
           '/src/silero_vad/data/silero_vad.onnx')
    temporary = None
    try:
        deadline = time.monotonic() + DOWNLOAD_SECONDS
        digest, size = hashlib.sha256(), 0
        with opener(url, timeout=REQUEST_TIMEOUT) as response:
            declared = getattr(response, 'headers', {}).get('Content-Length')
            if declared is not None:
                try:
                    declared = int(declared)
                except (TypeError, ValueError):
                    raise AssetError('Invalid model download Content-Length.') from None
                if not 0 < declared <= MAX_MODEL_BYTES:
                    raise AssetError(f'Silero model download exceeds the {MAX_MODEL_BYTES}-byte bound.')
            descriptor, name = tempfile.mkstemp(prefix='.silero-', suffix='.partial', dir=destination.parent)
            temporary = Path(name)
            with os.fdopen(descriptor, 'wb') as output:
                while True:
                    if time.monotonic() >= deadline:
                        raise AssetError('Silero model download exceeded its time limit.')
                    chunk = response.read(CHUNK_BYTES)
                    if time.monotonic() >= deadline:
                        raise AssetError('Silero model download exceeded its time limit.')
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > MAX_MODEL_BYTES:
                        raise AssetError(f'Silero model download exceeds the {MAX_MODEL_BYTES}-byte bound.')
                    digest.update(chunk)
                    output.write(chunk)
                if not size or digest.hexdigest() != manifest['sha256']:
                    raise AssetError('Downloaded Silero model failed SHA-256 verification; no asset was installed.')
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, destination)
            temporary = None
    except (OSError, urllib.error.URLError) as error:
        raise AssetError('No verified Silero model cache is available and the pinned model download failed. '
                         f'Prepare the model once while online or supply a valid --cache-dir. ({error})') from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _copy_verified(source, destination, expected):
    temporary = None
    try:
        descriptor, name = tempfile.mkstemp(prefix='.silero-', suffix='.partial', dir=destination.parent)
        temporary = Path(name)
        digest, size = hashlib.sha256(), 0
        with os.fdopen(descriptor, 'wb') as output, source.open('rb') as input_file:
            while chunk := input_file.read(CHUNK_BYTES):
                size += len(chunk)
                if size > MAX_MODEL_BYTES:
                    raise AssetError(f'Silero model exceeds {MAX_MODEL_BYTES} bytes during copy.')
                digest.update(chunk)
                output.write(chunk)
            if not size or digest.hexdigest() != expected:
                raise AssetError('Cached Silero model changed during copying; no asset was installed.')
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
        temporary = None
        if not _verified(destination, expected):
            raise AssetError('Prepared Silero model failed final SHA-256 verification.')
        return size
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _write_text_asset(destination, data, limit):
    descriptor, name = tempfile.mkstemp(prefix='.silero-', suffix='.partial', dir=destination.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        label = f'prepared {destination.name}'
        if _bounded_file(temporary, limit, label) != data:
            raise AssetError(f'{label} failed copy verification.')
        os.replace(temporary, destination)
        if _bounded_file(destination, limit, label) != data:
            raise AssetError(f'{label} failed final copy verification.')
    finally:
        temporary.unlink(missing_ok=True)


def prepare(output_dir, cache_dir=None, *, manifest_path=MANIFEST, license_path=LICENSE,
            runtime_license_path=RUNTIME_LICENSE, runtime_notices_path=RUNTIME_NOTICES,
            opener=urllib.request.urlopen):
    manifest = read_manifest(manifest_path)
    text_assets = [
        ('SILERO-LICENSE', _bounded_file(license_path, MAX_LICENSE_BYTES, 'Silero license'), MAX_LICENSE_BYTES),
        ('ONNXRUNTIME-LICENSE.txt', _bounded_file(runtime_license_path, MAX_NOTICE_BYTES, 'ONNX Runtime license'),
         MAX_NOTICE_BYTES),
        ('ONNXRUNTIME-ThirdPartyNotices.txt',
         _bounded_file(runtime_notices_path, MAX_NOTICE_BYTES, 'ONNX Runtime third-party notices'), MAX_NOTICE_BYTES),
    ]
    output_dir = Path(output_dir).resolve()
    cache_dir = Path(cache_dir if cache_dir is not None else DEFAULT_CACHE).resolve()
    try:
        output_dir.mkdir(parents=True, exist_ok=True)
        cache_dir.mkdir(parents=True, exist_ok=True)
        cached, output = cache_dir / MODEL_FILE, output_dir / MODEL_FILE
        if not _verified(cached, manifest['sha256']):
            _download(cached, manifest, opener)
        if not _verified(cached, manifest['sha256']):
            raise AssetError('Silero model cache failed SHA-256 verification.')
        size = _copy_verified(cached, output, manifest['sha256'])
        for filename, data, limit in text_assets:
            _write_text_asset(output_dir / filename, data, limit)
        return {'file': MODEL_FILE, 'version': MODEL_VERSION, 'commit': manifest['commit'],
                'sha256': manifest['sha256'], 'bytes': size, 'output_dir': str(output_dir)}
    except OSError as error:
        raise AssetError(f'Cannot prepare verified Silero model assets: {error}') from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True, help='Generated native bundle asset directory')
    parser.add_argument('--cache-dir', type=Path, help='Verified model cache (default: ignored runtime/native-vad)')
    args = parser.parse_args()
    try:
        result = prepare(args.output_dir, args.cache_dir)
    except AssetError as error:
        parser.exit(1, f'VAD assets unavailable: {error}\n')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
