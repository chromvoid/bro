"""Check the real CMU preview export; visual retarget quality is checked separately."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]


def validate(directory):
    manifest = json.loads((directory / 'cmu-flexing.json').read_text())
    raw = (directory / 'cmu-flexing.glb').read_bytes()
    assert hashlib.sha256(raw).hexdigest() == manifest['sha256'], 'Stale manifest'
    assert hashlib.sha256((directory / 'character.glb').read_bytes()).hexdigest() == manifest['target']['base_sha256'], 'Base character changed'
    magic, version, length = struct.unpack_from('<III', raw)
    assert (magic, version, length) == (0x46546C67, 2, len(raw))
    size, kind = struct.unpack_from('<II', raw, 12)
    assert kind == 0x4E4F534A
    doc = json.loads(raw[20:20 + size])
    bin_offset = 20 + size
    bin_size, kind = struct.unpack_from('<II', raw, bin_offset)
    assert kind == 0x004E4942
    binary = raw[bin_offset + 8:bin_offset + 8 + bin_size]
    assert not doc.get('meshes') and not doc.get('skins'), 'Motion file duplicates model'
    assert len(doc['animations']) == 1 and doc['animations'][0]['name'] == 'cmu_flexing'

    def values(index):
        accessor = doc['accessors'][index]
        assert accessor['componentType'] == 5126 and 'sparse' not in accessor
        width = {'SCALAR': 1, 'VEC3': 3, 'VEC4': 4}[accessor['type']]
        view = doc['bufferViews'][accessor['bufferView']]
        start = view.get('byteOffset', 0) + accessor.get('byteOffset', 0)
        stride = view.get('byteStride', width * 4)
        rows = [struct.unpack_from('<' + 'f' * width, binary, start + i * stride)
                for i in range(accessor['count'])]
        assert all(math.isfinite(value) for row in rows for value in row), 'Nonfinite motion'
        return rows

    animation = doc['animations'][0]
    channels = {}
    for channel in animation['channels']:
        sampler = animation['samplers'][channel['sampler']]
        assert sampler['interpolation'] == 'LINEAR'
        name = doc['nodes'][channel['target']['node']]['name']
        path = channel['target']['path']
        assert path in ('translation', 'rotation', 'scale'), 'Motion overwrites physique'
        times = values(sampler['input'])
        rows = values(sampler['output'])
        assert len(times) == len(rows) == 935, 'Capture trimmed or resampled'
        assert all(abs(row[0] - i / 60) < 1e-6 for i, row in enumerate(times)), 'Original time changed'
        if path == 'rotation':
            assert all(abs(sum(v * v for v in row) - 1) < 1e-5 for row in rows), 'Invalid quaternion'
            assert all(sum(a * b for a, b in zip(first, second)) >= 0 for first, second in zip(rows, rows[1:])), 'Quaternion sign discontinuity'
        channels[(name, path)] = rows
    assert abs(manifest['duration'] - 934 / 60) < 1e-6 and manifest['fps'] == 60
    root = channels[('root', 'translation')]
    root_range = [max(row[i] for row in root) - min(row[i] for row in root) for i in range(3)]
    assert all(value > .01 for value in root_range), 'Captured root movement was frozen'
    for bone in ('upperleg01.L', 'upperleg01.R', 'lowerleg01.L', 'lowerleg01.R', 'foot.L', 'foot.R'):
        rows = channels[(bone, 'rotation')]
        assert any(abs(sum(a * b for a, b in zip(rows[0], row))) < .999 for row in rows), f'Captured leg was frozen: {bone}'
    return {'clip': 'cmu_flexing', 'duration': manifest['duration'], 'frames': 935,
            'channels': len(channels), 'root_range_gltf_meters': root_range,
            'base_unchanged': True, 'finite_normalized_rotations': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, default=ROOT / 'runtime/inbody-character/assets')
    args = parser.parse_args()
    result = validate(args.assets)
    output = ROOT / 'runtime/inbody-character/cmu/verification.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
