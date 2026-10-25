"""Verify faceless heads against the exact deployed mannequin, on CI only.

UV seams and the body's bake atlas may change when body details are refined.
The head's actual triangles, normals, 30 forms, skin and rest rig must not.
The pinned production files remain private compiler inputs, outside the bundle.
"""
from __future__ import annotations

from collections import Counter
import copy
import hashlib
import json
import math
from pathlib import Path
import struct

if __package__:
    from .inbody_asset_contract import BODY_MORPHS, POSE_CORRECTIVES
else:
    from inbody_asset_contract import BODY_MORPHS, POSE_CORRECTIVES

PRODUCTION_COMMIT = 'eebec8980bd319cff622937d579c21fedc66eee3'
PRODUCTION_MODELS = {
    'male': {'sha256': '1d79294d066cc5bf52835ffcbb89058b6e1505820626c617766cfe91de452504',
             'bytes': 11589776},
    'female': {'sha256': '943c50c1b156cc1a84ed545808f4b21d2efc577deb86230d7f9174595791034d',
               'bytes': 11743512},
}
COMPONENTS = {5120: 'b', 5121: 'B', 5122: 'h', 5123: 'H', 5125: 'I', 5126: 'f'}
WIDTHS = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}
RIG_FIELDS = ('parent', 'translation', 'rotation', 'scale', 'matrix', 'inverse_bind')
# CI2524 measured at most 7.16e-7 in Float32 rest exports. This import gate
# permits replaying the pinned values, never accepting drift in the final model.
REST_IMPORT_MAX_ERROR = 1e-6
HEAD_POSITION_IMPORT_MAX_ERROR = 1e-6


def rig_difference(original, refined):
    """Bounded exact field evidence for CI failures; this defines no tolerance."""
    changes = []
    changed_bones = 0
    field_counts = Counter()
    maximum_errors = {}
    for name in sorted(original.keys() & refined.keys()):
        if original[name] == refined[name]:
            continue
        changed_bones += 1
        for field, before, after in zip(RIG_FIELDS, original[name], refined[name]):
            if before == after:
                continue
            field_counts[field] += 1
            evidence = {'bone': name, 'field': field}
            if isinstance(before, tuple) and isinstance(after, tuple):
                differences = [(index, source, candidate) for index, (source, candidate) in enumerate(zip(before, after))
                               if source != candidate]
                evidence['source_length'], evidence['candidate_length'] = len(before), len(after)
                if differences:
                    index, source, candidate = differences[0]
                    evidence['first_difference'] = {'index': index, 'source': source, 'candidate': candidate}
                    error = max(abs(source - candidate) for _, source, candidate in differences)
                    evidence['max_abs_error'] = error
                    maximum_errors[field] = max(maximum_errors.get(field, 0.), error)
                elif len(before) != len(after):
                    evidence['source'], evidence['candidate'] = before, after
            else:
                evidence['source'], evidence['candidate'] = before, after
            if len(changes) < 12:
                changes.append(evidence)
    return {'source_bones': len(original), 'candidate_bones': len(refined),
            'missing_bones': sorted(original.keys() - refined.keys()),
            'extra_bones': sorted(refined.keys() - original.keys()),
            'changed_bones': changed_bones, 'changed_fields': dict(field_counts),
            'max_abs_error_by_field': maximum_errors, 'first_differences': changes,
            'reported_difference_limit': 12}


class _Document:
    def __init__(self, path):
        self.raw = Path(path).read_bytes()
        assert struct.unpack_from('<4sII', self.raw) == (b'glTF', 2, len(self.raw)), 'Invalid head GLB'
        size, kind = struct.unpack_from('<II', self.raw, 12)
        assert kind == 0x4E4F534A
        self.doc = json.loads(self.raw[20:20 + size])
        binary_size, kind = struct.unpack_from('<II', self.raw, 20 + size)
        assert kind == 0x004E4942
        self.binary = self.raw[28 + size:28 + size + binary_size]
        assert len(self.doc['buffers']) == 1 and 'uri' not in self.doc['buffers'][0]
        assert len(self.doc['skins']) == 1
        nodes = [node for node in self.doc['nodes'] if node.get('name') == 'InBodyCharacter']
        assert len(nodes) == 1
        self.node = nodes[0]
        self.node_index = self.doc['nodes'].index(self.node)
        self.mesh = self.doc['meshes'][self.node['mesh']]
        self.skin = self.doc['skins'][self.node['skin']]
        self.joint_names = [self.doc['nodes'][index]['name'] for index in self.skin['joints']]

    def accessor(self, index):
        entry = self.doc['accessors'][index]
        fmt = '<' + COMPONENTS[entry['componentType']] * WIDTHS[entry['type']]
        size = struct.calcsize(fmt)
        if 'bufferView' in entry:
            view = self.doc['bufferViews'][entry['bufferView']]
            start = view.get('byteOffset', 0) + entry.get('byteOffset', 0)
            stride = view.get('byteStride', size)
            values = [struct.unpack_from(fmt, self.binary, start + i * stride) for i in range(entry['count'])]
        else:
            values = [(0,) * WIDTHS[entry['type']] for _ in range(entry['count'])]
        if 'sparse' in entry:
            sparse = entry['sparse']
            indices, replacements = sparse['indices'], sparse['values']
            iv, vv = (self.doc['bufferViews'][item['bufferView']] for item in (indices, replacements))
            ifmt = '<' + COMPONENTS[indices['componentType']]
            istart = iv.get('byteOffset', 0) + indices.get('byteOffset', 0)
            vstart = vv.get('byteOffset', 0) + replacements.get('byteOffset', 0)
            for i in range(sparse['count']):
                target = struct.unpack_from(ifmt, self.binary, istart + i * struct.calcsize(ifmt))[0]
                values[target] = struct.unpack_from(fmt, self.binary, vstart + i * size)
        assert all(math.isfinite(value) for row in values for value in row), 'Nonfinite head accessor'
        return values

    def rest_rig(self):
        assert len(self.joint_names) == 105, 'The deployed 105-bone skeleton must be retained'
        assert len(set(self.joint_names)) == 105, 'Skeleton bone names must be unique'
        inverse_bind = self.accessor(self.skin['inverseBindMatrices'])
        assert len(inverse_bind) == len(self.joint_names)
        parents = {child: node['name'] for node in self.doc['nodes'] for child in node.get('children', [])}
        return {name: (parents.get(index),
                       tuple(node.get('translation', (0, 0, 0))),
                       tuple(node.get('rotation', (0, 0, 0, 1))),
                       tuple(node.get('scale', (1, 1, 1))),
                       tuple(node.get('matrix', ())), tuple(bind))
                for name, index, bind in zip(self.joint_names, self.skin['joints'], inverse_bind)
                for node in (self.doc['nodes'][index],)}

    def body_transform(self):
        parents = {child: index for index, node in enumerate(self.doc['nodes'])
                   for child in node.get('children', [])}
        chain = []
        index = self.node_index
        visited = set()
        while index is not None:
            assert index not in visited, 'Cyclic body transform hierarchy'
            visited.add(index)
            node = self.doc['nodes'][index]
            chain.append((node['name'], tuple(node.get('translation', (0, 0, 0))),
                          tuple(node.get('rotation', (0, 0, 0, 1))),
                          tuple(node.get('scale', (1, 1, 1))), tuple(node.get('matrix', ()))))
            index = parents.get(index)
        return tuple(chain)

    def head_arrays(self):
        names = self.mesh['extras']['targetNames']
        assert names[:30] == BODY_MORPHS, 'The deployed 30 body forms must be retained'
        assert len(self.mesh['primitives']) == 1
        primitive = self.mesh['primitives'][0]
        attributes = primitive['attributes']
        positions = self.accessor(attributes['POSITION'])
        normals = self.accessor(attributes['NORMAL'])
        height = max(point[1] for point in positions)
        assert height > 1 and height > max(abs(point[2]) for point in positions) * 2, 'Expected Y-up mannequin'
        selected = {index for index, point in enumerate(positions) if point[1] >= .855 * height}
        assert len(selected) > 100, 'The connected mannequin head is missing'
        joints = self.accessor(attributes['JOINTS_0'])
        weights = self.accessor(attributes['WEIGHTS_0'])
        targets = primitive['targets']
        shapes = [self.accessor(target['POSITION']) for target in targets[:30]]
        shape_normals = [self.accessor(target['NORMAL']) for target in targets[:30]]
        return {'primitive': primitive, 'positions': positions, 'normals': normals, 'selected': selected,
                'joints': joints, 'weights': weights, 'shapes': shapes, 'shape_normals': shape_normals}

    def head(self, require_faceless):
        arrays = self.head_arrays()
        primitive = arrays['primitive']
        positions, normals = arrays['positions'], arrays['normals']
        selected, joints, weights = arrays['selected'], arrays['joints'], arrays['weights']
        shapes, shape_normals = arrays['shapes'], arrays['shape_normals']
        names, targets = self.mesh['extras']['targetNames'], primitive['targets']
        descriptors = {}
        for index in selected:
            skin = tuple(sorted((self.joint_names[joint], weight)
                                for joint, weight in zip(joints[index], weights[index]) if weight != 0))
            descriptors[index] = (positions[index], normals[index], skin,
                                  tuple(shape[index] for shape in shapes),
                                  tuple(normal[index] for normal in shape_normals))
        indices = [row[0] for row in self.accessor(primitive['indices'])]
        assert primitive.get('mode', 4) == 4 and len(indices) % 3 == 0
        def triangle_key(triangle):
            rows = tuple(descriptors[index] for index in triangle)
            return min(rows, rows[1:] + rows[:1], rows[2:] + rows[:2])
        triangles = Counter(triangle_key(triangle)
                            for offset in range(0, len(indices), 3)
                            for triangle in (indices[offset:offset + 3],)
                            if all(index in selected for index in triangle))
        assert triangles, 'The connected head has no triangles'
        material = self.doc['materials'][primitive['material']]
        pbr = material['pbrMetallicRoughness']
        appearance = (tuple(pbr['baseColorFactor']), pbr['roughnessFactor'], pbr['metallicFactor'],
                      material.get('alphaMode', 'OPAQUE'), material.get('doubleSided', False))
        if require_faceless:
            expected_nodes = {'InBodyCharacter', 'SpartanWrap'}
            assert {node['name'] for node in self.doc['nodes'] if 'mesh' in node} <= expected_nodes, 'Facial overlays are forbidden'
            assert names[30:] == [entry['name'] for entry in POSE_CORRECTIVES]
            for target in targets[30:]:
                corrections = self.accessor(target['POSITION'])
                assert all(all(value == 0 for value in corrections[index]) for index in selected), 'Joint corrections must not deform the retained head'
        return set(descriptors.values()), triangles, appearance


def _pinned_baseline(original, template):
    assert template in PRODUCTION_MODELS
    pin = PRODUCTION_MODELS[template]
    assert len(original.raw) == pin['bytes'], 'Production head baseline size differs from its pin'
    assert hashlib.sha256(original.raw).hexdigest() == pin['sha256'], 'Production head baseline checksum differs from its pin'
    return pin


def retain_production_rest(candidate: Path, baseline: Path, template: str) -> dict:
    """Replay the exact deployed rest fields after a bounded compiler import gate.

    Names, hierarchy and numeric widths must already match. New meshes, image,
    morph and animation spans stay byte-for-byte untouched; only rest node JSON
    and a newly appended, aligned inverse-bind accessor reference are replaced.
    """
    original, refined = _Document(baseline), _Document(candidate)
    pin = _pinned_baseline(original, template)
    before, target = refined.rest_rig(), original.rest_rig()
    assert before.keys() == target.keys(), 'Production rest import bone names changed'
    for name, fields in before.items():
        assert fields[0] == target[name][0], 'Production rest import bone hierarchy changed'
        for field, values, source in zip(RIG_FIELDS[1:], fields[1:], target[name][1:]):
            assert len(values) == len(source), f'Production rest import {name}/{field} width changed: source={len(source)}, candidate={len(values)}'
            assert all(math.isfinite(value) for value in (*values, *source)), f'Nonfinite production rest import: {name}/{field}'
            differences = [(index, expected, value) for index, (expected, value) in enumerate(zip(source, values)) if value != expected]
            error = max((abs(value - expected) for _, expected, value in differences), default=0.)
            assert error <= REST_IMPORT_MAX_ERROR, ('Production rest import exceeds compiler drift gate: '
                + json.dumps({'bone': name, 'field': field, 'max_abs_error': error, 'threshold': REST_IMPORT_MAX_ERROR,
                              'first_difference': differences[:1], 'rig_difference': rig_difference(target, before)}, separators=(',', ':')))
    source_chain, candidate_chain = original.body_transform(), refined.body_transform()
    assert [row[0] for row in candidate_chain] == [row[0] for row in source_chain], 'Production body transform hierarchy changed'
    chain_max_error = 0.
    for source, row in zip(source_chain, candidate_chain):
        for field, values, expected in zip(RIG_FIELDS[1:5], row[1:], source[1:]):
            assert len(values) == len(expected), f'Production body transform {row[0]}/{field} width changed: source={len(expected)}, candidate={len(values)}'
            assert all(math.isfinite(value) for value in (*values, *expected)), f'Nonfinite production body transform import: {row[0]}/{field}'
            error = max((abs(value - expected_value) for value, expected_value in zip(values, expected)), default=0.)
            assert error <= REST_IMPORT_MAX_ERROR, ('Production body transform exceeds compiler drift gate: '
                + json.dumps({'node': row[0], 'field': field, 'max_abs_error': error, 'threshold': REST_IMPORT_MAX_ERROR,
                              'source': expected, 'candidate': values}, separators=(',', ':')))
            chain_max_error = max(chain_max_error, error)
    diagnostic = rig_difference(target, before)
    metadata = {'source_commit': PRODUCTION_COMMIT, 'template': template, 'source_sha256': pin['sha256'],
                'candidate_sha256_before': hashlib.sha256(refined.raw).hexdigest(),
                'import_max_abs_error': REST_IMPORT_MAX_ERROR, 'before': diagnostic,
                'body_transform_max_abs_error_before': chain_max_error}
    if before != target or source_chain != candidate_chain:
        source_nodes = {node['name']: node for node in original.doc['nodes']}
        retained_names = set(original.joint_names) | {row[0] for row in source_chain}
        for name in retained_names:
            assert sum(node.get('name') == name for node in refined.doc['nodes']) == 1, 'Production rest import node names must be unique'
        for node in refined.doc['nodes']:
            if node.get('name') not in retained_names:
                continue
            for field in RIG_FIELDS[1:5]:
                if field in source_nodes[node['name']]:
                    node[field] = copy.deepcopy(source_nodes[node['name']][field])
                else:
                    node.pop(field, None)
        payload = bytearray(refined.binary)
        payload.extend(b'\0' * (-len(payload) % 4))
        offset = len(payload)
        for name in refined.joint_names:
            payload.extend(struct.pack('<16f', *target[name][-1]))
        view = len(refined.doc['bufferViews'])
        refined.doc['bufferViews'].append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(payload) - offset})
        accessor = len(refined.doc['accessors'])
        refined.doc['accessors'].append({'bufferView': view, 'componentType': 5126, 'count': 105, 'type': 'MAT4'})
        refined.skin['inverseBindMatrices'] = accessor
        refined.doc['buffers'][0]['byteLength'] = len(payload)
        encoded = json.dumps(refined.doc, separators=(',', ':')).encode()
        encoded += b' ' * (-len(encoded) % 4)
        payload.extend(b'\0' * (-len(payload) % 4))
        Path(candidate).write_bytes(struct.pack('<4sII', b'glTF', 2, 28 + len(encoded) + len(payload))
                                   + struct.pack('<II', len(encoded), 0x4E4F534A) + encoded
                                   + struct.pack('<II', len(payload), 0x004E4942) + payload)
    retained = _Document(candidate)
    assert retained.rest_rig() == target, 'Production rest replay did not retain exact fields'
    assert retained.body_transform() == source_chain, 'Production body transform replay was not exact'
    assert retained.binary[:len(refined.binary)] == refined.binary, 'Rest replay changed existing binary spans'
    metadata['candidate_sha256_after'] = hashlib.sha256(retained.raw).hexdigest()
    metadata['after'] = rig_difference(target, retained.rest_rig())
    metadata['body_transform_exact_after'] = True
    return metadata


def _write_rows(document, payload, accessor_index, replacements):
    """Edit existing rows, adding only required nonzero sparse head entries."""
    entry = document.doc['accessors'][accessor_index]
    assert not entry.get('normalized'), 'Head retention requires unnormalized attributes'
    fmt = '<' + COMPONENTS[entry['componentType']] * WIDTHS[entry['type']]
    size = struct.calcsize(fmt)
    sparse_rows = {}
    if 'sparse' in entry:
        sparse = entry['sparse']
        indices, values = sparse['indices'], sparse['values']
        iv, vv = (document.doc['bufferViews'][part['bufferView']] for part in (indices, values))
        ifmt = '<' + COMPONENTS[indices['componentType']]
        istart = iv.get('byteOffset', 0) + indices.get('byteOffset', 0)
        vstart = vv.get('byteOffset', 0) + values.get('byteOffset', 0)
        for ordinal in range(sparse['count']):
            index = struct.unpack_from(ifmt, payload, istart + ordinal * struct.calcsize(ifmt))[0]
            sparse_rows[index] = vstart + ordinal * size
    missing = {}
    for index, row in replacements.items():
        assert 0 <= index < entry['count'] and len(row) == WIDTHS[entry['type']]
        if 'bufferView' in entry:
            view = document.doc['bufferViews'][entry['bufferView']]
            offset = view.get('byteOffset', 0) + entry.get('byteOffset', 0) + index * view.get('byteStride', size)
            struct.pack_into(fmt, payload, offset, *row)
        if index in sparse_rows:
            struct.pack_into(fmt, payload, sparse_rows[index], *row)
        elif 'bufferView' not in entry and any(value != 0 for value in row):
            missing[index] = row
    if missing:
        rows = {index: struct.unpack_from(fmt, payload, offset) for index, offset in sparse_rows.items()}
        rows.update(missing)
        ordered = sorted(rows)
        def append(data):
            payload.extend(b'\0' * (-len(payload) % 4))
            index = len(document.doc['bufferViews'])
            document.doc['bufferViews'].append({'buffer': 0, 'byteOffset': len(payload), 'byteLength': len(data)})
            payload.extend(data)
            return index
        entry['sparse'] = {'count': len(rows),
                           'indices': {'bufferView': append(struct.pack('<' + 'I' * len(ordered), *ordered)), 'componentType': 5125},
                           'values': {'bufferView': append(b''.join(struct.pack(fmt, *rows[index]) for index in ordered))}}
    if 'min' in entry or 'max' in entry:
        original_binary = document.binary
        try:
            document.binary = payload
            effective = document.accessor(accessor_index)
        finally:
            document.binary = original_binary
        if 'min' in entry: entry['min'] = [min(row[component] for row in effective) for component in range(WIDTHS[entry['type']])]
        if 'max' in entry: entry['max'] = [max(row[component] for row in effective) for component in range(WIDTHS[entry['type']])]
    return bool(missing)


def _compact_views(doc, payload):
    """Discard superseded sparse spans, preserving every referenced view's bytes."""
    used = set()
    def visit(value, mapping=None):
        if isinstance(value, dict):
            for key, child in value.items():
                if key == 'bufferView':
                    if mapping is None: used.add(child)
                    else: value[key] = mapping[child]
                else: visit(child, mapping)
        elif isinstance(value, list):
            for child in value: visit(child, mapping)
    visit(doc)
    result, views, mapping = bytearray(), [], {}
    for index in sorted(used):
        view = copy.deepcopy(doc['bufferViews'][index])
        assert view.get('buffer', 0) == 0
        start = view.get('byteOffset', 0)
        data = payload[start:start + view['byteLength']]
        assert len(data) == view['byteLength']
        result.extend(b'\0' * (-len(result) % 4))
        view['byteOffset'] = len(result)
        mapping[index] = len(views)
        views.append(view); result.extend(data)
    visit(doc, mapping)
    doc['bufferViews'] = views
    return result


def _save_glb(path, doc, payload):
    doc['buffers'][0]['byteLength'] = len(payload)
    encoded = json.dumps(doc, separators=(',', ':')).encode()
    encoded += b' ' * (-len(encoded) % 4)
    payload.extend(b'\0' * (-len(payload) % 4))
    Path(path).write_bytes(struct.pack('<4sII', b'glTF', 2, 28 + len(encoded) + len(payload))
                          + struct.pack('<II', len(encoded), 0x4E4F534A) + encoded
                          + struct.pack('<II', len(payload), 0x004E4942) + payload)


def _outside_head_fingerprint(document, selected):
    """Hash actual unaffected rows, maps and animation samples across all meshes."""
    digest = hashlib.sha256()
    def rows(label, index, excluded=()):
        entry = document.doc['accessors'][index]
        digest.update(label.encode())
        fmt = '<' + COMPONENTS[entry['componentType']] * WIDTHS[entry['type']]
        digest.update(fmt.encode())
        for ordinal, row in enumerate(document.accessor(index)):
            if ordinal not in excluded: digest.update(struct.pack(fmt, *row))
    for node in document.doc['nodes']:
        if 'mesh' not in node: continue
        is_body = node['name'] == 'InBodyCharacter'
        for ordinal, primitive in enumerate(document.doc['meshes'][node['mesh']]['primitives']):
            label = f'{node["name"]}/{ordinal}/'
            rows(label + 'indices', primitive['indices'])
            for semantic, index in sorted(primitive['attributes'].items()):
                excluded = selected if is_body and semantic in {'POSITION', 'NORMAL', 'JOINTS_0', 'WEIGHTS_0'} else ()
                rows(label + semantic, index, excluded)
            for shape, target in enumerate(primitive.get('targets', [])):
                for semantic, index in sorted(target.items()):
                    rows(label + f'{shape}/{semantic}', index, selected if is_body else ())
    digest.update(json.dumps(document.doc.get('animations', []), sort_keys=True).encode())
    for clip in document.doc.get('animations', []):
        for ordinal, sampler in enumerate(clip['samplers']):
            rows(f'{clip["name"]}/{ordinal}/input', sampler['input'])
            rows(f'{clip["name"]}/{ordinal}/output', sampler['output'])
    for image in document.doc.get('images', []):
        view = document.doc['bufferViews'][image['bufferView']]
        start = view.get('byteOffset', 0)
        digest.update(document.binary[start:start + view['byteLength']])
    return digest.hexdigest()


def retain_production_head(candidate: Path, baseline: Path, template: str) -> dict:
    """Copy the selected production head after position and oriented-topology gates."""
    original, refined = _Document(baseline), _Document(candidate)
    pin = _pinned_baseline(original, template)
    assert refined.rest_rig() == original.rest_rig(), 'Head donor requires the exact production rest rig'
    assert refined.body_transform() == original.body_transform(), 'Head donor requires the exact production transform chain'
    source_descriptors, _, _ = original.head(False)
    refined.head(True)  # Reject facial overlays and head corrective displacement before copying.
    source, target = original.head_arrays(), refined.head_arrays()
    donor_by_position = {}
    for index in source['selected']:
        position = source['positions'][index]
        donor_by_position.setdefault(position, index)
    assert len({row[0] for row in source_descriptors}) == len(source_descriptors), 'Production head donor has ambiguous seam attributes'
    mapping, unique_mapping = {}, {}
    max_position_error = 0.
    for index in target['selected']:
        position = target['positions'][index]
        donors = [(point, donor) for point, donor in donor_by_position.items()
                  if sum((a - b) ** 2 for a, b in zip(position, point)) < HEAD_POSITION_IMPORT_MAX_ERROR ** 2]
        assert len(donors) == 1, ('Production head position import requires one exact donor: '
            + json.dumps({'position': position, 'matching_donors': len(donors),
                          'threshold': HEAD_POSITION_IMPORT_MAX_ERROR}, separators=(',', ':')))
        point, donor = donors[0]
        mapping[index] = donor
        unique_mapping[position] = point
        max_position_error = max(max_position_error, max(abs(a - b) for a, b in zip(position, point)))
    assert len(unique_mapping) == len(set(unique_mapping.values())) == len(donor_by_position), 'Production head donor pairing is not bijective apart from UV seams'
    def topology(document, arrays, donor_map=None):
        indices = [row[0] for row in document.accessor(arrays['primitive']['indices'])]
        assert arrays['primitive'].get('mode', 4) == 4 and len(indices) % 3 == 0
        faces = Counter()
        for offset in range(0, len(indices), 3):
            triangle = indices[offset:offset + 3]
            if not all(index in arrays['selected'] for index in triangle): continue
            points = tuple(source['positions'][donor_map[index]] if donor_map else arrays['positions'][index] for index in triangle)
            faces[min(points, points[1:] + points[:1], points[2:] + points[:2])] += 1
        return faces
    source_faces, candidate_faces = topology(original, source), topology(refined, target, mapping)
    assert candidate_faces == source_faces, ('Production head donor oriented topology changed: '
        + json.dumps({'source_triangles': sum(source_faces.values()), 'candidate_triangles': sum(candidate_faces.values()),
                      'first_missing': list((source_faces - candidate_faces).items())[:1],
                      'first_extra': list((candidate_faces - source_faces).items())[:1]}, separators=(',', ':')))
    payload = bytearray(refined.binary)
    original_document = copy.deepcopy(refined.doc)
    target_attributes = target['primitive']['attributes']
    outside_before = _outside_head_fingerprint(refined, target['selected'])
    candidate_joint_indices = {name: index for index, name in enumerate(refined.joint_names)}
    component_counts, maximum_errors, first_differences = Counter(), {}, []
    rebuilt_sparse = 0
    def copy_rows(field, accessor, old_rows, source_rows):
        nonlocal rebuilt_sparse
        replacements = {index: source_rows[donor] for index, donor in mapping.items()}
        errors = [(index, component, before, after)
                  for index, row in replacements.items()
                  for component, (before, after) in enumerate(zip(old_rows[index], row)) if before != after]
        if errors:
            component_counts[field] = len(errors)
            maximum_errors[field] = max(abs(before - after) for _, _, before, after in errors)
            if len(first_differences) < 8:
                index, component, before, after = errors[0]
                first_differences.append({'field': field, 'position': target['positions'][index], 'component': component,
                                          'source': after, 'candidate': before})
        rebuilt_sparse += int(_write_rows(refined, payload, accessor, replacements))
    for field, semantic in [('positions', 'POSITION'), ('normals', 'NORMAL'), ('weights', 'WEIGHTS_0')]:
        assert refined.doc['accessors'][target_attributes[semantic]]['componentType'] == 5126
        copy_rows(field, target_attributes[semantic], target[field], source[field])
    mapped_joints = [tuple(candidate_joint_indices[original.joint_names[joint]] for joint in row) for row in source['joints']]
    copy_rows('joint_indices', target_attributes['JOINTS_0'], target['joints'], mapped_joints)
    for ordinal, descriptor in enumerate(target['primitive']['targets'][:30]):
        copy_rows(f'form_position_{ordinal}', descriptor['POSITION'], target['shapes'][ordinal], source['shapes'][ordinal])
        copy_rows(f'form_normal_{ordinal}', descriptor['NORMAL'], target['shape_normals'][ordinal], source['shape_normals'][ordinal])
    for descriptor in target['primitive']['targets'][30:]:
        if 'NORMAL' in descriptor:
            rebuilt_sparse += int(_write_rows(refined, payload, descriptor['NORMAL'], {index: (0., 0., 0.) for index in mapping}))
    if rebuilt_sparse:
        payload = _compact_views(refined.doc, payload)
    before_sha = hashlib.sha256(refined.raw).hexdigest()
    if payload != refined.binary or refined.doc != original_document:
        _save_glb(candidate, refined.doc, payload)
    assert Path(candidate).stat().st_size <= 20 * 1024 * 1024, 'Head retention exceeds the main GLB budget'
    accepted = validate_head(candidate, baseline, template)
    outside_after = _outside_head_fingerprint(_Document(candidate), target['selected'])
    assert outside_before == outside_after, 'Head retention changed body, UV, accessory, image or animation data'
    return {'source_commit': PRODUCTION_COMMIT, 'template': template, 'source_sha256': pin['sha256'],
            'candidate_sha256_before': before_sha, 'candidate_sha256_after': hashlib.sha256(Path(candidate).read_bytes()).hexdigest(),
            'position_import_threshold': HEAD_POSITION_IMPORT_MAX_ERROR, 'position_max_abs_error_before': max_position_error,
            'source_head_positions': len(donor_by_position), 'candidate_head_rows_with_uv_seams': len(mapping),
            'oriented_topology_identical_before': True, 'changed_components_before': dict(component_counts),
            'max_abs_error_by_field_before': maximum_errors, 'first_differences_before': first_differences,
            'sparse_accessors_rebuilt': rebuilt_sparse, 'exact_source_head_after': accepted['head_fingerprint'],
            'outside_head_fingerprint_before': outside_before, 'outside_head_fingerprint_after': outside_after}


def head_difference(original, refined):
    """Report exact mismatches by position without authorizing replacement."""
    fields = ('position', 'normal', 'skin_weights', 'body_forms', 'form_normals')
    source_by_position = {row[0]: row for row in original}
    candidate_by_position = {row[0]: row for row in refined}
    unmatched_source = sorted(source_by_position.keys() - candidate_by_position.keys())
    unmatched_candidate = sorted(candidate_by_position.keys() - source_by_position.keys())
    differences = []
    counts = Counter()
    maximum_errors = {}
    for position in sorted(source_by_position.keys() & candidate_by_position.keys()):
        source, row = source_by_position[position], candidate_by_position[position]
        for field, expected, values in zip(fields[1:], source[1:], row[1:]):
            if expected == values:
                continue
            counts[field] += 1
            evidence = {'position': position, 'field': field}
            if field == 'skin_weights':
                evidence['source'], evidence['candidate'] = expected, values
            else:
                before = expected if field == 'normal' else tuple(value for vector in expected for value in vector)
                after = values if field == 'normal' else tuple(value for vector in values for value in vector)
                changes = [(index, a, b) for index, (a, b) in enumerate(zip(before, after)) if a != b]
                if changes:
                    index, a, b = changes[0]
                    error = max(abs(a - b) for _, a, b in changes)
                    evidence['first_difference'] = {'index': index, 'source': a, 'candidate': b}
                    evidence['max_abs_error'] = error
                    maximum_errors[field] = max(maximum_errors.get(field, 0.), error)
            if len(differences) < 8: differences.append(evidence)
    return {'source_vertices_without_uv_seams': len(original), 'candidate_vertices_without_uv_seams': len(refined),
            'unmatched_source_positions': len(unmatched_source), 'unmatched_candidate_positions': len(unmatched_candidate),
            'first_unmatched_source_positions': unmatched_source[:4], 'first_unmatched_candidate_positions': unmatched_candidate[:4],
            'changed_fields': dict(counts), 'max_abs_error_by_field': maximum_errors,
            'first_differences': differences}


def validate_head(candidate: Path, baseline: Path, template: str) -> dict:
    """Compare actual candidate head data with frozen production bytes, not labels."""
    original, refined = _Document(baseline), _Document(candidate)
    pin = _pinned_baseline(original, template)
    original_rig, refined_rig = original.rest_rig(), refined.rest_rig()
    assert refined_rig == original_rig, ('Production bone names, parents, rest poses or inverse binds changed: '
        + json.dumps({'source_commit': PRODUCTION_COMMIT, 'template': template,
                      'source_sha256': pin['sha256'], 'candidate_sha256': hashlib.sha256(refined.raw).hexdigest(),
                      'rig_difference': rig_difference(original_rig, refined_rig)}, separators=(',', ':')))
    assert refined.body_transform() == original.body_transform(), 'Production body transform chain changed'
    old_vertices, old_triangles, old_appearance = original.head(False)
    vertices, triangles, appearance = refined.head(True)
    assert vertices == old_vertices, ('Production head positions, normals, 30 forms or skin weights changed: '
        + json.dumps({'source_commit': PRODUCTION_COMMIT, 'template': template, 'source_sha256': pin['sha256'],
                      'candidate_sha256': hashlib.sha256(refined.raw).hexdigest(),
                      'head_difference': head_difference(old_vertices, vertices)}, separators=(',', ':')))
    assert triangles == old_triangles, 'Production head topology changed'
    assert appearance == old_appearance, 'Production mannequin material changed'
    encoded = json.dumps({'vertices': sorted(vertices), 'triangles': sorted(triangles.items()),
                          'appearance': appearance}, separators=(',', ':')).encode()
    return {'source_commit': PRODUCTION_COMMIT, 'template': template,
            'source_sha256': pin['sha256'], 'source_bytes': pin['bytes'],
            'head_fingerprint': hashlib.sha256(encoded).hexdigest(),
            'head_vertices_without_uv_seams': len(vertices), 'head_triangles': sum(triangles.values()),
            'body_forms_identical': 30, 'rig_identical': True, 'material_identical': True,
            'facial_overlays': 0, 'head_joint_corrections': 0}
