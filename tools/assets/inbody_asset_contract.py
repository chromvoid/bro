"""Versioned offline resource metadata shared by compiler and validators."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct

VERSION = 'body-assets-v3'
RESOURCE_KEYS = ('male', 'female', 'male_spartan')
BODY_MORPHS = [f'shape_f{fat}_m{muscle}' for fat in range(6) for muscle in range(5)]
POSE_CORRECTIVES = [
    {'name': 'pose_shoulder_raise_l', 'bone': 'upperarm01.L', 'axis': [0, 0, 1], 'sign': 1,
     'start_radians': .35, 'end_radians': 1.2, 'composition_scales': [1.] * 30},
    {'name': 'pose_shoulder_raise_r', 'bone': 'upperarm01.R', 'axis': [0, 0, 1], 'sign': -1,
     'start_radians': .35, 'end_radians': 1.2, 'composition_scales': [1.] * 30},
    {'name': 'pose_elbow_flex_l', 'bone': 'lowerarm01.L', 'axis': [1, 0, 0], 'sign': 1,
     'start_radians': .35, 'end_radians': 1.55, 'composition_scales': [1.] * 30},
    {'name': 'pose_elbow_flex_r', 'bone': 'lowerarm01.R', 'axis': [1, 0, 0], 'sign': 1,
     'start_radians': .35, 'end_radians': 1.55, 'composition_scales': [1.] * 30},
]


def relief_weights(fat: float, muscle: float) -> list[float]:
    """Convex artistic blend: fat progressively suppresses secondary definition."""
    definition = max(0., min(1., muscle * (1. - fat) / .8))
    if definition <= .5:
        return [1. - 2. * definition, 2. * definition, 0.]
    return [0., 2. * (1. - definition), 2. * definition - 1.]


def embed_contract(path: Path, output: Path, surfaces: list[dict]) -> dict:
    """Append all relief textures to the same GLB; no external URLs or dummy meshes.

    The standard normalTexture references the neutral map for offline viewers.
    The additional images are referenced by scene extras and loaded explicitly by
    the renderer using GLTFParser.getDependency('texture', index).
    """
    raw = path.read_bytes()
    size, kind = struct.unpack_from('<II', raw, 12)
    assert kind == 0x4E4F534A
    doc = json.loads(raw[20:20 + size])
    binary_size, binary_kind = struct.unpack_from('<II', raw, 20 + size)
    assert binary_kind == 0x004E4942
    payload = bytearray(raw[28 + size:28 + size + binary_size])
    payload = payload[:doc['buffers'][0]['byteLength']]
    maps = {}
    for label in ('soft', 'neutral', 'defined', 'regions'):
        data = (output / f'relief-{label}.png').read_bytes()
        # The neutral bake is already embedded by Blender. Reuse it when the
        # content agrees, rather than paying twice for the standard material.
        image_index = None
        if label == 'neutral':
            body_node = next(node for node in doc['nodes'] if node.get('name') == 'InBodyCharacter')
            primitive = doc['meshes'][body_node['mesh']]['primitives'][0]
            normal = doc['materials'][primitive['material']].get('normalTexture')
            if normal:
                image_index = doc['textures'][normal['index']]['source']
                payload.extend(b'\0' * (-len(payload) % 4))
                view_index = len(doc['bufferViews'])
                doc['bufferViews'].append({'buffer': 0, 'byteOffset': len(payload), 'byteLength': len(data)})
                payload.extend(data)
                doc['images'][image_index] = {'name': 'relief-neutral', 'mimeType': 'image/png', 'bufferView': view_index}
        for index, image in enumerate(doc.get('images', [])):
            if image_index is not None: break
            view = doc['bufferViews'][image['bufferView']]
            start = view.get('byteOffset', 0)
            if bytes(payload[start:start + view['byteLength']]) == data:
                image_index = index
                image['name'] = f'relief-{label}'
                break
        if image_index is None:
            payload.extend(b'\0' * (-len(payload) % 4))
            view_index = len(doc['bufferViews'])
            doc['bufferViews'].append({'buffer': 0, 'byteOffset': len(payload), 'byteLength': len(data)})
            payload.extend(data)
            image_index = len(doc.setdefault('images', []))
            doc['images'].append({'name': f'relief-{label}', 'mimeType': 'image/png', 'bufferView': view_index})
        texture_index = next((index for index, texture in enumerate(doc.get('textures', []))
                              if texture.get('source') == image_index), None)
        if texture_index is None:
            texture_index = len(doc.setdefault('textures', []))
            doc['textures'].append({'name': f'relief-{label}', 'source': image_index})
        maps[label] = texture_index
    contract = {'version': VERSION, 'body_morph_names': BODY_MORPHS,
                'body_surface_nodes': ['InBodyCharacter'], 'surfaces': surfaces,
                'pose_correctives': POSE_CORRECTIVES,
                'relief': {'normal_textures': {label: maps[label] for label in ('soft', 'neutral', 'defined')},
                           'mask_texture': maps['regions'], 'region_channels': ['torso', 'arms', 'legs'],
                           'grid_weights': [relief_weights(fat, muscle) for fat in (0., .2, .4, .6, .8, 1.)
                                            for muscle in (0., .25, .5, .75, 1.)]},
                'framing': {'body_surface_nodes': ['InBodyCharacter']}}
    scene = doc['scenes'][doc.get('scene', 0)]
    scene.setdefault('extras', {})['bodyAsset'] = contract
    doc['buffers'][0]['byteLength'] = len(payload)
    encoded = json.dumps(doc, separators=(',', ':')).encode()
    encoded += b' ' * (-len(encoded) % 4)
    payload.extend(b'\0' * (-len(payload) % 4))
    path.write_bytes(struct.pack('<4sII', b'glTF', 2, 28 + len(encoded) + len(payload))
                     + struct.pack('<II', len(encoded), 0x4E4F534A) + encoded
                     + struct.pack('<II', len(payload), 0x004E4942) + payload)
    return contract


def file_sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()
