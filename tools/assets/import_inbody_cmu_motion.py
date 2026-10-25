"""Retarget CMU 79_94 (real 60 Hz capture) onto the accepted InBody rig.

Offline Blender tool. This never changes the accepted mesh, morphs, blend or GLB.
Input motion is CMU ASF/AMC, not reconstructed or authored pose keyframes.
Run: blender -b --factory-startup --python-exit-code 1 --python \
  tools/assets/import_inbody_cmu_motion.py

ASF uses fixed XYZ axes, Y-up and directions in the unrotated global frame.
Column-vector FK is parent @ axis @ dof @ inverse(axis). Bone lengths remain
those of the target. A rest-direction basis correction maps the source T pose
onto MPFB's A pose, retaining the source's full-body world rotation and tempo.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import struct
import sys

import bpy
from mathutils import Euler, Matrix, Vector

ROOT = Path(__file__).resolve().parents[2]
SOURCE_TO_BLENDER = Matrix.Rotation(math.pi / 2, 3, 'X')
BLENDER_TO_GLTF = Matrix.Rotation(-math.pi / 2, 4, 'X')


def rotation_xyz(values):
    """Fixed-axis XYZ: column vectors are multiplied Rz @ Ry @ Rx."""
    return Euler(tuple(math.radians(v) for v in values), 'XYZ').to_matrix()


def parse_asf(path):
    bones = {}
    section = ''
    bone = None
    root = {}
    unit = None
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith(':'):
            section = line
            continue
        bits = line.split()
        key = bits[0]
        if section == ':units' and key == 'length':
            unit = float(bits[1])
        elif section == ':root':
            root[key] = bits[1:]
        elif section == ':bonedata':
            if key == 'begin':
                bone = {'dof': []}
            elif key == 'end':
                bones[bone['name']] = bone
            elif key in ('direction', 'axis'):
                bone[key] = Vector([float(v) for v in bits[1:4]])
                if key == 'axis' and bits[4] != 'XYZ':
                    raise ValueError('This pinned ASF requires XYZ axis order')
            elif key == 'length':
                bone[key] = float(bits[1])
            elif key == 'name':
                bone[key] = bits[1]
            elif key == 'dof':
                bone[key] = bits[1:]
        elif section == ':hierarchy' and key not in ('begin', 'end'):
            for child in bits[1:]:
                bones[child]['parent'] = key
    if root['order'] != ['TX', 'TY', 'TZ', 'RX', 'RY', 'RZ']:
        raise ValueError('Unexpected root channels')
    if root['axis'] != ['XYZ'] or any(float(x) for x in root['orientation']):
        raise ValueError('Unexpected root rest orientation')
    for bone in bones.values():
        bone['basis'] = rotation_xyz(bone['axis'])
    return bones, .0254 / unit


def parse_amc(path, bones):
    frames = []
    frame = None
    for raw in path.read_text().splitlines():
        bits = raw.split()
        if not bits or bits[0].startswith(('#', ':')):
            continue
        if bits[0].isdigit():
            if int(bits[0]) != len(frames) + 1:
                raise ValueError('AMC frames must be sequential')
            frame = {}
            frames.append(frame)
        else:
            name = bits[0]
            frame[name] = [float(v) for v in bits[1:]]
            count = 6 if name == 'root' else len(bones[name]['dof'])
            if len(frame[name]) != count:
                raise ValueError(f'Wrong DOF count: {name}')
    return frames


def forward_kinematics(bones, frame):
    rotations = {'root': rotation_xyz(frame['root'][3:])}
    positions = {'root': Vector(frame['root'][:3])}
    for name, bone in bones.items():
        values = dict(zip(bone['dof'], frame.get(name, [])))
        motion = rotation_xyz([values.get(axis, 0.) for axis in ('rx', 'ry', 'rz')])
        rotations[name] = rotations[bone['parent']] @ bone['basis'] @ motion @ bone['basis'].transposed()
        positions[name] = positions[bone['parent']] + rotations[name] @ (bone['direction'] * bone['length'])
    return rotations, positions


def read_glb(path):
    data = path.read_bytes()
    size, kind = struct.unpack_from('<II', data, 12)
    assert kind == 0x4e4f534a
    return json.loads(data[20:20 + size])


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_animation_glb(path, base, tracks, fps):
    """Use exact accepted node names/hierarchy, with no geometry duplication."""
    document = {key: copy.deepcopy(base[key]) for key in ('asset', 'scene', 'scenes', 'nodes')}
    for node in document['nodes']:
        for key in ('mesh', 'skin', 'weights'):
            node.pop(key, None)
    document['asset']['generator'] = 'InBody CMU79_94 offline retarget v1'
    payload = bytearray()
    views, accessors = [], []

    def accessor(rows, kind):
        width = {'SCALAR': 1, 'VEC3': 3, 'VEC4': 4}[kind]
        flat = [v for row in rows for v in row]
        offset = len(payload)
        payload.extend(struct.pack('<' + 'f' * len(flat), *flat))
        view = len(views)
        views.append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(flat) * 4})
        index = len(accessors)
        accessors.append({'bufferView': view, 'componentType': 5126, 'count': len(rows), 'type': kind,
                          'min': [min(row[i] for row in rows) for i in range(width)],
                          'max': [max(row[i] for row in rows) for i in range(width)]})
        return index

    length = len(next(iter(tracks.values()))['rotation'])
    times = accessor([[i / fps] for i in range(length)], 'SCALAR')
    samplers, channels = [], []
    for name, paths in tracks.items():
        node = next(i for i, n in enumerate(document['nodes']) if n['name'] == name)
        for channel, rows in paths.items():
            output = accessor(rows, 'VEC4' if channel == 'rotation' else 'VEC3')
            channels.append({'sampler': len(samplers), 'target': {'node': node, 'path': channel}})
            samplers.append({'input': times, 'output': output, 'interpolation': 'LINEAR'})
    document.update(buffers=[{'byteLength': len(payload)}], bufferViews=views, accessors=accessors,
                    animations=[{'name': 'cmu_flexing', 'samplers': samplers, 'channels': channels}])
    encoded = json.dumps(document, separators=(',', ':')).encode()
    encoded += b' ' * (-len(encoded) % 4)
    payload.extend(b'\0' * (-len(payload) % 4))
    total = 12 + 8 + len(encoded) + 8 + len(payload)
    result = struct.pack('<III', 0x46546c67, 2, total)
    result += struct.pack('<II', len(encoded), 0x4e4f534a) + encoded
    result += struct.pack('<II', len(payload), 0x004e4942) + payload
    path.write_bytes(result)
    return len(channels)


def retarget_map(rig, bones, reference_rotations):
    """World-space deform rotations, with A-pose to source T-pose corrections.

    Segmented upper/lower limbs share one alignment for the full anatomical
    segment. This avoids introducing bends at MPFB's twist/helper joints.
    Finger/thumb channels are not captured by CMU and intentionally ignored.
    """
    mapping = {name: ('root', Matrix.Identity(3)) for name in ('root', 'pelvis.L', 'pelvis.R')}
    for names, source in [(['spine05', 'spine04'], 'lowerback'), (['spine03', 'spine02'], 'upperback'),
                          (['spine01'], 'thorax'), (['neck01'], 'lowerneck'),
                          (['neck02', 'neck03'], 'upperneck'), (['head'], 'head')]:
        for name in names:
            mapping[name] = (source, Matrix.Identity(3))
    for side, prefix in [('L', 'l'), ('R', 'r')]:
        def frame(direction, reference):
            direction = direction.normalized()
            reference = (reference - direction * reference.dot(direction)).normalized()
            return Matrix((direction, reference, direction.cross(reference))).transposed()

        def alignment(start, end, source, normal=None):
            direction = rig.data.bones[end].head_local - rig.data.bones[start].head_local
            desired = SOURCE_TO_BLENDER @ bones[source]['direction']
            if normal is not None:
                hinge = SOURCE_TO_BLENDER @ bones[prefix + 'radius']['basis'].col[0]
                return frame(desired, hinge) @ frame(direction, normal).transposed()
            return direction.rotation_difference(desired).to_matrix()
        upper = rig.data.bones[f'lowerarm01.{side}'].head_local - rig.data.bones[f'upperarm01.{side}'].head_local
        lower = rig.data.bones[f'wrist.{side}'].head_local - rig.data.bones[f'lowerarm01.{side}'].head_local
        arm_hinge = upper.cross(lower).normalized()
        for part, first, last, source in [
            ('upperarm', f'upperarm01.{side}', f'lowerarm01.{side}', prefix + 'humerus'),
            ('lowerarm', f'lowerarm01.{side}', f'wrist.{side}', prefix + 'radius'),
            ('upperleg', f'upperleg01.{side}', f'lowerleg01.{side}', prefix + 'femur'),
            ('lowerleg', f'lowerleg01.{side}', f'foot.{side}', prefix + 'tibia'),
        ]:
            basis = alignment(first, last, source, arm_hinge if 'arm' in part else None)
            for index in (1, 2):
                mapping[f'{part}0{index}.{side}'] = (source, basis)
            if part == 'lowerarm':
                # Radius and wrist are two parts of the source forearm. Wrist
                # rotation contributes captured pronation; hand adds its bend.
                mapping[f'lowerarm02.{side}'] = (prefix + 'wrist', basis)
                mapping[f'wrist.{side}'] = (prefix + 'hand', basis)
        # Source clavicles attach at a different height. Keep target proportions;
        # their animated world rotation is copied without translating shoulders.
        for name in (f'clavicle.{side}', f'shoulder01.{side}'):
            mapping[name] = (prefix + 'clavicle', Matrix.Identity(3))
        foot = rig.data.bones[f'foot.{side}']
        toe = rig.data.bones[f'toe2-1.{side}']
        target = toe.head_local - foot.head_local
        # CMU warns the coarse foot/toe channels are noisy. The video starts in
        # a grounded stance, but the raw skeleton's left toe points 13 degrees
        # up. Calibrate a single constant rest basis against that stance: keep
        # captured yaw, align initial pitch/roll to the accepted flat sole, then
        # preserve every subsequent captured rotation delta. No foot is locked.
        source_foot = prefix + 'foot'
        initial = SOURCE_TO_BLENDER @ reference_rotations[source_foot] @ SOURCE_TO_BLENDER.transposed()
        initial_direction = SOURCE_TO_BLENDER @ (reference_rotations[source_foot] @ bones[source_foot]['direction'])
        yaw = math.atan2(initial_direction.y, initial_direction.x) - math.atan2(target.y, target.x)
        calibrated = Matrix.Rotation(yaw, 3, 'Z')
        mapping[f'foot.{side}'] = (source_foot, initial.transposed() @ calibrated)
        source_toes = prefix + 'toes'
        initial_toes = SOURCE_TO_BLENDER @ reference_rotations[source_toes] @ SOURCE_TO_BLENDER.transposed()
        toe_basis = initial_toes.transposed() @ calibrated
        for digit in range(1, 6):
            mapping[f'toe{digit}-1.{side}'] = (prefix + 'toes', toe_basis)
    return mapping


def apply_frame(rig, mapping, rotations, root_position):
    world = {name: SOURCE_TO_BLENDER @ rotation @ SOURCE_TO_BLENDER.transposed()
             for name, rotation in rotations.items()}
    for bone in rig.pose.bones:
        bone.rotation_mode = 'QUATERNION'
        bone.matrix_basis = Matrix.Identity(4)
    # Blender's data.bones iteration is parent-first for this accepted rig.
    posed = {}
    for bone in rig.pose.bones:
        rest = bone.bone.matrix_local
        if bone.parent:
            local_rest = bone.parent.bone.matrix_local.inverted() @ rest
            inherited = posed[bone.parent.name] @ local_rest
        else:
            inherited = rest.copy()
            inherited.translation = root_position
        if bone.name in mapping:
            source, basis = mapping[bone.name]
            orientation = world[source] @ basis @ rest.to_3x3()
            desired = orientation.to_4x4()
            desired.translation = inherited.translation
        else:
            desired = inherited
            # CMU did not capture fingers/thumbs (database homepage). Use one
            # explicitly authored static relaxed grip; it is not mocap data.
            if bone.name.startswith('finger'):
                digit = int(bone.name[6])
                joint = int(bone.name[8])
                side = bone.name[-1]
                if digit == 1:
                    angles = (8., 0., -16. * (1 if side == 'L' else -1)) if joint == 1 else (10., 0., 0.)
                else:
                    angles = ([60., 75., 35.][joint - 1] * .4, 0., 0.)
                desired = desired @ rotation_xyz(angles).to_4x4()
        posed[bone.name] = desired
        if bone.parent:
            local_rest = bone.parent.bone.matrix_local.inverted() @ rest
            local = posed[bone.parent.name].inverted() @ desired
            bone.matrix_basis = local_rest.inverted() @ local
        else:
            bone.matrix_basis = rest.inverted() @ desired
    bpy.context.view_layer.update()
    return posed


def make_contact_sheet(rig, obj, mapping, samples, path):
    """Render actual skin poses in the existing accepted studio for review."""
    scene = bpy.context.scene
    scene.render.resolution_x = 520
    scene.render.resolution_y = 720
    scene.render.resolution_percentage = 100
    scene.cycles.samples = 16
    scene.render.image_settings.file_format = 'PNG'
    scene.render.image_settings.color_mode = 'RGBA'
    for key in obj.data.shape_keys.key_blocks[1:]:
        key.value = float(key.name == 'shape_f1_m4')
    # Keep preview and original model materials/geometry intact in memory only.
    camera = scene.camera
    camera.location = (.9, -5., 1.1)
    target = Vector((0, 0, .90))
    camera.rotation_euler = (target - camera.location).to_track_quat('-Z', 'Y').to_euler()
    for index, (time, rotations, root_position) in enumerate(samples):
        apply_frame(rig, mapping, rotations, root_position)
        scene.render.filepath = str(path / f'pose-{index:02d}-{time:.2f}.png')
        bpy.ops.render.render(write_still=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, default=ROOT / 'runtime/inbody-character/cmu/source')
    parser.add_argument('--blend', type=Path, default=ROOT / 'runtime/inbody-character/review/character.blend')
    parser.add_argument('--base', type=Path, default=ROOT / 'runtime/inbody-character/assets/character.glb')
    parser.add_argument('--output', type=Path, default=ROOT / 'runtime/inbody-character/assets')
    parser.add_argument('--review', type=Path, default=ROOT / 'runtime/inbody-character/cmu/review')
    parser.add_argument('--render', action='store_true')
    args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else [])
    args.output.mkdir(parents=True, exist_ok=True)
    args.review.mkdir(parents=True, exist_ok=True)
    source_asf = args.source / '79.asf'
    source_amc = args.source / '79_94.amc'
    original_base_hash, original_blend_hash = sha(args.base), sha(args.blend)
    bones, source_meter_scale = parse_asf(source_asf)
    frames = parse_amc(source_amc, bones)
    fps = 60
    source_c3d = args.source / '79_94.c3d'
    if source_c3d.exists():
        header = source_c3d.read_bytes()[:512]
        if struct.unpack_from('<f', header, 20)[0] != fps:
            raise ValueError('C3D capture rate disagrees with the catalog')
        if struct.unpack_from('<H', header, 8)[0] - struct.unpack_from('<H', header, 6)[0] + 1 != len(frames):
            raise ValueError('C3D and AMC frame counts differ')
    bpy.ops.wm.open_mainfile(filepath=str(args.blend.resolve()))
    rig = bpy.data.objects['InBodySkeleton']
    obj = bpy.data.objects['InBodyCharacter']
    rig.animation_data_clear()
    for bone in rig.pose.bones:
        for constraint in list(bone.constraints):
            bone.constraints.remove(constraint)
    reference_rotations, _ = forward_kinematics(bones, frames[0])
    mapping = retarget_map(rig, bones, reference_rotations)
    root_rest = rig.data.bones['root'].head_local.copy()
    first_root = Vector(frames[0]['root'][:3])
    # Match actor leg length to the target; every captured root delta is kept.
    leg_length = sum(bones[name]['length'] for name in ('lfemur', 'ltibia'))
    target_leg = sum((rig.data.bones[end].head_local - rig.data.bones[start].head_local).length
                     for start, end in [('upperleg01.L', 'lowerleg01.L'), ('lowerleg01.L', 'foot.L')])
    translation_scale = target_leg / leg_length
    tracks = {bone.name: {'translation': [], 'rotation': [], 'scale': []} for bone in rig.pose.bones}
    review_samples = []
    previous_quats = {}
    root_positions = []
    landmarks = []
    ground_shift = None
    for index, frame in enumerate(frames):
        rotations, positions = forward_kinematics(bones, frame)
        root = root_rest + SOURCE_TO_BLENDER @ (Vector(frame['root'][:3]) - first_root) * translation_scale
        posed = apply_frame(rig, mapping, rotations, root)
        # One constant ground alignment preserves subsequent pelvis/feet motion.
        if ground_shift is None:
            evaluated = obj.evaluated_get(bpy.context.evaluated_depsgraph_get())
            ground_shift = -min(vertex.co.z for vertex in evaluated.data.vertices)
        root.z += ground_shift
        posed = apply_frame(rig, mapping, rotations, root)
        root_positions.append(list(root))
        for bone in rig.pose.bones:
            local = posed[bone.parent.name].inverted() @ posed[bone.name] if bone.parent else BLENDER_TO_GLTF @ posed[bone.name]
            translation, quaternion, scale = local.decompose()
            if bone.name in previous_quats and quaternion.dot(previous_quats[bone.name]) < 0:
                quaternion.negate()
            previous_quats[bone.name] = quaternion.copy()
            tracks[bone.name]['translation'].append(list(translation))
            tracks[bone.name]['rotation'].append([quaternion.x, quaternion.y, quaternion.z, quaternion.w])
            tracks[bone.name]['scale'].append(list(scale))
        if index in (0, 180, 300, 480, 660, 840, len(frames) - 1):
            review_samples.append((index / fps, rotations, root.copy()))
            evaluated = obj.evaluated_get(bpy.context.evaluated_depsgraph_get())
            landmarks.append({'time': index / fps,
                              'sole_min_z': min(vertex.co.z for vertex in evaluated.data.vertices),
                              'target': {name: list(posed[name].translation) for name in ['root', 'upperarm01.L', 'lowerarm01.L', 'wrist.L', 'upperarm01.R', 'lowerarm01.R', 'wrist.R', 'foot.L', 'foot.R']},
                              'source': {name: list(SOURCE_TO_BLENDER @ position * translation_scale) for name, position in positions.items()}})
    output = args.output / 'cmu-flexing.glb'
    channels = write_animation_glb(output, read_glb(args.base), tracks, fps)
    moving = {name: max(2 * math.acos(min(1., abs(sum(a * b for a, b in zip(row, paths['rotation'][0]))))) for row in paths['rotation'])
              for name, paths in tracks.items()}
    manifest = {
        'version': 'inbody-cmu-retarget-v1', 'clip': 'cmu_flexing', 'duration': (len(frames) - 1) / fps,
        'fps': fps, 'frames': len(frames), 'bytes': output.stat().st_size, 'sha256': sha(output),
        'source': {'name': 'CMU Graphics Lab Motion Capture Database', 'subject': '79', 'trial': '94',
                   'description': 'Flexing', 'url': 'https://mocap.cs.cmu.edu/search.php?subjectnumber=79',
                   'fps_evidence': 'CMU catalog and original C3D header, 60 Hz / 935 frames',
                   'files': {path.name: sha(path) for path in (source_asf, source_amc, source_c3d) if path.exists()},
                   'license_url': 'https://mocap.cs.cmu.edu/',
                   'license_note': 'May be used for any purpose with CMU attribution; no standalone resale.'},
        'target': {'base_sha256': original_base_hash, 'blend_sha256': original_blend_hash,
                   'bones': len(tracks), 'mapped_bones': len(mapping), 'channels': channels},
        'retarget': {'source_units_meters': source_meter_scale, 'translation_scale_meters_per_source_unit': translation_scale,
                     'root_channels': 'all six preserved; constant initial position and ground alignment only',
                     'ground_shift_meters': ground_shift, 'source_to_blender': '(x,y,z) -> (x,-z,y)',
                     'rest_correction': 'source T pose to target A pose using full anatomical segment direction',
                     'fingers': 'Static relaxed grip; CMU states finger/thumb channels were not captured, so those channels are not mapped',
                     'feet': 'Captured femur, tibia, foot and coarse toe rotations; no planted-foot freeze or invented steps',
                     'foot_rest_calibration': 'Constant initial grounded-stance basis: retain captured yaw, align sole pitch/roll at frame1, retain all subsequent rotation deltas',
                     'limitations': ['No individual finger capture', 'Different target proportions can cause contact drift', 'Source hand and foot channels can be noisy; no cleanup synthesized']},
        'metrics': {'moving_bones': [name for name, angle in moving.items() if angle > .002],
                    'root_range_meters': [max(p[i] for p in root_positions) - min(p[i] for p in root_positions) for i in range(3)],
                    'rotation_range_degrees': {name: math.degrees(angle) for name, angle in moving.items() if name in mapping}},
    }
    (args.output / 'cmu-flexing.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (args.review / 'landmarks.json').write_text(json.dumps(landmarks, indent=2) + '\n')
    if args.render:
        make_contact_sheet(rig, obj, mapping, review_samples, args.review)
    assert sha(args.base) == original_base_hash and sha(args.blend) == original_blend_hash
    print('CMU_EXPORT', json.dumps({'duration': manifest['duration'], 'frames': len(frames), 'bytes': output.stat().st_size, 'moving_bones': len(manifest['metrics']['moving_bones']), 'root_range_meters': manifest['metrics']['root_range_meters']}))


if __name__ == '__main__':
    main()
