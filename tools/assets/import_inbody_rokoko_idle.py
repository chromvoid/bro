"""Retarget the licensed first Rokoko LookingAround take, offline only.

blender -b --factory-startup --python-exit-code 1 --python \
  tools/assets/import_inbody_rokoko_idle.py -- --source /path/to/Idle_LookingAround_MIXAMO_769.fbx --render

The two FBX calibration frames are removed. The selected standing interval
(source frames 150..960) retains its authored 30 Hz timing and poses; entrance
and exit steps stay in the original FBX, not in the repeating menu idle. The animation-only GLB leaves accepted mesh
and morph assets untouched. This is not a seamless loop; the viewer may overlap
its end with its start. Source motion is evaluated in WORLD coordinates because
Rokoko exports Mixamo Hips as the animated armature object, not as a pose bone.
"""
from __future__ import annotations
import argparse
import copy
import importlib.util
import json
import math
from pathlib import Path
import sys
import struct

import bpy
from mathutils import Matrix, Quaternion, Vector

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('inbody_game_helpers', Path(__file__).with_name('import_inbody_game_idle.py'))
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
FPS = 30
CLIP = 'rokoko_lookaround'
SOURCE_SHA256 = '0c967adac22b5b2337c26ea36272b6ef089f4ea2be22e873caceeab496ead595'
PREFIX = 'mixamorig:'


def write_clip(path, base, tracks):
    document = {key: copy.deepcopy(base[key]) for key in ('asset', 'scene', 'scenes', 'nodes')}
    for node in document['nodes']:
        for key in ('mesh', 'skin', 'weights'):
            node.pop(key, None)
    document['asset']['generator'] = 'InBody Rokoko LookingAround retarget v1'
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


def rigid(matrix):
    location, rotation, _ = matrix.decompose()
    result = rotation.to_matrix().to_4x4()
    result.translation = location
    return result


def capture(source, frame):
    bpy.context.scene.frame_set(math.floor(frame), subframe=frame % 1)
    bpy.context.view_layer.update()
    result = {bone.name.removeprefix(PREFIX): rigid(source.matrix_world @ bone.matrix) for bone in source.pose.bones}
    result['Hips'] = rigid(source.matrix_world)
    return result


def rest_transforms(source):
    capture(source, 1)
    result = {bone.name.removeprefix(PREFIX): rigid(source.matrix_world @ bone.matrix_local) for bone in source.data.bones}
    result['Hips'] = rigid(source.matrix_world)
    return result


def mapping_for(target, rest):
    identity = Matrix.Identity(3)
    result = {name: ('Hips', identity) for name in ['root', 'pelvis.L', 'pelvis.R']}
    for targets, source in [(['spine05', 'spine04'], 'Spine'),
                            (['spine03', 'spine02'], 'Spine1'), (['spine01'], 'Spine2'),
                            (['neck01', 'neck02', 'neck03'], 'Neck'), (['head'], 'Head')]:
        for name in targets:
            result[name] = source, identity
    for side, source_side in [('L', 'Left'), ('R', 'Right')]:
        def td(start, end):
            return target.data.bones[end + '.' + side].head_local - target.data.bones[start + '.' + side].head_local
        def sd(start, end):
            return rest[source_side + end].translation - rest[source_side + start].translation
        target_hinge = td('upperarm01', 'lowerarm01').cross(td('lowerarm01', 'wrist'))
        # Mixamo's T-pose elbows are exactly straight: cross(upper, lower) is
        # numerically undefined. The signed sagittal hinge follows its palms.
        source_hinge = Vector((0, 0, -1 if side == 'L' else 1))
        for part, ts, te, ss, se in [('upperarm', 'upperarm01', 'lowerarm01', 'Arm', 'ForeArm'),
                                    ('lowerarm', 'lowerarm01', 'wrist', 'ForeArm', 'Hand'),
                                    ('upperleg', 'upperleg01', 'lowerleg01', 'UpLeg', 'Leg'),
                                    ('lowerleg', 'lowerleg01', 'foot', 'Leg', 'Foot')]:
            tv, sv = td(ts, te), sd(ss, se)
            correction = (helpers.basis(sv, source_hinge) @ helpers.basis(tv, target_hinge).transposed()
                          if 'arm' in part else tv.rotation_difference(sv).to_matrix())
            for segment in (1, 2):
                result[f'{part}0{segment}.{side}'] = source_side + ss, correction
        for name in ['clavicle', 'shoulder01']:
            result[name + '.' + side] = source_side + 'Shoulder', identity
        tn = td('wrist', 'finger2-1').cross(td('wrist', 'finger5-1'))
        sn = sd('Hand', 'HandIndex1').cross(sd('Hand', 'HandPinky1'))
        palm = helpers.basis(sd('Hand', 'HandMiddle1'), sn) @ helpers.basis(td('wrist', 'finger3-1'), tn).transposed()
        result['wrist.' + side] = source_side + 'Hand', palm
        for digit, source_digit in [(1, 'Thumb'), (2, 'Index'), (3, 'Middle'), (4, 'Ring'), (5, 'Pinky')]:
            for joint in range(1, 4):
                name = f'finger{digit}-{joint}.{side}'
                source_name = source_side + f'Hand{source_digit}{joint}'
                tb = target.data.bones[name]
                source_bone = bpy.data.objects['mixamorig:Hips'].data.bones[PREFIX + source_name]
                source_vector = bpy.data.objects['mixamorig:Hips'].matrix_world.to_3x3() @ (source_bone.tail_local - source_bone.head_local)
                correction = helpers.basis(source_vector, sn) @ helpers.basis(tb.tail_local - tb.head_local, tn).transposed()
                result[name] = source_name, correction
        # Both bases face Blender -Y with grounded soles. Preserve authored
        # foot/toe WORLD rotation deltas, including heel lift in the first pose.
        result['foot.' + side] = source_side + 'Foot', identity
        for digit in range(1, 6):
            result[f'toe{digit}-1.{side}'] = source_side + 'ToeBase', identity
    return result


def apply_pose(target, rest, mapping, sample, root):
    deltas = {name: sample[name].to_3x3() @ matrix.to_3x3().inverted() for name, matrix in rest.items()}
    poses = {}
    for bone in target.pose.bones:
        bone.rotation_mode = 'QUATERNION'
        bind = bone.bone.matrix_local
        local_bind = bone.parent.bone.matrix_local.inverted() @ bind if bone.parent else bind
        inherited = poses[bone.parent.name] @ local_bind if bone.parent else bind.copy()
        if not bone.parent:
            inherited.translation = root
        if bone.name in mapping:
            source_name, correction = mapping[bone.name]
            desired = (deltas[source_name] @ correction @ bind.to_3x3()).to_4x4()
            desired.translation = inherited.translation
        else:
            desired = inherited
        poses[bone.name] = desired
        local = poses[bone.parent.name].inverted() @ desired if bone.parent else desired
        bone.matrix_basis = local_bind.inverted() @ local
    bpy.context.view_layer.update()


def ranges(values):
    return [max(v[i] for v in values) - min(v[i] for v in values) for i in range(3)]


def verify_overlap(target, mesh, tracks, seconds=1.2):
    """Measure the same local-transform smoothstep blend used by the viewer."""
    inverse_bind = {
        bone.name: (bone.parent.bone.matrix_local.inverted() @ bone.bone.matrix_local
                    if bone.parent else bone.bone.matrix_local).inverted()
        for bone in target.pose.bones
    }
    count = len(tracks['root']['rotation'])
    steps = min(round(seconds * FPS), count - 1)
    floors, feet = [], []
    for frame in range(steps + 1):
        first, second = count - 1 - steps + frame, frame
        fraction = frame / steps
        weight = fraction * fraction * (3 - 2 * fraction)
        for bone in target.pose.bones:
            channels = tracks[bone.name]
            position = Vector(channels['translation'][first]).lerp(Vector(channels['translation'][second]), weight)
            a, b = channels['rotation'][first], channels['rotation'][second]
            quaternion = Quaternion((a[3], *a[:3])).slerp(Quaternion((b[3], *b[:3])), weight)
            scale = Vector(channels['scale'][first]).lerp(Vector(channels['scale'][second]), weight)
            local = Matrix.LocRotScale(position, quaternion, scale)
            if not bone.parent:
                local = helpers.BLENDER_TO_GLTF.inverted() @ local
            bone.matrix_basis = inverse_bind[bone.name] @ local
        bpy.context.view_layer.update()
        evaluated = mesh.evaluated_get(bpy.context.evaluated_depsgraph_get())
        floors.append(min(vertex.co.z for vertex in evaluated.data.vertices))
        feet.append({side: target.pose.bones['foot.' + side].head.copy() for side in ['L', 'R']})
    return {
        'min_z': min(floors), 'max_min_z': max(floors),
        'foot_range': {side: ranges([frame[side] for frame in feet]) for side in ['L', 'R']},
        'max_foot_step_m': max((a[side] - b[side]).length for a, b in zip(feet, feet[1:]) for side in ['L', 'R']),
    }


def render_review(target, mesh, source_meshes, review_samples, directory):
    scene = bpy.context.scene
    scene.render.resolution_x, scene.render.resolution_y = 440, 660
    scene.render.resolution_percentage = 100
    scene.cycles.samples = 16
    scene.render.image_settings.file_format = 'PNG'
    for obj in source_meshes:
        obj.hide_render = True
    for shape in mesh.data.shape_keys.key_blocks[1:]:
        shape.value = float(shape.name == 'shape_f1_m4')
    for view, location in [('front', (0, -5, 1.0)), ('three-quarter', (2.6, -4.4, 1.04))]:
        scene.camera.location = location
        scene.camera.rotation_euler = (Vector((0, 0, .9)) - scene.camera.location).to_track_quat('-Z', 'Y').to_euler()
        for frame, pose in review_samples.items():
            for bone in target.pose.bones:
                bone.matrix_basis = Matrix(pose[bone.name])
            bpy.context.view_layer.update()
            scene.render.filepath = str(directory / f'{view}-{frame:04d}.png')
            bpy.ops.render.render(write_still=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--blend', type=Path, default=ROOT / 'runtime/inbody-character/review/character.blend')
    parser.add_argument('--base', type=Path, default=ROOT / 'runtime/inbody-character/assets/character.glb')
    parser.add_argument('--output', type=Path, default=ROOT / 'runtime/inbody-rokoko/male')
    parser.add_argument('--start-frame', type=int, default=150)
    parser.add_argument('--end-frame', type=int, default=960)
    parser.add_argument('--render', action='store_true')
    args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else [])
    assert helpers.sha(args.source) == SOURCE_SHA256, 'Source differs from reviewed original take'
    base_hash, blend_hash = helpers.sha(args.base), helpers.sha(args.blend)
    args.output.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.open_mainfile(filepath=str(args.blend.resolve()))
    target, mesh = bpy.data.objects['InBodySkeleton'], bpy.data.objects['InBodyCharacter']
    target.animation_data_clear()
    existing = set(bpy.data.objects)
    bpy.ops.import_scene.fbx(filepath=str(args.source.resolve()))
    source = next(obj for obj in set(bpy.data.objects) - existing if obj.type == 'ARMATURE')
    source_meshes = [obj for obj in set(bpy.data.objects) - existing if obj.type == 'MESH']
    assert len(source.data.bones) == 53
    assert bpy.context.scene.render.fps == FPS
    first, last = source.animation_data.action.frame_range
    assert round(first) == 1 and round(last) == 1014
    rest = rest_transforms(source)
    mapping = mapping_for(target, rest)
    start, end = 3, 1014
    assert start <= args.start_frame < args.end_frame <= end
    initial = capture(source, start)
    source_leg = sum((rest[b].translation - rest[a].translation).length for a, b in [('LeftUpLeg', 'LeftLeg'), ('LeftLeg', 'LeftFoot')])
    target_leg = sum((target.data.bones[b].head_local - target.data.bones[a].head_local).length for a, b in [('upperleg01.L', 'lowerleg01.L'), ('lowerleg01.L', 'foot.L')])
    scale = target_leg / source_leg
    root_rest = target.data.bones['root'].head_local.copy()
    apply_pose(target, rest, mapping, initial, root_rest)
    ground = -min(vertex.co.z for vertex in mesh.evaluated_get(bpy.context.evaluated_depsgraph_get()).data.vertices)
    root_rest.z += ground
    apply_pose(target, rest, mapping, initial, root_rest)
    anchors = {side: target.pose.bones['foot.' + side].head.copy() for side in ['L', 'R']}
    tracks = {bone.name: {'translation': [], 'rotation': [], 'scale': []} for bone in target.pose.bones}
    previous, reviews = {}, {}
    feet = {side: [] for side in ['L', 'R']}
    roots, source_roots, contacts, floor_heights = [], [], [], []
    quaternions = {name: [] for name in tracks}
    for frame in range(start, end + 1):
        sample = capture(source, frame)
        root = root_rest + (sample['Hips'].translation - initial['Hips'].translation) * scale
        apply_pose(target, rest, mapping, sample, root)
        for side, source_side in [('L', 'Left'), ('R', 'Right')]:
            goal = anchors[side] + (sample[source_side + 'Foot'].translation - initial[source_side + 'Foot'].translation) * scale
            contacts.append(helpers.ankle_contact(target, side, goal))
        if args.start_frame <= frame <= args.end_frame:
            evaluated_mesh = mesh.evaluated_get(bpy.context.evaluated_depsgraph_get())
            floor_heights.append(min(vertex.co.z for vertex in evaluated_mesh.data.vertices))
        poses = {bone.name: bone.matrix.copy() for bone in target.pose.bones}
        for bone in target.pose.bones:
            local = poses[bone.parent.name].inverted() @ poses[bone.name] if bone.parent else helpers.BLENDER_TO_GLTF @ poses[bone.name]
            position, quaternion, scaling = local.decompose()
            if bone.name in previous and quaternion.dot(previous[bone.name]) < 0:
                quaternion.negate()
            previous[bone.name] = quaternion.copy()
            quaternions[bone.name].append(quaternion.copy())
            tracks[bone.name]['translation'].append(list(position))
            tracks[bone.name]['rotation'].append([quaternion.x, quaternion.y, quaternion.z, quaternion.w])
            tracks[bone.name]['scale'].append(list(scaling))
        for side in feet:
            feet[side].append(list(poses['foot.' + side].translation))
        roots.append(list(root))
        source_roots.append(list(sample['Hips'].translation))
        if frame in [args.start_frame, 300, 600, 900, args.end_frame]:
            reviews[frame] = {bone.name: [list(row) for row in bone.matrix_basis] for bone in target.pose.bones}
    left, right = args.start_frame - start, args.end_frame - start + 1
    tracks = {name: {path: rows[left:right] for path, rows in paths.items()} for name, paths in tracks.items()}
    quaternions = {name: values[left:right] for name, values in quaternions.items()}
    feet = {side: values[left:right] for side, values in feet.items()}
    roots, source_roots, contacts = roots[left:right], source_roots[left:right], contacts[left * 2:right * 2]
    start, end = args.start_frame, args.end_frame
    assert min(floor_heights) >= -.005 and max(floor_heights) <= .015, 'Standing interval sole clearance outside reviewed tolerance'
    assert all(math.isfinite(value) for paths in tracks.values() for rows in paths.values() for row in rows for value in row)
    assert max(contacts) < .03, f'Unreachable leg contact: {max(contacts)}'
    base = helpers.read_glb(args.base)
    # Strip inherited source extras (but retain the exact target node hierarchy).
    base['asset']['generator'] = 'InBody Rokoko LookingAround retarget v1'
    destination = args.output / 'rokoko-lookaround.glb'
    write_clip(destination, base, tracks)
    metrics = {
        'finite': True, 'max_ankle_contact_error_meters': max(contacts),
        'minimum_mesh_z_meters': min(floor_heights),
        'maximum_minimum_mesh_z_meters': max(floor_heights),
        'mesh_floor_samples': len(floor_heights),
        'root_range_meters': ranges(roots), 'source_root_range_meters': ranges(source_roots),
        'foot_range_meters': {side: ranges(values) for side, values in feet.items()},
        'endpoint_foot_distance_meters': {side: (Vector(values[-1]) - Vector(values[0])).length for side, values in feet.items()},
        'endpoint_root_distance_meters': (Vector(roots[-1]) - Vector(roots[0])).length,
        'maximum_frame_rotation_degrees': max(math.degrees(a.rotation_difference(b).angle) for values in quaternions.values() for a, b in zip(values, values[1:])),
        'endpoint_rotation_degrees': {name: math.degrees(values[0].rotation_difference(values[-1]).angle) for name, values in quaternions.items() if name in ['root', 'head', 'wrist.L', 'wrist.R', 'foot.L', 'foot.R']},
    }
    metadata = {
        'version': 'inbody-rokoko-lookaround-v1', 'clip': CLIP,
        'fps': FPS, 'frames': end - start + 1, 'duration': (end - start) / FPS,
        'bytes': destination.stat().st_size, 'sha256': helpers.sha(destination),
        'source': {'author': 'Rokoko', 'file': args.source.name, 'sha256': SOURCE_SHA256,
                   'url': 'https://www.rokoko.com/resources/rokoko-mocap-10-free-everyday-idle-animations',
                   'license': 'Rokoko publisher permission; not CC0', 'frame_range': [first, last]},
        'target': {'template': target.get('body_template', 'male'), 'resource_id': target.get('body_resource', target.get('body_template', 'male')),
                   'base_sha256': base_hash, 'blend_sha256': blend_hash, 'bones': len(tracks), 'channels': len(tracks) * 3},
        'retarget': {'source_frame_range': [start, end], 'omitted_calibration_frames': [1, 2],
                     'omitted_entrance_frames': [3, start - 1], 'omitted_exit_frames': [end + 1, 1014],
                     'selection': 'Natural standing interval with matching foot placement; original head, shoulder and weight-shift motion retained',
                     'source_timing_preserved': True, 'loop': False, 'suggested_overlap_seconds': 1.2,
                     'translation_scale': scale, 'ground_offset_meters': ground,
                     'hips': 'Animated armature object evaluated in world coordinates',
                     'limbs': 'Rest directions with explicit T-pose elbow planes and rest palm planes',
                     'contacts': 'Two-segment target leg IK follows original source ankle trajectories',
                     'mesh_unchanged': True},
        'metrics': metrics,
    }
    metadata['metrics']['loop_overlap_verification'] = verify_overlap(target, mesh, tracks)
    (args.output / 'rokoko-lookaround.json').write_text(json.dumps(metadata, indent=2) + '\n')
    if args.render:
        render_review(target, mesh, source_meshes, reviews, args.output)
    assert helpers.sha(args.base) == base_hash and helpers.sha(args.blend) == blend_hash
    print('ROKOKO_EXPORT', json.dumps(metadata), flush=True)


if __name__ == '__main__':
    main()
