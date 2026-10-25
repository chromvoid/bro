"""Offline transfer of Quaternius' CC0 Idle_Loop onto the accepted InBody rig.

blender -b --factory-startup --python-exit-code 1 --python \
  tools/assets/import_inbody_game_idle.py -- --render

The original 2.5-second / 30 Hz loop is retained. Only the target skeleton's
local animation transforms are exported: accepted mesh/morph assets are never
rewritten. Source foot positions drive a two-segment contact correction for the
target's different proportions. No random motion is added.
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
from mathutils import Matrix, Vector

ROOT = Path(__file__).resolve().parents[2]
BLENDER_TO_GLTF = Matrix.Rotation(-math.pi / 2, 4, 'X')
SOURCE_CLIP = 'Idle_Loop'
CLIP = 'game_idle'
FPS = 30
DURATION = 2.5
SOURCE_SHA256 = '69591853d817488edaa8fd9bf8fc1d821eaeaf789f8627b3cd23b41c4ed67997'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_glb(path):
    data = path.read_bytes()
    size, kind = struct.unpack_from('<II', data, 12)
    assert kind == 0x4e4f534a
    return json.loads(data[20:20 + size])


def write_glb(path, base, tracks):
    document = {key: copy.deepcopy(base[key]) for key in ('asset', 'scene', 'scenes', 'nodes')}
    for node in document['nodes']:
        for key in ('mesh', 'skin', 'weights'):
            node.pop(key, None)
    document['asset']['generator'] = 'InBody Quaternius idle retarget v1'
    payload, views, accessors = bytearray(), [], []

    def accessor(rows, kind):
        width = {'SCALAR': 1, 'VEC3': 3, 'VEC4': 4}[kind]
        flat = [value for row in rows for value in row]
        offset = len(payload)
        payload.extend(struct.pack('<' + 'f' * len(flat), *flat))
        view = len(views)
        views.append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(flat) * 4})
        index = len(accessors)
        accessors.append({'bufferView': view, 'componentType': 5126, 'count': len(rows), 'type': kind,
                          'min': [min(row[i] for row in rows) for i in range(width)],
                          'max': [max(row[i] for row in rows) for i in range(width)]})
        return index

    count = len(next(iter(tracks.values()))['rotation'])
    times = accessor([[i / FPS] for i in range(count)], 'SCALAR')
    samplers, channels = [], []
    for name, paths in tracks.items():
        node = next(i for i, node in enumerate(document['nodes']) if node['name'] == name)
        for channel, rows in paths.items():
            output = accessor(rows, 'VEC4' if channel == 'rotation' else 'VEC3')
            channels.append({'sampler': len(samplers), 'target': {'node': node, 'path': channel}})
            samplers.append({'input': times, 'output': output, 'interpolation': 'LINEAR'})
    document.update(buffers=[{'byteLength': len(payload)}], bufferViews=views, accessors=accessors,
                    animations=[{'name': CLIP, 'samplers': samplers, 'channels': channels}])
    encoded = json.dumps(document, separators=(',', ':')).encode()
    encoded += b' ' * (-len(encoded) % 4)
    payload.extend(b'\0' * (-len(payload) % 4))
    length = 12 + 8 + len(encoded) + 8 + len(payload)
    path.write_bytes(struct.pack('<III', 0x46546c67, 2, length)
                     + struct.pack('<II', len(encoded), 0x4e4f534a) + encoded
                     + struct.pack('<II', len(payload), 0x004e4942) + payload)


def basis(direction, normal):
    direction = direction.normalized()
    normal = normal - direction * normal.dot(direction)
    if normal.length < .00001:
        raise ValueError('Unstable rest-pose plane')
    normal.normalize()
    return Matrix((direction, normal, direction.cross(normal))).transposed()


def build_mapping(target, source, initial):
    identity = Matrix.Identity(3)
    mapping = {name: ('pelvis', identity) for name in ['root', 'pelvis.L', 'pelvis.R']}
    for names, source_name in [(['spine05', 'spine04'], 'spine_01'),
                              (['spine03', 'spine02'], 'spine_02'), (['spine01'], 'spine_03'),
                              (['neck01', 'neck02', 'neck03'], 'neck_01'), (['head'], 'Head')]:
        for name in names:
            mapping[name] = (source_name, identity)
    for side, suffix in [('L', 'l'), ('R', 'r')]:
        def target_direction(start, end):
            return target.data.bones[end + '.' + side].head_local - target.data.bones[start + '.' + side].head_local

        def source_direction(start, end):
            return source.data.bones[end + '_' + suffix].head_local - source.data.bones[start + '_' + suffix].head_local

        target_hinge = target_direction('upperarm01', 'lowerarm01').cross(target_direction('lowerarm01', 'wrist'))
        source_hinge = source_direction('upperarm', 'lowerarm').cross(source_direction('lowerarm', 'hand'))
        for part, start, end, source_name, source_end in [
            ('upperarm', 'upperarm01', 'lowerarm01', 'upperarm', 'lowerarm'),
            ('lowerarm', 'lowerarm01', 'wrist', 'lowerarm', 'hand'),
            ('upperleg', 'upperleg01', 'lowerleg01', 'thigh', 'calf'),
            ('lowerleg', 'lowerleg01', 'foot', 'calf', 'foot'),
        ]:
            tv, sv = target_direction(start, end), source_direction(source_name, source_end)
            correction = (basis(sv, source_hinge) @ basis(tv, target_hinge).transposed()
                          if 'arm' in part else tv.rotation_difference(sv).to_matrix())
            for segment in (1, 2):
                mapping[f'{part}0{segment}.{side}'] = (source_name + '_' + suffix, correction)
        for name in ['clavicle', 'shoulder01']:
            mapping[name + '.' + side] = ('clavicle_' + suffix, identity)
        # Preserve the palm plane as well as its direction. One-vector rotations
        # otherwise leave the palm with an arbitrary roll on an A-pose target.
        thand = target_direction('wrist', 'finger3-1')
        shand = source_direction('hand', 'middle_01')
        tnormal = target_direction('wrist', 'finger2-1').cross(target_direction('wrist', 'finger5-1'))
        snormal = source_direction('hand', 'index_01').cross(source_direction('hand', 'pinky_01'))
        hand_basis = basis(shand, snormal) @ basis(thand, tnormal).transposed()
        mapping['wrist.' + side] = ('hand_' + suffix, hand_basis)
        for digit, source_digit in [(1, 'thumb'), (2, 'index'), (3, 'middle'), (4, 'ring'), (5, 'pinky')]:
            for joint in range(1, 4):
                target_name = f'finger{digit}-{joint}.{side}'
                source_name = f'{source_digit}_{joint:02d}_{suffix}'
                tb, sb = target.data.bones[target_name], source.data.bones[source_name]
                correction = basis(sb.tail_local - sb.head_local, snormal) @ basis(tb.tail_local - tb.head_local, tnormal).transposed()
                mapping[target_name] = (source_name, correction)
        # The source starts in a stationary grounded stance. Preserve its yaw
        # and subsequent orientation deltas using the target's flat rest sole.
        foot_name = 'foot_' + suffix
        target_forward = target_direction('foot', 'toe2-1')
        source_forward = initial['ball_' + suffix].translation - initial[foot_name].translation
        yaw = math.atan2(source_forward.y, source_forward.x) - math.atan2(target_forward.y, target_forward.x)
        flat = Matrix.Rotation(yaw, 3, 'Z')
        for names, source_name in [(['foot.' + side], foot_name),
                                   ([f'toe{i}-1.{side}' for i in range(1, 6)], 'ball_' + suffix)]:
            delta = initial[source_name].to_3x3() @ source.data.bones[source_name].matrix_local.to_3x3().inverted()
            for name in names:
                mapping[name] = (source_name, delta.inverted() @ flat)
    return mapping


def sample_source(source, time):
    frame = time * bpy.context.scene.render.fps / bpy.context.scene.render.fps_base
    bpy.context.scene.frame_set(math.floor(frame), subframe=frame % 1)
    bpy.context.view_layer.update()
    return {bone.name: bone.matrix.copy() for bone in source.pose.bones}


def apply_pose(target, source, mapping, captured, root):
    world = {name: pose.to_3x3() @ source.data.bones[name].matrix_local.to_3x3().inverted()
             for name, pose in captured.items()}
    posed = {}
    for bone in target.pose.bones:
        bone.rotation_mode = 'QUATERNION'
        rest = bone.bone.matrix_local
        local_rest = bone.parent.bone.matrix_local.inverted() @ rest if bone.parent else rest
        inherited = posed[bone.parent.name] @ local_rest if bone.parent else rest.copy()
        if not bone.parent:
            inherited.translation = root
        if bone.name in mapping:
            source_name, correction = mapping[bone.name]
            desired = (world[source_name] @ correction @ rest.to_3x3()).to_4x4()
            desired.translation = inherited.translation
        else:
            desired = inherited
        posed[bone.name] = desired
        local = posed[bone.parent.name].inverted() @ desired if bone.parent else desired
        bone.matrix_basis = local_rest.inverted() @ local
    bpy.context.view_layer.update()


def aim_chain(rig, bone_name, tip_name, destination):
    bone = rig.pose.bones[bone_name]
    origin = bone.head.copy()
    rotation = (rig.pose.bones[tip_name].head - origin).rotation_difference(destination - origin)
    bone.matrix = Matrix.Translation(origin) @ rotation.to_matrix().to_4x4() @ Matrix.Translation(-origin) @ bone.matrix
    bpy.context.view_layer.update()


def ankle_contact(rig, side, goal):
    upper = rig.pose.bones['upperleg01.' + side]
    lower = rig.pose.bones['lowerleg01.' + side]
    foot = rig.pose.bones['foot.' + side]
    foot_rotation = foot.matrix.to_quaternion()
    hip, knee, ankle = upper.head.copy(), lower.head.copy(), foot.head.copy()
    a, b = (knee - hip).length, (ankle - knee).length
    direction = goal - hip
    distance = min(direction.length, a + b - .000001)
    direction.normalize()
    along = (a * a - b * b + distance * distance) / (2 * distance)
    height = math.sqrt(max(0., a * a - along * along))
    pole = knee - hip
    pole -= direction * pole.dot(direction)
    if pole.length < .00001:
        pole = Vector((0., -1., 0.))
        pole -= direction * pole.dot(direction)
    destination_knee = hip + direction * along + pole.normalized() * height
    aim_chain(rig, 'upperleg01.' + side, 'lowerleg01.' + side, destination_knee)
    aim_chain(rig, 'lowerleg01.' + side, 'foot.' + side, goal)
    foot_matrix = foot_rotation.to_matrix().to_4x4()
    foot_matrix.translation = foot.head.copy()
    foot.matrix = foot_matrix
    bpy.context.view_layer.update()
    return (foot.head - goal).length


def render_reviews(target, source, mesh, source_meshes, mapping, samples, destination):
    scene = bpy.context.scene
    scene.render.resolution_x, scene.render.resolution_y = 440, 660
    scene.render.resolution_percentage = 100
    scene.cycles.samples = 16
    scene.render.image_settings.file_format = 'PNG'
    scene.render.image_settings.color_mode = 'RGBA'
    for key in mesh.data.shape_keys.key_blocks[1:]:
        key.value = float(key.name == 'shape_f1_m4')
    for kind in ['source', 'target']:
        mesh.hide_render = kind == 'source'
        for obj in source_meshes:
            obj.hide_render = kind != 'source'
        for view, location in [('front', (0, -5, .97)), ('three-quarter', (2.6, -4.4, 1.04))]:
            camera = scene.camera
            camera.location = location
            camera.rotation_euler = (Vector((0, 0, .9)) - camera.location).to_track_quat('-Z', 'Y').to_euler()
            for index, sample in enumerate(samples):
                sample_source(source, sample['time'])
                for bone in target.pose.bones:
                    bone.matrix_basis = Matrix(sample['basis'][bone.name])
                bpy.context.view_layer.update()
                scene.render.filepath = str(destination / f'{kind}-{view}-{index}-{sample["time"]:.2f}.png')
                bpy.ops.render.render(write_still=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, default=ROOT / 'runtime/inbody-character/idle-candidates/quaternius/Universal Animation Library[Standard]/Unreal-Godot/UAL1_Standard.glb')
    parser.add_argument('--blend', type=Path, default=ROOT / 'runtime/inbody-character/review/character.blend')
    parser.add_argument('--base', type=Path, default=ROOT / 'runtime/inbody-character/assets/character.glb')
    parser.add_argument('--output', type=Path, default=ROOT / 'runtime/inbody-character/assets')
    parser.add_argument('--review', type=Path, default=ROOT / 'runtime/inbody-character/idle-candidates/quaternius/retarget-review')
    parser.add_argument('--render', action='store_true')
    args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else [])
    args.output.mkdir(parents=True, exist_ok=True)
    args.review.mkdir(parents=True, exist_ok=True)
    if sha(args.source) != SOURCE_SHA256:
        raise ValueError('Unexpected Quaternius Standard v3 source; verify provenance before changing the pin')
    base_hash, blend_hash = sha(args.base), sha(args.blend)
    bpy.ops.wm.open_mainfile(filepath=str(args.blend.resolve()))
    target, mesh = bpy.data.objects['InBodySkeleton'], bpy.data.objects['InBodyCharacter']
    target.animation_data_clear()
    existing = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(args.source.resolve()))
    imported = set(bpy.data.objects) - existing
    source = next(obj for obj in imported if obj.type == 'ARMATURE')
    source_meshes = [obj for obj in imported if obj.type == 'MESH']
    for track in source.animation_data.nla_tracks:
        track.mute = True
    action = bpy.data.actions[SOURCE_CLIP]
    source.animation_data.action = action
    source.animation_data.action_slot = action.slots[0]
    # Blender imports at the scene FPS, retaining fractional keyframe times.
    duration = (action.frame_range[1] - action.frame_range[0]) / bpy.context.scene.render.fps
    if abs(duration - DURATION) > .00001:
        raise ValueError(f'Unexpected Idle_Loop duration {duration}')
    initial = sample_source(source, 0.)
    mapping = build_mapping(target, source, initial)
    source_leg = sum((source.data.bones[b].head_local - source.data.bones[a].head_local).length
                     for a, b in [('thigh_l', 'calf_l'), ('calf_l', 'foot_l')])
    target_leg = sum((target.data.bones[b].head_local - target.data.bones[a].head_local).length
                     for a, b in [('upperleg01.L', 'lowerleg01.L'), ('lowerleg01.L', 'foot.L')])
    scale = target_leg / source_leg
    root_rest = target.data.bones['root'].head_local.copy()
    apply_pose(target, source, mapping, initial, root_rest)
    ground = -min(v.co.z for v in mesh.evaluated_get(bpy.context.evaluated_depsgraph_get()).data.vertices)
    root_rest.z += ground
    apply_pose(target, source, mapping, initial, root_rest)
    anchors = {side: target.pose.bones['foot.' + side].head.copy() for side in ['L', 'R']}
    tracks = {bone.name: {'translation': [], 'rotation': [], 'scale': []} for bone in target.pose.bones}
    previous = {}
    reviews, feet, root_positions = [], [], []
    contact_error = 0.
    for frame in range(round(DURATION * FPS) + 1):
        time = frame / FPS
        captured = sample_source(source, time)
        root = root_rest + (captured['pelvis'].translation - initial['pelvis'].translation) * scale
        apply_pose(target, source, mapping, captured, root)
        for side, suffix in [('L', 'l'), ('R', 'r')]:
            goal = anchors[side] + (captured['foot_' + suffix].translation - initial['foot_' + suffix].translation) * scale
            contact_error = max(contact_error, ankle_contact(target, side, goal))
        poses = {bone.name: bone.matrix.copy() for bone in target.pose.bones}
        for bone in target.pose.bones:
            local = poses[bone.parent.name].inverted() @ poses[bone.name] if bone.parent else BLENDER_TO_GLTF @ poses[bone.name]
            position, quaternion, scaling = local.decompose()
            if bone.name in previous and quaternion.dot(previous[bone.name]) < 0:
                quaternion.negate()
            previous[bone.name] = quaternion.copy()
            tracks[bone.name]['translation'].append(list(position))
            tracks[bone.name]['rotation'].append([quaternion.x, quaternion.y, quaternion.z, quaternion.w])
            tracks[bone.name]['scale'].append(list(scaling))
        feet.append({side: list(poses['foot.' + side].translation) for side in ['L', 'R']})
        root_positions.append(list(root))
        if frame in [0, 19, 38, 57]:
            reviews.append({'time': time, 'basis': {bone.name: [list(row) for row in bone.matrix_basis] for bone in target.pose.bones}})
    path = args.output / 'game-idle.glb'
    write_glb(path, read_glb(args.base), tracks)
    endpoint = max(abs(a - b) for paths in tracks.values() for rows in paths.values() for a, b in zip(rows[0], rows[-1]))
    metrics = {'max_ankle_contact_error_meters': contact_error,
               'endpoint_component_delta': endpoint,
               'root_range_meters': [max(v[i] for v in root_positions) - min(v[i] for v in root_positions) for i in range(3)],
               'foot_range_meters': {side: [max(f[side][i] for f in feet) - min(f[side][i] for f in feet) for i in range(3)] for side in ['L', 'R']}}
    metadata = {'version': 'inbody-game-idle-v1', 'clip': CLIP, 'duration': DURATION, 'fps': FPS, 'frames': 76,
                'bytes': path.stat().st_size, 'sha256': sha(path),
                'source': {'author': 'Quaternius', 'package': 'Universal Animation Library Standard v3',
                           'clip': SOURCE_CLIP, 'url': 'https://quaternius.itch.io/universal-animation-library',
                           'license': 'CC0-1.0', 'sha256': sha(args.source)},
                'target': {'template': target.get('body_template', 'male'), 'base_sha256': base_hash, 'blend_sha256': blend_hash, 'bones': len(tracks), 'channels': len(tracks) * 3},
                'retarget': {'source_timing_preserved': True, 'loop': True, 'translation_scale': scale,
                             'ground_offset_meters': ground, 'limbs': 'Rest directions plus elbow/palm planes',
                             'contacts': 'Target two-segment leg IK follows original source ankle deltas',
                             'fingers': 'Original authored finger rotations mapped by rest-palm plane'},
                'metrics': metrics}
    (args.output / 'game-idle.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (args.review / 'metrics.json').write_text(json.dumps(metrics, indent=2) + '\n')
    if args.render:
        render_reviews(target, source, mesh, source_meshes, mapping, reviews, args.review)
    assert sha(args.base) == base_hash and sha(args.blend) == blend_hash
    print('GAME_IDLE_EXPORT', json.dumps({'duration': duration, 'bytes': path.stat().st_size, **metrics}))


if __name__ == '__main__':
    main()
