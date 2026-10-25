"""Original, authored bone animation for the isolated InBody character experiment.

No captured motion or third-party animation data. The scene routine uses a modest
thoracic turn with planted feet; it does not impersonate competition footwork.
Blender-only module; sampled by the reproducible character asset compiler.
"""
import math

import bpy
from mathutils import Euler, Matrix, Quaternion, Vector

FPS = 24
SCENARIOS = {
    'scenario_calm': {'duration': 10., 'exit_start': 8., 'settled_at': 9.5,
        'phases': [[0., 'settle'], [1.6, 'look_right'], [4.5, 'look_left'], [8., 'return']]},
    'scenario_athletic': {'duration': 14., 'exit_start': 12., 'settled_at': 13.5,
        'phases': [[0., 'prepare'], [2.6, 'double_biceps'], [6.4, 'release'], [8.2, 'lat_spread'], [12., 'return']]},
    'scenario_stage': {'duration': 18., 'exit_start': 16., 'settled_at': 17.5,
        'phases': [[0., 'prepare'], [2.8, 'double_biceps'], [6.4, 'release'], [8., 'chest_turn'], [13.2, 'present'], [16., 'return']]},
}


def capture(rig):
    return {bone.name: (bone.location.copy(), bone.rotation_quaternion.copy(), bone.scale.copy())
            for bone in rig.pose.bones}


def apply(rig, state):
    for name, (location, rotation, scale) in state.items():
        bone = rig.pose.bones[name]
        bone.location = location
        bone.rotation_quaternion = rotation
        bone.scale = scale


def rotation(rig, name, xyz, additive=False):
    bone = rig.pose.bones.get(name)
    if bone is None:
        return
    q = Euler(tuple(math.radians(v) for v in xyz), 'XYZ').to_quaternion()
    bone.rotation_quaternion = bone.rotation_quaternion @ q if additive else q


def fingers(rig, amount):
    for side, sign in [('L', 1), ('R', -1)]:
        for finger in range(2, 6):
            for joint, angle in enumerate([60., 75., 35.], 1):
                rotation(rig, f'finger{finger}-{joint}.{side}', (angle * amount, 0, 0))
        rotation(rig, f'finger1-1.{side}', (20 * amount, 0, -40 * sign * amount))
        rotation(rig, f'finger1-2.{side}', (25 * amount, 0, 0))
        rotation(rig, f'finger1-3.{side}', (25 * amount, 0, 0))


def aim_chain(rig, bone_name, tip_name, target):
    """Rotate one parent in armature space to aim its descendant without stretch."""
    bpy.context.view_layer.update()
    bone = rig.pose.bones[bone_name]
    pivot = bone.head.copy()
    vector = rig.pose.bones[tip_name].head - pivot
    adjustment = vector.rotation_difference(Vector(target) - pivot)
    bone.matrix = Matrix.Translation(pivot) @ adjustment.to_matrix().to_4x4() @ Matrix.Translation(-pivot) @ bone.matrix
    bone.rotation_quaternion.normalize()
    bone.scale = (1, 1, 1)
    bone.location = (0, 0, 0)
    bpy.context.view_layer.update()


def arm_target(rig, side, target, pole):
    """Analytic two-chain arm placement for authored lat/chest poses, no runtime IK."""
    upper = rig.pose.bones[f'upperarm01.{side}']
    lower = rig.pose.bones[f'lowerarm01.{side}']
    wrist = rig.pose.bones[f'wrist.{side}']
    bpy.context.view_layer.update()
    shoulder = upper.head.copy()
    length_upper = (lower.head - shoulder).length
    length_lower = (wrist.head - lower.head).length
    delta = Vector(target) - shoulder
    distance = min(delta.length, length_upper + length_lower - .001)
    direction = delta.normalized()
    along = (length_upper ** 2 - length_lower ** 2 + distance ** 2) / (2 * distance)
    height = math.sqrt(max(.000001, length_upper ** 2 - along ** 2))
    pole = Vector(pole) - shoulder
    normal = (pole - direction * pole.dot(direction)).normalized()
    elbow = shoulder + direction * along + normal * height
    aim_chain(rig, f'upperarm01.{side}', f'lowerarm01.{side}', elbow)
    aim_chain(rig, f'lowerarm01.{side}', f'wrist.{side}', target)


def author_poses(rig, held_pose):
    states = {}
    def contact(point):
        """Fit authored hand/pole landmarks to the female's own rest skeleton.

        Keep the accepted male curves unchanged. Female targets scale around
        its hip landmark in height, and use its own shoulder width. This avoids
        reusing male absolute hand heights/clearance on shorter limbs.
        """
        if rig.get('body_template') != 'female':
            return point
        root = rig.data.bones['root'].head_local
        neck = rig.data.bones['neck01'].head_local
        shoulder = rig.data.bones['upperarm01.L'].head_local
        width = abs(shoulder.x) / .18937185406684875
        torso = (neck.z - root.z) / (1.4824820756912231 - .9113608002662659)
        x, y, z = point
        return (x * width, root.y + (y - .0634409487247467) * torso,
                root.z + (z - .9113608002662659) * torso)
    def save(name):
        # Reset inherited stretch/translation and normalize generated quaternions.
        for bone in rig.pose.bones:
            bone.location = (0, 0, 0)
            bone.scale = (1, 1, 1)
            bone.rotation_quaternion.normalize()
        bpy.context.view_layer.update()
        states[name] = capture(rig)

    held_pose(rig, 'relaxed'); save('relaxed')
    # Calm is upper-body attention, with a restrained shift perceived through tilt.
    for label, yaw, roll in [('attention_right', -8., -1.0), ('attention_left', 7., .8)]:
        held_pose(rig, 'relaxed')
        rotation(rig, 'spine02', (.3, yaw * .10, roll * .40), True)
        rotation(rig, 'spine01', (-.4, yaw * .10, roll * .30), True)
        rotation(rig, 'neck02', (0, yaw * .35, -roll * .4), True)
        rotation(rig, 'head', (.3, yaw * .45, -roll * .2), True)
        rotation(rig, 'clavicle.L', (0, -.6, .8), True)
        rotation(rig, 'clavicle.R', (0, .6, -.8), True)
        save(label)
    held_pose(rig, 'relaxed')
    fingers(rig, .4)
    for side, sign in [('L', 1), ('R', -1)]:
        rotation(rig, f'clavicle.{side}', (0, 0, 2 * sign), True)
        rotation(rig, f'upperarm01.{side}', (0, -10 * sign, -13 * sign))
        rotation(rig, f'lowerarm01.{side}', (-5, -30 * sign, 0))
        rotation(rig, f'wrist.{side}', (-5, 6 * sign, 0))
    rotation(rig, 'head', (2, 0, 0), True)
    save('prepare')

    # The proven held flex remains the anatomical envelope. The lead arm arrives
    # slightly earlier, followed by independent forearm/hand setting, then release.
    held_pose(rig, 'flex')
    for side, sign in [('L', 1), ('R', -1)]:
        rotation(rig, f'upperarm01.{side}', (0, -45 * sign, 12 * sign))
    save('flex')
    rotation(rig, 'upperarm01.R', (0, 5, -7), True)
    rotation(rig, 'lowerarm01.R', (-10, 0, 0), True)
    rotation(rig, 'head', (0, -3, 0), True)
    save('flex_lead')
    apply(rig, states['flex'])
    for side, sign in [('L', 1), ('R', -1)]:
        rotation(rig, f'wrist.{side}', (-6, 5 * sign, -3 * sign))
        rotation(rig, f'clavicle.{side}', (0, -1 * sign, 1 * sign), True)
        rotation(rig, f'lowerarm01.{side}', (-2, 2 * sign, 0), True)
    rotation(rig, 'spine01', (-.8, 0, 0), True)
    rotation(rig, 'head', (-.8, 0, 0), True)
    save('flex_peak')
    held_pose(rig, 'relaxed'); fingers(rig, .65)
    for side, sign in [('L', 1), ('R', -1)]:
        rotation(rig, f'upperarm01.{side}', (0, -22 * sign, -8 * sign))
        rotation(rig, f'lowerarm01.{side}', (20, -15 * sign, 0))
        rotation(rig, f'wrist.{side}', (-3, 0, 0))
    save('lower')

    held_pose(rig, 'relaxed'); fingers(rig, .75)
    for side, sign in [('L', 1), ('R', -1)]:
        rotation(rig, f'clavicle.{side}', (0, -4 * sign, 4 * sign), True)
        rotation(rig, f'upperarm02.{side}', (0, 0, 0))
        rotation(rig, f'lowerarm01.{side}', (0, 0, 0))
        rotation(rig, f'lowerarm02.{side}', (0, 0, 0))
        arm_target(rig, side, contact((.29 * sign, -.20, 1.04)), contact((.48 * sign, -.015, 1.18)))
        rotation(rig, f'wrist.{side}', (-8, 0, -8 * sign))
    rotation(rig, 'spine01', (-1.5, 0, 0), True)
    save('lat_spread')
    rotation(rig, 'clavicle.L', (0, -1, 1), True)
    rotation(rig, 'clavicle.R', (0, 1, -1), True)
    rotation(rig, 'head', (-.6, 1, 0), True)
    save('lat_peak')

    # 28 degrees distributed across the thoracic chain. Root, pelvis and every
    # leg/foot bone remain exactly at relaxed; no planted-foot rotation or slide.
    held_pose(rig, 'relaxed'); fingers(rig, .35)
    for name, angle in [('spine03', 6.), ('spine02', 10.), ('spine01', 12.)]:
        rotation(rig, name, (0, angle, 0), True)
    rotation(rig, 'neck02', (0, -6, 0), True)
    rotation(rig, 'head', (0, -8, 0), True)
    save('chest_turn')
    for side, sign in [('L', 1), ('R', -1)]:
        rotation(rig, f'upperarm02.{side}', (0, 0, 0))
        rotation(rig, f'lowerarm01.{side}', (0, 0, 0))
        rotation(rig, f'lowerarm02.{side}', (0, 0, 0))
        # A separated clasp gesture in front of the abdomen, with clearance even
        # on the large-fat morph. Hands approach, never attach or pass through.
        arm_target(rig, side, contact((.14 if side == 'L' else -.04, -.34 if side == 'L' else -.37, 1.13 if side == 'L' else 1.09)),
                   contact((.43 if side == 'L' else -.26, .015 if side == 'L' else -.20, 1.16)))
        rotation(rig, f'wrist.{side}', (-4, 4 * sign, -3 * sign))
    fingers(rig, .7); save('side_chest')
    rotation(rig, 'spine01', (-1.0, 0, 0), True)
    rotation(rig, 'head', (-.5, 2, 0), True)
    rotation(rig, 'wrist.L', (1.5, -2, 0), True)
    rotation(rig, 'wrist.R', (0, 2, 0), True)
    save('side_chest_peak')
    apply(rig, states['relaxed'])
    return states


KEYS = {
    'scenario_calm': [(0., 'relaxed'), (1.6, 'relaxed'), (3.1, 'attention_right'),
        (4.1, 'attention_right'), (5.0, 'relaxed'), (6.5, 'attention_left'),
        (7.7, 'attention_left'), (8., 'attention_left'), (9.5, 'relaxed'), (10., 'relaxed')],
    'scenario_athletic': [(0., 'relaxed'), (1.1, 'prepare'), (2.6, 'flex_lead'),
        (3.5, 'flex'), (4.6, 'flex_peak'), (5.5, 'flex'), (6.4, 'flex_peak'),
        (7.6, 'lower'), (8.8, 'lat_spread'), (10., 'lat_peak'), (11.1, 'lat_spread'),
        (12., 'lat_peak'), (13.5, 'relaxed'), (14., 'relaxed')],
    'scenario_stage': [(0., 'relaxed'), (1.2, 'prepare'), (2.8, 'flex_lead'),
        (3.6, 'flex'), (4.8, 'flex_peak'), (5.5, 'flex'), (6.4, 'flex_peak'),
        (7.7, 'lower'), (8.7, 'chest_turn'), (10.0, 'side_chest'),
        (11.3, 'side_chest_peak'), (12.1, 'side_chest'), (13.2, 'side_chest_peak'),
        (14.7, 'side_chest'), (16., 'chest_turn'), (17.5, 'relaxed'), (18., 'relaxed')],
}


def sample(rig, name, time, states):
    keys = KEYS[name]
    time = max(0., min(keys[-1][0], time))
    a, b = keys[0], keys[1]
    for a, b in zip(keys, keys[1:]):
        if time <= b[0]:
            break
    t = (time - a[0]) / (b[0] - a[0])
    # Smooth quintic timing: starts/ends with zero angular velocity/acceleration.
    t = t * t * t * (t * (t * 6 - 15) + 10)
    for bone in rig.pose.bones:
        la, qa, sa = states[a[1]][bone.name]
        lb, qb, sb = states[b[1]][bone.name]
        bone.location = la.lerp(lb, t)
        bone.rotation_quaternion = qa.slerp(qb, t)
        bone.scale = sa.lerp(sb, t)
    # Continuous secondary motion between authored beats. Fade out before the
    # exact relaxed tail; feet and pelvis are deliberately excluded.
    end = SCENARIOS[name]['settled_at']
    envelope = min(1., time / 1.0, max(0., end - time) / 1.0)
    envelope = envelope * envelope * (3 - 2 * envelope)
    breath = math.sin(time * 2 * math.pi / 4.2) * envelope
    rotation(rig, 'spine01', (.27 * breath, 0, 0), True)
    rotation(rig, 'neck01', (-.12 * breath, 0, 0), True)
    for side, sign in [('L', 1), ('R', -1)]:
        rotation(rig, f'clavicle.{side}', (0, 0, .20 * sign * breath), True)
    bpy.context.view_layer.update()


def build_scenarios(rig, held_pose):
    states = author_poses(rig, held_pose)
    scene = bpy.context.scene
    scene.render.fps = FPS
    ground = {name: rig.pose.bones[name].matrix.copy() for name in
              ['root', 'pelvis.L', 'pelvis.R', 'foot.L', 'foot.R']}
    max_ground_drift = 0.
    for name, metadata in SCENARIOS.items():
        action = bpy.data.actions.new(name)
        rig.animation_data.action = action
        for frame in range(round(metadata['duration'] * FPS) + 1):
            sample(rig, name, frame / FPS, states)
            for bone_name, matrix in ground.items():
                current = rig.pose.bones[bone_name].matrix
                max_ground_drift = max(max_ground_drift, max(abs(current[i][j] - matrix[i][j]) for i in range(4) for j in range(4)))
            for bone in rig.pose.bones:
                bone.keyframe_insert(data_path='location', frame=frame, group=bone.name)
                bone.keyframe_insert(data_path='rotation_quaternion', frame=frame, group=bone.name)
                bone.keyframe_insert(data_path='scale', frame=frame, group=bone.name)
        rig.animation_data.action = None
        track = rig.animation_data.nla_tracks.new(); track.name = name
        strip = track.strips.new(name, 0, action); strip.name = name
        track.mute = True
    apply(rig, states['relaxed']); bpy.context.view_layer.update()
    if max_ground_drift > .000001:
        raise RuntimeError('Authored scenario changes planted foot/root transform')
    return {'provenance': 'Original keyframed choreography authored for this prototype; not motion capture',
            'license': 'Project-owned original animation curves; underlying mesh retains CC0 provenance',
            'version': 'authored-scenarios-v1', 'sample_fps': FPS, 'clips': SCENARIOS,
            'ground_transform_max_drift': max_ground_drift,
            'stage_turn': '28 degrees across thoracic bones; feet and pelvis remain planted',
            'limitations': 'Stylized hand placement without contact constraints; no competition footwork or biomechanical simulation'}
