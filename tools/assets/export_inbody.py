"""Compile curated CC0 MPFB body meshes with Blender; never runs in the application.

Example (MPFB checkout is an external build dependency, never vendored):
  BLENDER_USER_RESOURCES="$PWD/runtime/inbody-model/blender" blender -b --factory-startup \
    --python tools/assets/export_inbody.py -- --source runtime/inbody-model/mpfb2

See web/src/assets/body/README.md for provenance and calibration.
"""
import argparse
import hashlib
import gzip
import json
import math
from pathlib import Path
import subprocess
import sys

import bpy
import bmesh
from mathutils import Matrix, Quaternion, Vector
from collections import Counter, defaultdict

REVISION = 'afb9f530a7c2741dedb8df0ebae2e0b183caec21'
ROOT = Path(__file__).resolve().parents[2]
args = argparse.ArgumentParser()
args.add_argument('--source', type=Path, required=True)
args.add_argument('--output', type=Path, default=ROOT / 'web/src/assets/body')
args.add_argument('--review', type=Path, default=ROOT / 'runtime/inbody-model/review')
args.add_argument('--quick', action='store_true', help='Render only neutral posters for art iteration')
args = args.parse_args(sys.argv[sys.argv.index('--') + 1:])
source = args.source.resolve()
if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != REVISION:
    raise RuntimeError('MPFB checkout must match the recorded asset revision')
args.output.mkdir(parents=True, exist_ok=True)
args.review.mkdir(parents=True, exist_ok=True)
bpy.context.preferences.extensions.repos.new(name='InBody asset compiler', module='inbody', custom_directory=str(source / 'src'))
import addon_utils
addon_utils.enable('bl_ext.inbody.mpfb', default_set=True)
from bl_ext.inbody.mpfb.services.humanservice import HumanService
from bl_ext.inbody.mpfb.services.targetservice import TargetService


def clear():
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete(use_global=False)


def surface(gender, fat, muscle):
    settings = TargetService.get_default_macro_info_dict()
    # These artist-authored phenotype controls are illustrative, not clinical conversions.
    settings.update(gender=gender, age=.5, weight=(.15, .5, .85)[fat],
                    muscle=(.15, .5, .85)[muscle], proportions=.5, height=.5,
                    cupsize=.35, firmness=.5)
    original = HumanService.create_human(macro_detail_dict=settings)
    TargetService.load_target(original, str(source / 'src/mpfb/data/targets/breast/nipple-point-decr.target.gz'), weight=1)
    # Bake a relaxed A-pose with upstream skin weights; the runtime has no rig.
    rig = HumanService.add_builtin_rig(original, 'default')
    for side, direction in [('L', 1), ('R', -1)]:
        bone = rig.pose.bones[f'upperarm01.{side}']
        axis = bone.bone.matrix_local.to_3x3().inverted() @ Vector((0, 1, 0))
        bone.rotation_mode = 'QUATERNION'
        bone.rotation_quaternion = Quaternion(axis, math.radians(20) * direction)
        forearm = rig.pose.bones[f'lowerarm01.{side}']
        forearm.rotation_mode = 'XYZ'
        # Upstream default_fk/t-pose uses -37 degrees: retain a slight elbow bend.
        forearm.rotation_euler.x = math.radians(-31)
        forearm.rotation_euler.y = math.radians(-52) * direction
    bpy.context.view_layer.update()
    for side in ['L', 'R']:
        wrist = rig.pose.bones[f'wrist.{side}']
        matrix = wrist.matrix.copy()
        along_hand = matrix.to_3x3() @ Vector((0, 1, 0))
        down = along_hand.rotation_difference(Vector((0, 0, -1)))
        wrist.matrix = Matrix.LocRotScale(matrix.translation, down @ matrix.to_quaternion(), (1, 1, 1))
    bpy.context.view_layer.update()
    evaluated = original.evaluated_get(bpy.context.evaluated_depsgraph_get())
    mesh = bpy.data.meshes.new_from_object(evaluated)
    bm = bmesh.new()
    bm.from_mesh(mesh)
    # Close eye/mouth openings. A smooth, opaque mannequin needs no separate eyes,
    # teeth, hair, helper meshes or skin textures.
    boundaries = [edge for edge in bm.edges if edge.is_boundary]
    if boundaries:
        bmesh.ops.holes_fill(bm, edges=boundaries, sides=0)
    bm.verts.ensure_lookup_table()
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    bm.to_mesh(mesh)
    bm.free()
    vertices = [v.co.copy() for v in mesh.vertices]
    floor = min(v.z for v in vertices)
    vertices = [(v.x, v.y, v.z - floor) for v in vertices]
    faces = [tuple(p.vertices) for p in mesh.polygons]
    bpy.data.objects.remove(original, do_unlink=True)
    bpy.data.objects.remove(rig, do_unlink=True)
    bpy.data.meshes.remove(mesh)
    return vertices, faces


def sculpt(grid, faces):
    """Replace the dense face by a connected abstract head on one fixed neck loop.

    All index masks/topology are selected once on the neutral mesh, then reused
    for every morph. No detached primitives or anatomical eye/mouth cavities.
    """
    neutral = grid[(1, 1)]
    height = max(v[2] for v in neutral)
    cut = height * .855
    removed = {i for i, v in enumerate(neutral) if v[2] > cut}
    body_faces = [face for face in faces if not any(i in removed for i in face)]
    edges = Counter(tuple(sorted((a, b))) for face in body_faces
                    for a, b in zip(face, face[1:] + face[:1]))
    adjacency = defaultdict(list)
    for (a, b), count in edges.items():
        if count == 1 and min(neutral[a][2], neutral[b][2]) > cut - height * .035:
            adjacency[a].append(b)
            adjacency[b].append(a)
    if not adjacency or any(len(neighbors) != 2 for neighbors in adjacency.values()):
        raise RuntimeError('Head replacement needs one closed neck loop')
    ring = [min(adjacency)]
    while True:
        neighbors = adjacency[ring[-1]]
        following = neighbors[0] if len(ring) == 1 or neighbors[0] != ring[-2] else neighbors[1]
        if following == ring[0]:
            break
        if following in ring:
            raise RuntimeError('Neck boundary is not a simple cycle')
        ring.append(following)
    if len(ring) != len(adjacency):
        raise RuntimeError('Unexpected additional neck boundary')
    kept = sorted({i for face in body_faces for i in face})
    indices = {old: new for new, old in enumerate(kept)}
    topology = [tuple(indices[i] for i in face) for face in body_faces]
    count = len(ring)
    center_x = sum(neutral[i][0] for i in ring) / count
    center_y = sum(neutral[i][1] for i in ring) / count
    angles = [math.atan2(neutral[i][1] - center_y, neutral[i][0] - center_x) for i in ring]
    # Fraction of remaining height, transverse radius/H, depth radius/H, forward/H.
    profiles = [(0.10, .028, .030, -.001), (0.22, .026, .032, -.007),
                (0.34, .034, .039, -.003), (0.49, .039, .045, 0),
                (0.64, .041, .048, .002), (0.77, .039, .046, .002),
                (0.88, .032, .038, .001), (0.96, .020, .024, 0),
                (0.99, .010, .012, 0)]
    previous = [indices[i] for i in ring]
    for row, _ in enumerate(profiles):
        current = [len(kept) + row * count + i for i in range(count)]
        for i in range(count):
            j = (i + 1) % count
            topology.append((previous[i], previous[j], current[j], current[i]))
        previous = current
    top = len(kept) + len(profiles) * count
    for i in range(count):
        topology.append((previous[i], previous[(i + 1) % count], top))
    edge_counts = Counter(tuple(sorted((a, b))) for face in topology
                          for a, b in zip(face, face[1:] + face[:1]))
    if any(count != 2 for count in edge_counts.values()):
        raise RuntimeError('Sculpture must be one closed surface without open seams')
    # Fixed local masks reduce anatomical surface detail without flattening form.
    with gzip.open(source / 'src/mpfb/data/targets/breast/nipple-size-decr.target.gz', 'rt') as file:
        nipple_indices = {int(line.split()[0]) for line in file if line.strip()}
    chest = [indices[i] for i in nipple_indices if i in indices]
    suit = [indices[i] for i in kept if .43 * height < neutral[i][2] < .57 * height
            and abs(neutral[i][0]) < .105 and neutral[i][1] < -.025]
    result = {}
    for key, vertices in grid.items():
        h = max(v[2] for v in vertices)
        neck_z = sum(vertices[i][2] for i in ring) / count
        cy = sum(vertices[i][1] for i in ring) / count
        coords = [vertices[i] for i in kept]
        for fraction, rx, ry, offset in profiles:
            coords.extend((h * rx * math.cos(angle), cy + h * (offset + ry * math.sin(angle)),
                           neck_z + (h - neck_z) * fraction) for angle in angles)
        coords.append((0, cy, h))
        mesh = bpy.data.meshes.new('sculpt-pass')
        mesh.from_pydata(coords, [], topology)
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bm.verts.ensure_lookup_table()
        for mask, iterations, preserve_outline in [(chest, 60, True), (suit, 12, False)]:
            for _ in range(iterations):
                bmesh.ops.smooth_vert(bm, verts=[bm.verts[i] for i in mask], factor=.55,
                                     use_axis_x=not preserve_outline, use_axis_y=True, use_axis_z=not preserve_outline)
        bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
        bm.to_mesh(mesh)
        bm.free()
        result[key] = [tuple(v.co) for v in mesh.vertices]
        topology = [tuple(face.vertices) for face in mesh.polygons]
        bpy.data.meshes.remove(mesh)
    print('SCULPT', len(kept), count, len(topology))
    return result, topology


def pearl():
    material = bpy.data.materials.new('Pearl ceramic')
    material.use_nodes = True
    shader = material.node_tree.nodes.get('Principled BSDF')
    shader.inputs['Base Color'].default_value = (.63, .58, .50, 1)
    shader.inputs['Metallic'].default_value = 0
    shader.inputs['Roughness'].default_value = .7
    return material


def aim(obj, location):
    obj.rotation_euler = (Vector(location) - obj.location).to_track_quat('-Z', 'Y').to_euler()


def studio(height):
    scene = bpy.context.scene
    scene.render.engine = 'CYCLES'
    scene.cycles.samples = 128
    scene.cycles.use_denoising = True
    scene.render.resolution_x = 600
    scene.render.resolution_y = 840
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = True
    scene.view_settings.view_transform = 'AgX'
    scene.world.color = (.12, .12, .12)
    bpy.ops.object.camera_add(location=(.18, -5, height / 2))
    camera = bpy.context.object
    aim(camera, (0, 0, height / 2))
    camera.data.type = 'ORTHO'
    camera.data.ortho_scale = height * 1.12
    scene.camera = camera
    for name, location, power, color, size in (
        ('Key', (-2, -3, 3.6), 240, (1,.97,.93), 3),
        ('Fill', (2, -1, 2), 80, (.85,.89,1), 3),
        ('Rim', (1, 1.5, 2.6), 280, (1,.95,.86), 2),
    ):
        bpy.ops.object.light_add(type='AREA', location=location)
        lamp = bpy.context.object
        lamp.name = name
        lamp.data.energy = power
        lamp.data.color = color
        lamp.data.shape = 'DISK'
        lamp.data.size = size
        aim(lamp, (0,0,height/2))
    return camera


def render(path):
    scene = bpy.context.scene
    scene.render.image_settings.file_format = 'PNG'
    scene.render.image_settings.color_mode = 'RGBA'
    scene.render.image_settings.compression = 60
    scene.render.filepath = str(path.resolve())
    bpy.ops.render.render(write_still=True)


manifest = {'upstream': 'https://github.com/makehumancommunity/mpfb2', 'revision': REVISION,
            'license': 'CC0-1.0', 'blender': bpy.app.version_string, 'calibration': 'body-visual-v1',
            'fat_percent_anchors': [10,25,40], 'muscle_index_anchors': [6,9,12],
            'style': 'faceless-sculpture-v2',
            'pose': 'MPFB default rig; upperarm20, elbow-31, forearmtwist52 degrees, hands aligned downward; baked',
            'phenotype_weight': [.15,.5,.85], 'phenotype_muscle': [.15,.5,.85], 'assets': {}}
for template, gender in [('male',1.), ('female',0.)]:
    clear()
    grid = {}
    topology = None
    for fat in range(3):
        for muscle in range(3):
            coords, faces = surface(gender,fat,muscle)
            if topology is not None and topology != faces:
                raise RuntimeError('Morph topology differs')
            topology = faces
            grid[(fat,muscle)] = coords
    grid, topology = sculpt(grid, topology)
    mesh = bpy.data.meshes.new(template)
    mesh.from_pydata(grid[(1,1)], [], topology)
    mesh.update()
    for polygon in mesh.polygons:
        polygon.use_smooth = True
    obj = bpy.data.objects.new(template,mesh)
    bpy.context.collection.objects.link(obj)
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    obj.data.materials.append(pearl())
    obj.shape_key_add(name='Basis')
    for (fat,muscle), vertices in grid.items():
        key = obj.shape_key_add(name=f'body_f{fat}_m{muscle}')
        key.value = 0
        for vertex, coordinate in zip(key.data,vertices):
            vertex.co = coordinate
    obj.data.shape_keys.key_blocks['body_f1_m1'].value = 1
    mesh.calc_loop_triangles()
    triangles = len(mesh.loop_triangles)
    if triangles > 30000:
        raise RuntimeError(f'Triangle budget exceeded: {triangles}')
    path = args.output / f'{template}.glb'
    bpy.ops.export_scene.gltf(filepath=str(path.resolve()), export_format='GLB',
        use_selection=True, export_texcoords=False, export_normals=True,
        export_materials='EXPORT', export_morph=True, export_morph_normal=True,
        export_animations=False, export_skins=False, export_yup=True,
        export_cameras=False, export_lights=False)
    height = max(v.co.z for v in mesh.vertices)
    camera = studio(height)
    render(args.output / f'{template}.png')
    for name, angle in ([] if args.quick else [('front',0),('three-quarter',math.pi/4),('side',math.pi/2)]):
        camera.location = (5*math.sin(angle), -5*math.cos(angle),height/2)
        aim(camera,(0,0,height/2))
        render(args.review / f'{template}-{name}.png')
    for fat,muscle in ([] if args.quick else [(0,0),(0,2),(2,0),(2,2)]):
        for key in obj.data.shape_keys.key_blocks[1:]:
            key.value = 0
        obj.data.shape_keys.key_blocks[f'body_f{fat}_m{muscle}'].value=1
        camera.location=(.18,-5,height/2)
        aim(camera,(0,0,height/2))
        render(args.review / f'{template}-f{fat}-m{muscle}.png')
    manifest['assets'][template]={'vertices': len(mesh.vertices),'triangles':triangles,
        'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
        'poster_sha256':hashlib.sha256((args.output/f'{template}.png').read_bytes()).hexdigest()}
(args.output / 'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('BODY_ASSETS_COMPLETE',json.dumps(manifest))
