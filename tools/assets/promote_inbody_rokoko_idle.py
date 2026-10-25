"""Validate and add the reviewed Rokoko LookingAround take without replacing legacy assets.

The default invocation is read-only. --write copies only the two *-lookaround.glb
files and their dedicated provenance manifest after every input has passed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import tempfile


ROOT = Path(__file__).resolve().parents[2]
TEMPLATES = ('male', 'female', 'male_spartan')
CLIP = 'rokoko_lookaround'
VERSION = 'inbody-rokoko-lookaround-v1'
MANIFEST_NAME = 'rokoko-lookaround.json'
SOURCE_FILE = 'Idle_LookingAround_MIXAMO_769.fbx'
SOURCE_SHA256 = '0c967adac22b5b2337c26ea36272b6ef089f4ea2be22e873caceeab496ead595'
ARCHIVE_SHA256 = 'ce239abe239a264cc6567be14a53a973fa719a759095eece0cddb7cc3f16fa37'
SOURCE_URL = 'https://www.rokoko.com/resources/rokoko-mocap-10-free-everyday-idle-animations'
ARCHIVE_URL = 'https://media.rokoko.com/EVERYDAY-IDLES-MOCAP.zip'
FPS, FIRST_MOTION_FRAME, LAST_SOURCE_FRAME = 30, 3, 1014


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def read_glb(raw):
    require(len(raw) >= 28, 'Truncated GLB')
    require(struct.unpack_from('<4sII', raw) == (b'glTF', 2, len(raw)), 'Invalid GLB header')
    chunks, offset = [], 12
    while offset < len(raw):
        require(offset + 8 <= len(raw), 'Truncated GLB chunk')
        size, kind = struct.unpack_from('<II', raw, offset)
        require(size % 4 == 0 and offset + 8 + size <= len(raw), 'Invalid GLB chunk length')
        chunks.append((kind, raw[offset + 8:offset + 8 + size]))
        offset += 8 + size
    require([kind for kind, _ in chunks] == [0x4E4F534A, 0x004E4942], 'Expected JSON and embedded BIN')
    doc = json.loads(chunks[0][1])
    binary = chunks[1][1]
    buffers = doc.get('buffers', [])
    require(len(buffers) == 1 and 'uri' not in buffers[0], 'External buffers are forbidden')
    require(0 <= len(binary) - buffers[0]['byteLength'] <= 3, 'Invalid embedded buffer size')
    return doc, binary


def accessor(doc, binary, index, kind, frames):
    require(isinstance(index, int) and 0 <= index < len(doc['accessors']), 'Invalid accessor index')
    entry = doc['accessors'][index]
    require(entry.get('componentType') == 5126 and entry.get('type') == kind,
            'Animation accessors must contain float32 values of the expected width')
    require(not entry.get('normalized') and 'sparse' not in entry, 'Unexpected compressed animation accessor')
    require(entry.get('count') == frames, 'Original animation sample count changed')
    view_index = entry.get('bufferView', -1)
    require(0 <= view_index < len(doc['bufferViews']), 'Invalid buffer view')
    view = doc['bufferViews'][view_index]
    require(view.get('buffer', 0) == 0, 'Animation references an external buffer')
    width = {'SCALAR': 1, 'VEC3': 3, 'VEC4': 4}[kind]
    size = width * 4
    stride = view.get('byteStride', size)
    start = view.get('byteOffset', 0) + entry.get('byteOffset', 0)
    end = start + (entry['count'] - 1) * stride + size
    require(stride >= size and stride % 4 == 0 and start >= view.get('byteOffset', 0),
            'Invalid animation accessor stride or offset')
    require(end <= view.get('byteOffset', 0) + view['byteLength'] <= len(binary),
            'Animation accessor exceeds its embedded buffer')
    rows = list(struct.iter_unpack('<' + 'f' * width, binary[start:end])) if stride == size else [
        struct.unpack_from('<' + 'f' * width, binary, start + i * stride) for i in range(entry['count'])]
    require(all(math.isfinite(value) for row in rows for value in row), 'Nonfinite animation data')
    return rows


def hierarchy(doc, strip_geometry=False):
    nodes = doc.get('nodes', [])
    require(nodes and all(isinstance(node.get('name'), str) and node['name'] for node in nodes),
            'Every target node must have a stable name')
    require(len({node['name'] for node in nodes}) == len(nodes), 'Duplicate target node name')
    parents = set()
    for node in nodes:
        for child in node.get('children', []):
            require(isinstance(child, int) and 0 <= child < len(nodes), 'Invalid target hierarchy index')
            require(child not in parents, 'Target node has multiple parents')
            parents.add(child)
    roots = doc.get('scenes', [])[doc.get('scene', 0)].get('nodes', [])
    reached, active = set(), set()

    def visit(index):
        require(index not in active, 'Cycle in target hierarchy')
        require(index not in reached, 'Repeated target hierarchy node')
        require(isinstance(index, int) and 0 <= index < len(nodes), 'Invalid scene root')
        active.add(index)
        for child in nodes[index].get('children', []):
            visit(child)
        active.remove(index)
        reached.add(index)

    for index in roots:
        visit(index)
    require(len(reached) == len(nodes), 'Unbound nodes outside target scene')
    ignored = {'extras', 'mesh', 'skin'} if strip_geometry else {'extras'}
    return [{key: value for key, value in node.items() if key not in ignored} for node in nodes]


def validate_animation(raw, base_raw, record, template):
    require(template in TEMPLATES, 'Unknown body template')
    require(record.get('version') == VERSION and record.get('clip') == CLIP, 'Wrong export version or clip')
    require(record.get('sha256') == sha(raw) and record.get('bytes') == len(raw), 'Animation hash or byte count mismatch')
    source, target, retarget = record['source'], record['target'], record['retarget']
    require(source.get('file') == SOURCE_FILE and source.get('sha256') == SOURCE_SHA256,
            'Unexpected source recording or source hash')
    require(source.get('url') == SOURCE_URL and source.get('author') == 'Rokoko', 'Missing publisher provenance')
    require(source.get('license') == 'Rokoko publisher permission; not CC0', 'Incorrect animation license')
    phenotype = 'female' if template == 'female' else 'male'
    require(target.get('template') == phenotype and target.get('resource_id') == template and target.get('base_sha256') == sha(base_raw),
            'Animation was retargeted to a different body template or base')
    source_range = retarget.get('source_frame_range', [])
    require(len(source_range) == 2 and all(isinstance(frame, int) for frame in source_range), 'Invalid source frame range')
    first, last = source_range
    require(FIRST_MOTION_FRAME <= first < last <= LAST_SOURCE_FRAME and retarget.get('omitted_calibration_frames') == [1, 2],
            'Unexpected source trimming')
    frames, duration = last - first + 1, (last - first) / FPS
    require(record.get('fps') == FPS and record.get('frames') == frames and abs(record.get('duration', -1) - duration) < 1e-6,
            'Original animation duration or sample rate changed')
    require(retarget.get('source_timing_preserved') is True and retarget.get('loop') is False,
            'Source timing or non-looping provenance changed')
    require(retarget.get('mesh_unchanged') is True, 'Source export must preserve body geometry')
    doc, binary = read_glb(raw)
    base, _ = read_glb(base_raw)
    require(not any(doc.get(key) for key in ('meshes', 'materials', 'images', 'textures', 'skins', 'cameras')),
            'Animation-only asset contains geometry, images, or other scene resources')
    require(doc.get('scene', 0) == base.get('scene', 0) and doc.get('scenes') == base.get('scenes'),
            'Target scene roots do not match the body')
    require(hierarchy(doc) == hierarchy(base, strip_geometry=True), 'Target node hierarchy or rest transforms differ')
    require(len(base.get('skins', [])) == 1, 'Expected a single target skeleton')
    joints = set(base['skins'][0]['joints'])
    require(target.get('bones') == len(joints) and target.get('channels') == len(joints) * 3,
            'Export skeleton counts disagree with target')
    clips = doc.get('animations', [])
    require(len(clips) == 1 and clips[0].get('name') == CLIP, 'Wrong animation clip')
    clip = clips[0]
    require(len(clip['channels']) == len(joints) * 3, 'Incomplete target skeleton tracks')
    cached = {}

    def values(index, kind):
        key = index, kind
        if key not in cached:
            cached[key] = accessor(doc, binary, index, kind, frames)
        return cached[key]

    targets = set()
    for channel in clip['channels']:
        node, path = channel['target'].get('node'), channel['target'].get('path')
        require(node in joints and path in ('translation', 'rotation', 'scale'), 'Animation changes morphology or an unbound node')
        require((node, path) not in targets, 'Duplicate animation target')
        targets.add((node, path))
        sampler_index = channel.get('sampler', -1)
        require(0 <= sampler_index < len(clip['samplers']), 'Invalid animation sampler')
        sampler = clip['samplers'][sampler_index]
        require(sampler.get('interpolation', 'LINEAR') == 'LINEAR', 'Source motion must be sampled linearly')
        times = values(sampler['input'], 'SCALAR')
        require(all(abs(row[0] - i / FPS) < .00001 for i, row in enumerate(times)), 'Original sample times changed')
        samples = values(sampler['output'], 'VEC4' if path == 'rotation' else 'VEC3')
        if path == 'rotation':
            require(all(abs(sum(v * v for v in row) - 1) < .001 for row in samples), 'Non-normalized animation quaternion')
        if path == 'scale':
            rest = doc['nodes'][node].get('scale', [1, 1, 1])
            require(all(abs(value - original) < .0001 for row in samples for value, original in zip(row, rest)),
                    'Animation changes body proportions through bone scale')
    require(targets == {(node, path) for node in joints for path in ('translation', 'rotation', 'scale')},
            'Missing target skeleton channels')
    return {'sha256': sha(raw), 'bytes': len(raw), 'base_sha256': sha(base_raw),
            'bones': len(joints), 'channels': len(targets), 'duration': duration, 'frames': frames, 'fps': FPS, 'source_frame_range': [first, last]}


def source_provenance():
    return {'author': 'Sam Lazarus / Rokoko', 'package': '10 free everyday idle animations',
            'url': SOURCE_URL, 'file': SOURCE_FILE, 'sha256': SOURCE_SHA256,
            'archive': {'url': ARCHIVE_URL, 'file': 'EVERYDAY-IDLES-MOCAP.zip', 'sha256': ARCHIVE_SHA256},
            'license': 'Rokoko publisher permission; not CC0',
            'notice': 'tools/assets/ROKOKO_ANIMATION_NOTICE.md'}


def prepare(source_directory, output_directory):
    assets, prepared = {}, {}
    for template in TEMPLATES:
        raw = (source_directory / template / 'rokoko-lookaround.glb').read_bytes()
        record = json.loads((source_directory / template / 'rokoko-lookaround.json').read_text())
        base_raw = (output_directory / f'{template}.glb').read_bytes()
        verified = validate_animation(raw, base_raw, record, template)
        assets[template] = {'model': f'{template}.glb', 'animation': f'{template}-lookaround.glb',
                            **verified}
        prepared[f'{template}-lookaround.glb'] = raw
    require(all(row['source_frame_range']==assets['male']['source_frame_range'] for row in assets.values()), 'Body resources use different source windows')
    manifest = {'version': VERSION, 'clip': CLIP, 'source': source_provenance(),
                'retarget': {'source_frame_range': assets['male']['source_frame_range'], 'omitted_calibration_frames': [1, 2],
                             'source_timing_preserved': True, 'source_is_loop': False,
                             'mesh_unchanged': True},
                'compiler': {'path': 'tools/assets/import_inbody_rokoko_idle.py',
                             'sha256': sha((ROOT / 'tools/assets/import_inbody_rokoko_idle.py').read_bytes())},
                'assets': assets}
    prepared[MANIFEST_NAME] = (json.dumps(manifest, indent=2) + '\n').encode()
    return manifest, prepared


def promote(source_directory, output_directory, *, write=False):
    manifest, prepared = prepare(source_directory, output_directory)
    if write:
        # Validate all three resources before any mutation; manifest is the final commit marker.
        for template in TEMPLATES:
            require(sha((output_directory / f'{template}.glb').read_bytes()) == manifest['assets'][template]['base_sha256'],
                    'Body base changed during validation')
        for filename, data in prepared.items():
            with tempfile.NamedTemporaryFile(dir=output_directory, prefix='.rokoko-', delete=False) as file:
                temporary = Path(file.name)
                try:
                    file.write(data)
                    file.flush()
                    os.fchmod(file.fileno(), 0o644)
                    os.fsync(file.fileno())
                except BaseException:
                    temporary.unlink(missing_ok=True)
                    raise
            try:
                os.replace(temporary, output_directory / filename)
            finally:
                temporary.unlink(missing_ok=True)
    return manifest


def validate_promoted(directory):
    """Check the shipped files independently from private exporter workspaces."""
    manifest = json.loads((directory / MANIFEST_NAME).read_text())
    require(set(manifest) == {'version', 'clip', 'source', 'retarget', 'compiler', 'assets'}, 'Unexpected provenance fields')
    require(manifest['version'] == VERSION and manifest['clip'] == CLIP, 'Wrong promoted clip')
    require(manifest['source'] == source_provenance(), 'Promoted source provenance changed')
    compiler = manifest['compiler']
    require(set(compiler) == {'path', 'sha256'} and compiler['path'] == 'tools/assets/import_inbody_rokoko_idle.py',
            'Unsafe compiler provenance path')
    require(len(compiler['sha256']) == 64 and all(c in '0123456789abcdef' for c in compiler['sha256']),
            'Missing compiler provenance hash')
    require(set(manifest['assets']) == set(TEMPLATES), 'All three promoted resources are required')
    retarget = manifest['retarget']
    require(set(retarget) == {'source_frame_range', 'omitted_calibration_frames', 'source_timing_preserved', 'source_is_loop', 'mesh_unchanged'},
            'Unexpected retarget provenance fields')
    for template in TEMPLATES:
        row = manifest['assets'][template]
        require(set(row) == {'model', 'animation', 'sha256', 'bytes', 'base_sha256', 'bones', 'channels', 'duration', 'frames', 'fps', 'source_frame_range'},
                'Unexpected asset provenance fields')
        require(row['model'] == f'{template}.glb' and row['animation'] == f'{template}-lookaround.glb',
                'Unsafe promoted asset path')
        require(row['source_frame_range'] == retarget['source_frame_range'], 'Promoted source windows disagree')
        record = {**row, 'version': manifest['version'], 'clip': manifest['clip'],
                  'source': {'author': 'Rokoko', 'file': SOURCE_FILE, 'sha256': SOURCE_SHA256,
                             'url': SOURCE_URL, 'license': manifest['source']['license']},
                  'target': {'template': 'female' if template=='female' else 'male', 'resource_id': template, 'base_sha256': row['base_sha256'],
                             'bones': row['bones'], 'channels': row['channels']},
                  'retarget': {**retarget, 'loop': retarget['source_is_loop']}}
        validate_animation((directory / row['animation']).read_bytes(),
                           (directory / row['model']).read_bytes(), record, template)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-directory', type=Path, default=ROOT / 'runtime/inbody-rokoko')
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'web/src/assets/body')
    parser.add_argument('--source-fbx', type=Path, help='Optionally verify the local original recording')
    parser.add_argument('--source-archive', type=Path, help='Optionally verify the original download archive')
    parser.add_argument('--write', action='store_true', help='Promote after visual acceptance; otherwise validate only')
    args = parser.parse_args()
    for path, expected in ((args.source_fbx, SOURCE_SHA256), (args.source_archive, ARCHIVE_SHA256)):
        if path is not None:
            require(sha(path.read_bytes()) == expected, 'Local original source hash mismatch')
    manifest = promote(args.source_directory, args.output_directory, write=args.write)
    print(json.dumps({'written': args.write, 'manifest': MANIFEST_NAME, 'assets': manifest['assets']}, indent=2))


if __name__ == '__main__':
    main()
