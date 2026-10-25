"""Compile three v3 resources sequentially on CI, then validate the full candidate.

Source archives and Blender workspaces stay in runtime/. Candidate fingerprints
are recorded by the versioned manifest; shipping additionally requires a review
approval with the exact model and geometry fingerprints shown to the owner.
"""
from __future__ import annotations

import argparse
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess

from PIL import Image

if __package__:
    from .inbody_character_validate import validate
    from .inbody_asset_contract import BODY_MORPHS, RESOURCE_KEYS, VERSION
    from .inbody_head_validate import PRODUCTION_COMMIT, retain_production_head, retain_production_rest, validate_head
    from .promote_inbody_rokoko_idle import prepare as prepare_motion
else:
    from inbody_character_validate import validate
    from inbody_asset_contract import BODY_MORPHS, RESOURCE_KEYS, VERSION
    from inbody_head_validate import PRODUCTION_COMMIT, retain_production_head, retain_production_rest, validate_head
    from promote_inbody_rokoko_idle import prepare as prepare_motion

ROOT = Path(__file__).resolve().parents[2]
AUTHORING_ORDER = ('male_spartan', 'male', 'female')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def public_poster(raw):
    """Remove authoring metadata, preserving every pixel and colour-profile byte."""
    if raw[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('Poster must be PNG')
    chunks, offset = [raw[:8]], 8
    while offset < len(raw):
        if offset + 12 > len(raw):
            raise ValueError('Truncated PNG chunk')
        size, kind = struct.unpack_from('>I4s', raw, offset)
        end = offset + size + 12
        if end > len(raw):
            raise ValueError('Truncated PNG payload')
        if kind not in {b'tEXt', b'zTXt', b'iTXt', b'eXIf'}:
            chunks.append(raw[offset:end])
        offset = end
    return b''.join(chunks)


def web_poster(raw):
    """Encode the public poster losslessly, retaining RGBA and its ICC profile."""
    with Image.open(BytesIO(public_poster(raw))) as image:
        output = BytesIO()
        image.save(output, 'WEBP', lossless=True, exact=True, method=6,
                   icc_profile=image.info.get('icc_profile', b''))
        return output.getvalue()


def approval_record(path, fingerprints):
    """A review accepts exact candidate bytes, never a shape-wide magic hash."""
    if path is None:
        return {'status': 'pending', 'resources': fingerprints}
    record = json.loads(path.read_text())
    if record.get('version') != VERSION or record.get('status') != 'accepted':
        raise ValueError('Art review must accept this version explicitly')
    if record.get('resources') != fingerprints:
        raise ValueError('Art review fingerprints differ from the candidate')
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / 'runtime/inbody-model/mpfb2')
    parser.add_argument('--rokoko-source', type=Path, required=True)
    parser.add_argument('--blender', default='blender')
    parser.add_argument('--workspace', type=Path, default=ROOT / 'runtime/inbody-character-v3')
    parser.add_argument('--output', type=Path, default=ROOT / 'runtime/inbody-character-v3/public')
    parser.add_argument('--reuse-existing', action='store_true')
    parser.add_argument('--review-approval', type=Path)
    parser.add_argument('--head-baseline-dir', type=Path, required=True,
                        help='Private CI inputs: the exact deployed male/female mannequin GLBs')
    args = parser.parse_args()
    env = dict(os.environ, BLENDER_USER_RESOURCES=str(args.workspace / 'blender'))
    manifest = {'version': VERSION, 'calibration': 'body-visual-v2',
                'upstream': 'https://github.com/makehumancommunity/mpfb2',
                'revision': 'afb9f530a7c2741dedb8df0ebae2e0b183caec21', 'license': 'CC0-1.0',
                'fat_percent_anchors': [10, 25, 40], 'muscle_index_anchors': [6, 9, 12],
                'fat_coordinates': [0, .5, 1], 'muscle_coordinates': [0, .5, 1],
                'grid': {'fat': [0, .2, .4, .6, .8, 1], 'muscle': [0, .25, .5, .75, 1]},
                'body_morph_names': BODY_MORPHS,
                'head_baseline_source': PRODUCTION_COMMIT,
                'clip_seconds': {'relaxed': 1, 'flex': 1, 'open': 1, 'scenario_calm': 10,
                                 'scenario_athletic': 14, 'scenario_stage': 18, 'rokoko_lookaround': 27},
                'compiler': {name: sha(ROOT / 'tools/assets' / name) for name in
                             ('export_inbody_character_prototype.py', 'inbody_character_motion.py',
                              'inbody_fat_volume.py',
                              'inbody_asset_contract.py', 'inbody_character_validate.py', 'inbody_head_validate.py',
                              'import_inbody_rokoko_idle.py', 'export_inbody_production.py')},
                'assets': {}}
    prepared, fingerprints = [], {}
    # Test the garment guard first; publish in the unchanged catalog order.
    assert set(AUTHORING_ORDER) == set(RESOURCE_KEYS)
    for key in AUTHORING_ORDER:
        template = 'female' if key == 'female' else 'male'
        character = 'spartan' if key == 'male_spartan' else 'sculpture'
        workspace = args.workspace / key
        assets, review = workspace / 'assets', workspace / 'review'
        baseline = args.head_baseline_dir / f'{template}.glb'
        if not args.reuse_existing:
            subprocess.run([args.blender, '-b', '--factory-startup', '--python-exit-code', '1',
                            '--python', str(ROOT / 'tools/assets/export_inbody_character_prototype.py'), '--',
                            '--source', str(args.source), '--template', template, '--character', character,
                            '--head-baseline', str(baseline),
                            '--output', str(assets), '--review', str(review)], check=True, env=env)
        export = json.loads((assets / 'manifest.json').read_text())
        original_export_sha = export['sha256']
        if args.reuse_existing:
            retention = retain_production_rest(assets / 'character.glb', baseline, template)
            export['production_rig_retention'] = retention
            export['production_head_retention'] = retain_production_head(assets / 'character.glb', baseline, template)
            export['sha256'], export['bytes'] = sha(assets / 'character.glb'), (assets / 'character.glb').stat().st_size
            (assets / 'manifest.json').write_text(json.dumps(export, indent=2) + '\n')
        if not args.reuse_existing or original_export_sha != export['sha256']:
            subprocess.run([args.blender, '-b', '--factory-startup', '--python-exit-code', '1',
                            '--python', str(ROOT / 'tools/assets/import_inbody_rokoko_idle.py'), '--',
                            '--source', str(args.rokoko_source), '--blend', str(review / 'character.blend'),
                            '--base', str(assets / 'character.glb'), '--output', str(assets)], check=True, env=env)
        checks = validate(assets / 'character.glb')
        export = json.loads((assets / 'manifest.json').read_text())
        head_baseline = validate_head(assets / 'character.glb', baseline, template)
        export['head_baseline'] = head_baseline
        (assets / 'manifest.json').write_text(json.dumps(export, indent=2) + '\n')
        motion = json.loads((assets / 'rokoko-lookaround.json').read_text())
        assert export['template'] == template and export['character_id'] == character
        assert motion['target']['resource_id'] == key
        assert motion['target']['base_sha256'] == checks['sha256']
        assert motion['sha256'] == sha(assets / 'rokoko-lookaround.glb')
        poster_bytes = public_poster((assets / 'poster.png').read_bytes())
        web_poster_bytes = web_poster(poster_bytes)
        fingerprints[key] = {name: checks[name] for name in ('sha256', 'geometry_material_fingerprint', 'held_pose_fingerprint')}
        manifest['assets'][key] = {
            'template': template, 'character_id': character,
            'model': f'{key}.glb', 'lookaround': f'{key}-lookaround.glb', 'poster': f'{key}.png',
            'sha256': checks['sha256'], 'bytes': checks['bytes'],
            'poster_sha256': hashlib.sha256(poster_bytes).hexdigest(),
            'lookaround_sha256': sha(assets / 'rokoko-lookaround.glb'),
            'runtime_poster': {'file': f'{key}.webp', 'encoding': 'webp-lossless',
                               'sha256': hashlib.sha256(web_poster_bytes).hexdigest(), 'bytes': len(web_poster_bytes)},
            'triangles': checks['triangles'], 'bones': checks['joints'],
            'vertices': checks['vertices_with_uv_seams'], 'export': export,
            'head_baseline': head_baseline,
            'lookaround_export': motion, 'geometry_material_fingerprint': checks['geometry_material_fingerprint'],
        }
        prepared.append((key, assets, poster_bytes, web_poster_bytes))
    manifest['assets'] = {key: manifest['assets'][key] for key in RESOURCE_KEYS}
    fingerprints = {key: fingerprints[key] for key in RESOURCE_KEYS}
    prepared.sort(key=lambda row: RESOURCE_KEYS.index(row[0]))
    manifest['art_review'] = approval_record(args.review_approval, fingerprints)
    if args.output.resolve() == (ROOT / 'web/src/assets/body').resolve() and manifest['art_review']['status'] != 'accepted':
        raise ValueError('Shipping assets requires approval of the exact candidate fingerprints')
    # Validate against exact candidate base files before replacing any output.
    import tempfile
    with tempfile.TemporaryDirectory(dir=args.workspace) as temporary:
        staged = Path(temporary)
        for key, assets, poster_bytes, web_poster_bytes in prepared:
            shutil.copyfile(assets / 'character.glb', staged / f'{key}.glb')
            (staged / f'{key}.png').write_bytes(poster_bytes)
            (staged / f'{key}.webp').write_bytes(web_poster_bytes)
        motion_sources = staged / 'motion-source'
        for key, assets, _, _ in prepared:
            directory = motion_sources / key; directory.mkdir(parents=True)
            for filename in ('rokoko-lookaround.glb', 'rokoko-lookaround.json'):
                shutil.copyfile(assets / filename, directory / filename)
        _, motion_files = prepare_motion(motion_sources, staged)
        for filename, data in motion_files.items(): (staged / filename).write_bytes(data)
        (staged / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        for source, destination in [('LICENSE.md', 'LICENSE.MPFB.md'), ('LICENSE.ASSETS.md', 'LICENSE.CC0.md')]:
            shutil.copyfile(args.source / source, staged / destination)
        args.output.mkdir(parents=True, exist_ok=True)
        for path in staged.iterdir():
            if path.is_file(): shutil.copyfile(path, args.output / path.name)
        for obsolete in ('male-idle.glb', 'female-idle.glb'):
            (args.output / obsolete).unlink(missing_ok=True)
    print(json.dumps({'version': manifest['version'], 'art_review': manifest['art_review']['status'], 'assets': {
        name: {key: row[key] for key in ('bytes', 'triangles', 'bones', 'sha256')}
        for name, row in manifest['assets'].items()}}, indent=2))


if __name__ == '__main__':
    main()
