"""Fetch the one pinned CMU capture used by the isolated InBody preview.

Downloads public data only. Never crawls the collection or changes the character.
Uses curl's system trust store without disabling certificate verification.
"""
import argparse
import hashlib
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
ORIGIN = 'https://mocap.cs.cmu.edu'
CAPTURE = {
    '79.asf': 'aa7fb254b0e7b9155df59e0f0cf76cbe34c476c6f4b3f015e63d5ae170d2fe2d',
    '79_94.amc': '55f03143db0e54931de9db95bbc008f839d5f196642da12e7f3d3457812c3cd0',
    '79_94.c3d': 'fa98a5f4e7f2898c185f8bae221e61f05754a28ef137b1cbd0a4c6d0715f5c2e',
}
VIDEO_HASH = 'e3ac6c9ef9c3d5d34bc1d29934e6f88f71e6323363249fa7c0a60c27d17c69bd'


def fetch(destination, url, expected_hash=None):
    if destination.exists():
        if expected_hash and hashlib.sha256(destination.read_bytes()).hexdigest() != expected_hash:
            raise RuntimeError(f'Existing file differs from the pinned capture: {destination}')
        print(f'Present: {destination.name}')
        return
    temporary = destination.with_suffix(destination.suffix + '.part')
    try:
        subprocess.run([
            'curl', '--fail', '--location', '--silent', '--show-error',
            '--proto', '=https', '--proto-redir', '=https',
            '--max-time', '60', '--max-filesize', '8000000',
            '--output', str(temporary), url,
        ], check=True)
        data = temporary.read_bytes()
        if not data or (expected_hash and hashlib.sha256(data).hexdigest() != expected_hash):
            raise RuntimeError(f'Download differs from the pinned capture: {destination.name}')
        temporary.replace(destination)
        print(f'Downloaded: {destination.name} ({len(data)} bytes)')
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'runtime/inbody-character/cmu/source')
    parser.add_argument('--with-video', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for name, checksum in CAPTURE.items():
        fetch(args.output / name, f'{ORIGIN}/subjects/79/{name}', checksum)
    fetch(args.output / 'cmu-license.html', f'{ORIGIN}/')
    fetch(args.output / 'catalog79.html', f'{ORIGIN}/search.php?subjectnumber=79')
    if args.with_video:
        fetch(args.output / '79_94.mpg', f'{ORIGIN}/subjects/79/79_94.mpg', VIDEO_HASH)


if __name__ == '__main__':
    main()
