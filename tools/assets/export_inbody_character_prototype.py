"""Build the isolated rigged InBody art prototype with pinned CC0 MPFB assets.

Run with Blender, never in the application:
  BLENDER_USER_RESOURCES="$PWD/runtime/inbody-model/blender" blender -b --factory-startup --python-exit-code 1 \
    --python tools/assets/export_inbody_character_prototype.py -- \
    --source runtime/inbody-model/mpfb2 --quick

Generated models, textures, Blender project and review images stay under runtime/.
This is an artistic character experiment, not an anatomical reconstruction of a user.
"""
from collections import Counter, defaultdict
import argparse
import hashlib
import gzip
import json
import math
import platform
import resource
import time
from pathlib import Path
import shutil
import subprocess
import sys

import bpy
import bmesh
import numpy as np
from mathutils import Matrix, Quaternion, Vector

ROOT = Path(__file__).resolve().parents[2]
REVISION = 'afb9f530a7c2741dedb8df0ebae2e0b183caec21'
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True)
parser.add_argument('--template', choices=('male', 'female'), default='male')
parser.add_argument('--character', choices=('sculpture', 'spartan'), default='sculpture')
parser.add_argument('--head-baseline', type=Path, help='Pinned deployed mannequin GLB for exact CI head retention')
parser.add_argument('--output', type=Path, default=ROOT / 'runtime/inbody-character/assets')
parser.add_argument('--review', type=Path, default=ROOT / 'runtime/inbody-character/review')
parser.add_argument('--quick', action='store_true', help='Skip normal bake; render first three art checks')
parser.add_argument('--animations-only', action='store_true', help='Reopen the accepted review/character.blend and rebuild only original bone animation')
parser.add_argument('--motion-preview-only', action='store_true', help='Render authored key poses without exporting or changing accepted assets')
parser.add_argument('--skip-review', action='store_true', help='Only render the neutral production poster; omit the larger review matrix')
parser.add_argument('--review-samples', type=int, default=16)
parser.add_argument('--review-size', type=int, default=600)
args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:])
if args.character == 'spartan' and args.template != 'male':
    parser.error('The Spartan is authored for the male template')
build_started=time.monotonic()
source = args.source.resolve()
if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != REVISION:
    raise RuntimeError('Unexpected MPFB asset revision')
args.output.mkdir(parents=True, exist_ok=True)
args.review.mkdir(parents=True, exist_ok=True)
bpy.context.preferences.extensions.repos.new(name='InBody prototype compiler', module='inbody', custom_directory=str(source / 'src'))
import addon_utils
addon_utils.enable('bl_ext.inbody.mpfb', default_set=True)
from bl_ext.inbody.mpfb.services.humanservice import HumanService
from bl_ext.inbody.mpfb.services.targetservice import TargetService
sys.path.insert(0, str(Path(__file__).resolve().parent))
from inbody_character_motion import SCENARIOS, author_poses, build_scenarios, sample as sample_motion
from inbody_asset_contract import BODY_MORPHS, POSE_CORRECTIVES, embed_contract, relief_weights
from inbody_head_validate import retain_production_head, retain_production_rest, validate_head
from inbody_fat_volume import VERSION as FAT_VOLUME_VERSION, RECIPES as FAT_VOLUME_RECIPES, REVIEW_SAMPLES as FAT_VOLUME_SAMPLES, apply_fat_volume, body_sections, fat_volume_field, sample_body_grid

FAT_LEVELS = [0., .2, .4, .6, .8, 1.]
MUSCLE_LEVELS = [0., .25, .5, .75, 1.]
NEUTRAL = 'f2_m2'
PRESETS = {'neutral': (.4, .5), 'athletic': (.2, 1.), 'soft': (.85, .2), 'mixed': (.8, .9)}
BODY_NORMAL_STRENGTH = .6


def interpolate(value, anchors):
    value=max(anchors[0][0], min(anchors[-1][0], value))
    for (x0,y0),(x1,y1) in zip(anchors,anchors[1:]):
        if value<=x1:
            return y0+(y1-y0)*(value-x0)/(x1-x0)
    return anchors[-1][1]


def controls(fat,muscle):
    # Artistic shape controls only; neither axis represents a medical range.
    return {
        'fat':fat,'muscle_axis':muscle,
        'weight':interpolate(fat,[(0.,.10),(.2,.30),(.4,.40),(.85,.90),(1.,1.)]),
        'muscle':interpolate(muscle,[(0.,.15),(.2,.35),(.5,.65),(1.,.98)]),
        'anatomy':muscle**.9*interpolate(fat,[(0.,1.),(.2,1.),(.4,.35/(.5**.9)),(.85,.05/(.2**.9)),(1.,.08)]),
    }


CONTROL_POINTS = {f'f{f}_m{m}':controls(fat,muscle)
                  for f,fat in enumerate(FAT_LEVELS) for m,muscle in enumerate(MUSCLE_LEVELS)}
PREVIEW_RELIEF = None
SCULPTURE_HEAD_LANDMARKS = None
FAT_VOLUME_SOURCE_RECEIPTS = {}
FAT_VOLUME_STAGE_RECEIPTS = {}
FAT_VOLUME_RECEIPT_SHAPES = {f'f{f}_m{m}' for f in (0, 2, 5) for m in (0, 2, 4)}
ANATOMY = {
    'torso/torso-muscle-pectoral-incr': .65,
    'torso/torso-muscle-dorsi-incr': .60,
    'torso/torso-vshape-incr': .35,
    'stomach/stomach-tone-incr': 1.,
    **{f'arms/{side}-{part}-muscle-incr': amount for side in ('l', 'r')
       for part, amount in [('upperarm-shoulder', .60), ('upperarm', .8), ('lowerarm', .35)]},
    **{f'legs/{side}-{part}-muscle-incr': amount for side in ('l', 'r')
       for part, amount in [('upperleg', .7), ('lowerleg', .65)]},
}
if args.template == 'female':
    # Female source proportions are authored independently. Breast volume stays
    # in the phenotype, rather than gaining male pectoral/serratus geometry.
    ANATOMY['torso/torso-muscle-pectoral-incr'] = .28
    ANATOMY['torso/torso-vshape-incr'] = .22


def evaluated_section_report(original):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    mesh = bpy.data.meshes.new_from_object(original.evaluated_get(depsgraph), depsgraph=depsgraph)
    mesh.calc_loop_triangles()
    coords = [tuple(vertex.co) for vertex in mesh.vertices]
    triangles = [tuple(face.vertices) for face in mesh.loop_triangles]
    height = max(point[2] for point in coords)
    result = body_sections(coords, coords, triangles, height)
    bpy.data.meshes.remove(mesh)
    return {'height_m': height, 'sections': result, 'reference': 'this stage evaluated rest surface'}


def surface(name):
    settings = TargetService.get_default_macro_info_dict()
    settings_for_shape = CONTROL_POINTS[name]
    settings.update(gender=1. if args.template == 'male' else 0., age=.5, weight=settings_for_shape['weight'], muscle=settings_for_shape['muscle'], proportions=.5, height=.5)
    original = HumanService.create_human(macro_detail_dict=settings)
    if name in FAT_VOLUME_RECEIPT_SHAPES:
        readback = TargetService.get_macro_info_dict_from_basemesh(original)
        requested = {key: settings[key] for key in ('gender', 'age', 'weight', 'muscle', 'proportions', 'height')}
        actual = {key: readback[key] for key in requested}
        if any(abs(requested[key] - actual[key]) > 1.e-6 for key in requested):
            raise RuntimeError('MPFB macro read-back differs from the requested phenotype')
        keys = original.data.shape_keys.key_blocks
        expected = TargetService.calculate_target_stack_from_macro_info_dict(readback)
        for target, value in expected:
            encoded = TargetService.macrodetail_filename_to_shapekey_name(target, encode_name=True)
            if encoded not in keys or abs(keys[encoded].value - value) > 1.e-6:
                raise RuntimeError('MPFB macro target stack differs from the read-back phenotype')
        active = {key.name: float(key.value) for key in keys if key.name.startswith('$md') and key.value > 0.}
        if not active or len(active) > 256:
            raise RuntimeError('Unexpected MPFB macro receipt size')
        FAT_VOLUME_SOURCE_RECEIPTS[name] = {'requested': requested, 'readback': actual,
                                           'active_macro_keys': active,
                                           'raw_mpfb': evaluated_section_report(original)}
    for target, strength in ANATOMY.items():
        TargetService.load_target(original, str(source / f'src/mpfb/data/targets/{target}.target.gz'), weight=strength * settings_for_shape['anatomy'])
    TargetService.load_target(original, str(source / 'src/mpfb/data/targets/breast/nipple-point-decr.target.gz'), weight=1.)
    if name in FAT_VOLUME_RECEIPT_SHAPES:
        FAT_VOLUME_SOURCE_RECEIPTS[name]['after_details'] = evaluated_section_report(original)
    rig = HumanService.add_builtin_rig(original, 'default')
    depsgraph = bpy.context.evaluated_depsgraph_get()
    mesh = bpy.data.meshes.new_from_object(original.evaluated_get(depsgraph), preserve_all_data_layers=True, depsgraph=depsgraph)
    coords = [tuple(v.co) for v in mesh.vertices]
    if name in FAT_VOLUME_RECEIPT_SHAPES:
        mesh.calc_loop_triangles()
        height = max(point[2] for point in coords)
        FAT_VOLUME_SOURCE_RECEIPTS[name]['after_rig'] = {'height_m': height,
            'sections': body_sections(coords, coords, [tuple(face.vertices) for face in mesh.loop_triangles], height),
            'reference': 'this stage evaluated rest surface'}
    faces = [tuple(p.vertices) for p in mesh.polygons]
    uv = [[tuple(mesh.uv_layers.active.data[i].uv) for i in p.loop_indices] for p in mesh.polygons]
    weights = [{original.vertex_groups[g.group].name: g.weight for g in v.groups
                if original.vertex_groups[g.group].name in rig.data.bones and g.weight > .00001}
               for v in mesh.vertices]
    original_mesh=original.data
    bpy.data.objects.remove(original, do_unlink=True)
    bpy.data.meshes.remove(original_mesh)
    bpy.data.meshes.remove(mesh)
    if name != NEUTRAL:
        rig_data=rig.data
        bpy.data.objects.remove(rig, do_unlink=True)
        bpy.data.armatures.remove(rig_data)
        rig = None
    return coords, faces, uv, weights, rig


def refine_fat_volume(grid, mesh, skin_rows):
    """Add one fixed fat field after existing sculpting, keeping muscle deltas."""
    reference = grid[NEUTRAL]
    height = max(point[2] for point in reference)
    field = fat_volume_field(reference, skin_rows, height, args.template)
    mesh.calc_loop_triangles()
    triangles = [tuple(face.vertices) for face in mesh.loop_triangles]
    refined = {name: apply_fat_volume(coords, field, CONTROL_POINTS[name]['fat'])
               for name, coords in grid.items()}
    protected = [index for index, offset in enumerate(field) if not any(offset)]
    def coordinate_hash(coords, indices=None):
        values = coords if indices is None else [coords[index] for index in indices]
        return hashlib.sha256(np.asarray(values, dtype='<f8').tobytes()).hexdigest()
    low_hashes, protected_hashes = {}, {}
    for name in CONTROL_POINTS:
        before, after = coordinate_hash(grid[name], protected), coordinate_hash(refined[name], protected)
        if before != after:
            raise RuntimeError('Fat volume changed coordinates outside its fixed support')
        protected_hashes[name] = before
        if CONTROL_POINTS[name]['fat'] <= .4:
            before, after = coordinate_hash(grid[name]), coordinate_hash(refined[name])
            if before != after:
                raise RuntimeError('Fat volume changed an unmodified lean or neutral row')
            low_hashes[name] = before
    muscle_error = 0.
    for fi in range(len(FAT_LEVELS)):
        for mi in range(1, len(MUSCLE_LEVELS)):
            previous, current = f'f{fi}_m{mi-1}', f'f{fi}_m{mi}'
            error = (np.asarray(refined[current]) - np.asarray(refined[previous])
                     - np.asarray(grid[current]) + np.asarray(grid[previous]))
            muscle_error = max(muscle_error, float(np.max(np.abs(error))))
    if muscle_error > height * 1.e-12:
        raise RuntimeError('Fat volume changed the independent muscle offsets')
    before_sections = {name: body_sections(coords, reference, triangles, height) for name, coords in grid.items()}
    after_sections = {name: body_sections(coords, reference, triangles, height) for name, coords in refined.items()}
    before_samples = {name: body_sections(sample_body_grid(grid, *composition), reference, triangles, height)
                      for name, composition in FAT_VOLUME_SAMPLES.items()}
    after_samples = {name: body_sections(sample_body_grid(refined, *composition), reference, triangles, height)
                     for name, composition in FAT_VOLUME_SAMPLES.items()}
    incomplete = [(stage, name, label) for stage, sections in (('existing_sculpture', before_sections), ('refined_sculpture', after_sections))
                  for name in CONTROL_POINTS for label, values in sections[name].items()
                  if values['open_paths'] or values['area_m2'] is None]
    source_diagnostics = []
    for stage, name, label in incomplete[:4]:
        trace = {}
        coords = grid[name] if stage == 'existing_sculpture' else refined[name]
        body_sections(coords, reference, triangles, height, diagnostics=trace, diagnostic_section=label)
        for triangle in trace.get('triangles', []):
            triangle['vertex_skin_weights'] = [skin_rows[index] for index in triangle['vertices']]
        source_diagnostics.append({'stage': stage, 'case_id': name, 'section': label, 'contour': trace})
    receipt = {'version': FAT_VOLUME_VERSION, 'template': args.template, 'height_m': height,
        'body_frame': 'Blender X lateral, Y posterior, Z vertical; metres',
        'authoring_blend': 'smoothstep(clamp((fat-.4)/.6)); runtime remains the existing bilinear grid',
        'recipe_height_fractions': FAT_VOLUME_RECIPES[args.template],
        'source_helper_sha256': hashlib.sha256((ROOT / 'tools/assets/inbody_fat_volume.py').read_bytes()).hexdigest(),
        'source_macros': FAT_VOLUME_SOURCE_RECEIPTS, 'independent_shapes': FAT_VOLUME_STAGE_RECEIPTS,
        'section_reference': 'fixed existing neutral sculpture; actual neutral loop triangles',
        'before_sections': before_sections, 'after_sections': after_sections,
        'sample_compositions': FAT_VOLUME_SAMPLES, 'before_samples': before_samples, 'after_samples': after_samples,
        'source_incomplete_section_count': len(incomplete), 'source_incomplete_diagnostic_cap': 4,
        'source_incomplete_sections': source_diagnostics,
        'exact_low_fat_coordinate_hashes': low_hashes, 'exact_protected_coordinate_hashes': protected_hashes,
        'protected_vertex_count': len(protected), 'affected_vertex_count': len(field) - len(protected),
        'maximum_field_length_m': max(math.sqrt(sum(value * value for value in offset)) for offset in field),
        'max_muscle_delta_error_m': muscle_error, 'medical_calibration': False}
    return refined, receipt


def independent_shapes(grid,faces):
    """Separate MPFB's coupled phenotype into fat surface and outward muscle growth.

    MPFB's macro-muscle slider partly removes a high-weight abdomen. For an
    independent illustration axis we keep that fat surface fixed, then add
    cumulative outward muscle deltas derived from the accepted lean template.
    Midriff/hand/foot/head masks prevent muscle from silently reducing body fat
    or moving the shared skeleton. These are authored visual offsets, not tissue
    volumes inferred from medical measurements.
    """
    def normals(coords):
        temporary=bpy.data.meshes.new('muscle-normal-reference')
        temporary.from_pydata(coords,[],faces); temporary.update()
        result=np.asarray([tuple(v.normal) for v in temporary.vertices])
        bpy.data.meshes.remove(temporary)
        return result
    reference=np.asarray(grid['f1_m2'])
    directions=normals(reference)
    height=reference[:,2].max()
    neighbors=defaultdict(set)
    for face in faces:
        for a,b in zip(face,face[1:]+face[:1]):
            neighbors[a].add(b); neighbors[b].add(a)
    x=np.abs(reference[:,0])/height; z=reference[:,2]/height
    def smooth(values):
        t=np.clip(values,0,1); return t*t*(3-2*t)
    legs=smooth((z-.08)/.07)*smooth((.56-z)/.05)
    chest=smooth((z-.66)/.06)*smooth((.85-z)/.03)
    arms=smooth((x-.105)/.035)*smooth((.31-x)/.055)*smooth((z-.52)/.07)*smooth((.85-z)/.03)
    mask=np.maximum.reduce([legs,chest,arms])
    # Leave the centreline of the inner thighs enough room on heavy shapes.
    inward=(directions[:,0]*reference[:,0]<0)&(z<.48)
    mask[inward]*=smooth((x[inward]-.007)/.035)
    growth=[np.zeros(len(reference))]
    for mi in range(1,len(MUSCLE_LEVELS)):
        delta=np.asarray(grid[f'f1_m{mi}'])-np.asarray(grid[f'f1_m{mi-1}'])
        outward=np.maximum(0,np.sum(delta*directions,axis=1))*mask
        for _ in range(3):
            previous=outward.copy()
            for vertex,surrounding in neighbors.items():
                outward[vertex]=.7*previous[vertex]+.3*sum(previous[j] for j in surrounding)/len(surrounding)
        growth.append(growth[-1]+outward)
    result={}
    for fi,fat in enumerate(FAT_LEVELS):
        reference_muscle=interpolate(fat,[(0.,.5),(.4,.5),(.85,.2),(1.,.2)])
        mi=min(3,int(reference_muscle*4)); t=reference_muscle*4-mi
        base=np.asarray(grid[f'f{fi}_m{mi}'])*(1-t)+np.asarray(grid[f'f{fi}_m{mi+1}'])*t
        outward_directions=normals(base)
        baseline_growth=growth[mi]*(1-t)+growth[mi+1]*t
        scale=interpolate(fat,[(0.,2.),(.2,1.),(1.,1.)])
        for muscle_index in range(len(MUSCLE_LEVELS)):
            displacement=(growth[muscle_index]-baseline_growth)*scale
            shaped=base+outward_directions*displacement[:,None]
            result[f'f{fi}_m{muscle_index}']=[tuple(float(v) for v in row) for row in shaped]
    return result


def sculpture(grid, faces, uvs, weights):
    """Keep body topology/UV and skin weights; replace face by a connected head."""
    global SCULPTURE_HEAD_LANDMARKS
    neutral = grid[NEUTRAL]
    h = max(v[2] for v in neutral)
    cut = .855 * h
    kept_faces = [i for i, f in enumerate(faces) if all(neutral[v][2] < cut for v in f)]
    body_faces = [faces[i] for i in kept_faces]
    body_uvs = [uvs[i] for i in kept_faces]
    edges = Counter(tuple(sorted((a,b))) for f in body_faces for a,b in zip(f, f[1:] + f[:1]))
    adjacency = defaultdict(list)
    for (a,b), count in edges.items():
        if count == 1 and min(neutral[a][2], neutral[b][2]) > cut - .04 * h:
            adjacency[a].append(b); adjacency[b].append(a)
    if any(len(v) != 2 for v in adjacency.values()):
        raise RuntimeError('Expected a simple neck boundary')
    ring = [min(adjacency)]
    while True:
        neighbors = adjacency[ring[-1]]
        following = neighbors[0] if len(ring) == 1 or neighbors[0] != ring[-2] else neighbors[1]
        if following == ring[0]: break
        if following in ring: raise RuntimeError('Invalid neck cycle')
        ring.append(following)
    kept = sorted({v for f in body_faces for v in f})
    remap = {old:new for new,old in enumerate(kept)}
    topology = [tuple(remap[v] for v in f) for f in body_faces]
    count = len(ring)
    cy = sum(neutral[v][1] for v in ring) / count
    neck_z = sum(neutral[v][2] for v in ring) / count
    head_height = h-neck_z
    SCULPTURE_HEAD_LANDMARKS = {'neck_z':neck_z,'head_height':head_height,'head_center_y':cy,
                              'chin_z':neck_z+.18*head_height,'jaw_z':neck_z+.34*head_height,
                              'mouth_z':neck_z+.43*head_height,'eye_z':neck_z+.63*head_height}
    angles = [math.atan2(neutral[v][1] - cy, neutral[v][0]) for v in ring]
    profiles = [(.10,.028,.030,-.001),(.22,.026,.032,-.007),(.34,.033,.039,-.003),
                (.49,.038,.045,0),(.64,.040,.048,.002),(.77,.038,.046,.002),
                (.88,.031,.038,.001),(.96,.020,.024,0),(.99,.010,.012,0)]
    previous = [remap[v] for v in ring]
    for row,_ in enumerate(profiles):
        current = [len(kept) + row * count + i for i in range(count)]
        for i in range(count):
            j = (i + 1) % count
            topology.append((previous[i],previous[j],current[j],current[i]))
            # The normal bake uses a newly packed UV set below, avoiding overlap.
            body_uvs.append([(0.,0.)] * 4)
        previous = current
    top = len(kept) + len(profiles) * count
    for i in range(count):
        topology.append((previous[i],previous[(i+1)%count],top))
        body_uvs.append([(0.,0.)] * 3)
    edge_counts=Counter(tuple(sorted((a,b))) for f in topology for a,b in zip(f,f[1:]+f[:1]))
    if any(count!=2 for count in edge_counts.values()):
        raise RuntimeError('Character surface has an open seam')
    new_weights = [weights[v] for v in kept]
    for row,(fraction,*_) in enumerate(profiles):
        blend = min(1., fraction / .3)
        for i in range(count):
            new_weights.append({'head':blend,'neck03':1.-blend})
    new_weights.append({'head':1.})
    suit = [remap[i] for i in kept if .43*h < neutral[i][2] < .56*h and abs(neutral[i][0]) < .10 and neutral[i][1] < -.025]
    with gzip.open(source / 'src/mpfb/data/targets/breast/nipple-size-decr.target.gz', 'rt') as file:
        nipple = [remap[int(line.split()[0])] for line in file if line.strip() and int(line.split()[0]) in remap]
    result = {}
    for name, coords in grid.items():
        height = max(v[2] for v in coords)
        neck_z = sum(coords[i][2] for i in ring) / count
        cy = sum(coords[i][1] for i in ring) / count
        vertices = [coords[i] for i in kept]
        for fraction,rx,ry,offset in profiles:
            vertices.extend((height*rx*math.cos(a),cy+height*(offset+ry*math.sin(a)),neck_z+(height-neck_z)*fraction) for a in angles)
        vertices.append((0,cy,height))
        mesh = bpy.data.meshes.new('surface')
        vertices = remove_chest_features(vertices, nipple, height)
        vertices = anatomical_forms(vertices, height, CONTROL_POINTS[name]['anatomy'])
        mesh.from_pydata(vertices,[],topology)
        bm = bmesh.new(); bm.from_mesh(mesh); bm.verts.ensure_lookup_table()
        for _ in range(10):
            bmesh.ops.smooth_vert(bm,verts=[bm.verts[i] for i in suit],factor=.5,use_axis_x=False,use_axis_y=True,use_axis_z=False)
        shoulders=[v for v in bm.verts if .765*height<v.co.z<.83*height and .08*height<abs(v.co.x)<.16*height]
        for _ in range(4):
            bmesh.ops.smooth_vert(bm,verts=shoulders,factor=.20,use_axis_x=True,use_axis_y=True,use_axis_z=True)
        bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces))
        bm.to_mesh(mesh); bm.free()
        result[name] = [tuple(v.co) for v in mesh.vertices]
        topology = [tuple(f.vertices) for f in mesh.polygons]
        bpy.data.meshes.remove(mesh)
    return result, topology, body_uvs, new_weights


def remove_chest_features(vertices,nipple,height):
    """Replace dense nipple rings by a smooth convex continuation of the chest.

    Fitting a plane across an entire breast erased its curvature and made a
    circular crater at the patch boundary. A local quadratic fitted to the
    surrounding skin retains the dome; a positive semidefinite curvature and
    broad C1 transition prevent an inverted cup or a new point-like cone.
    """
    result=np.asarray(vertices,dtype=float).copy()
    for sign in [-1,1]:
        patch=[i for i in nipple if result[i,0]*sign>0]
        center=result[patch].mean(axis=0)
        u=(result[:,0]-center[0])/(.033*height)
        v=(result[:,2]-center[2])/(.028*height)
        radius=np.sqrt(u*u+v*v)
        front=result[:,1]<-.015*height
        sample=(radius>.95)&(radius<1.75)&front
        sample[patch]=False
        design=np.stack([np.ones(len(u)),u,v,u*u,u*v,v*v],axis=1)
        sample_weights=np.exp(-radius[sample]**2/2.)
        coefficients=np.linalg.lstsq(design[sample]*sample_weights[:,None],
                                     result[sample,1]*sample_weights,rcond=None)[0]
        curvature=np.asarray([[coefficients[3],coefficients[4]/2.],
                              [coefficients[4]/2.,coefficients[5]]])
        eigenvalues,eigenvectors=np.linalg.eigh(curvature)
        floor=.0015*height if args.template=='female' else 0.
        curvature=eigenvectors@np.diag(np.clip(eigenvalues,floor,.018*height))@eigenvectors.T
        coefficients[3],coefficients[4],coefficients[5]=curvature[0,0],2*curvature[0,1],curvature[1,1]
        # Refit the level after correcting curvature so that the patch still
        # meets the observed surrounding skin rather than shifting the chest.
        residual=result[sample,1]-design[sample,1:]@coefficients[1:]
        coefficients[0]=np.average(residual,weights=sample_weights)
        fit=design@coefficients
        weight=np.clip((1.65-radius)/.85,0.,1.)
        weight=weight*weight*(3-2*weight)*front
        result[:,1]=result[:,1]*(1-weight)+fit*weight
    return [tuple(v) for v in result]


def gaussian(value, center, width):
    return math.exp(-((value-center)/width)**2)


def anatomical_forms(vertices, height, strength):
    """Artist-authored shallow muscle masses in rest coordinates, all in metres.

    This changes real silhouette/surface geometry. Not an anatomical estimate from
    InBody: these three synthetic art anchors exist only for visual evaluation.
    """
    result=[]
    for vx,vy,vz in vertices:
        x,y,z=vx/height,vy/height,vz/height
        front=max(0.,min(1.,(-y-.015)/.035))
        back=max(0.,min(1.,(y-.015)/.035))
        front_detail=0.
        # Paired rectus abdominis lobes, tapering toward pelvis. Their broad form
        # lives in geometry; small separations are refined in the normal bake.
        for cz,width,amplitude in [(.677,.018,.0045),(.641,.019,.0048),(.605,.020,.003)]:
            offset=.0025 if x>0 else -.001
            front_detail+=amplitude*gaussian(abs(x),.027,.021)*gaussian(z,cz+offset,width)
        front_detail-=.0007*gaussian(x,0,.005)*gaussian(z,.645,.061)
        # Oblique ridge and three serratus slips below the outer pectoral.
        ridge=.074+(z-.62)*.40
        front_detail+=.0030*gaussian(abs(x),ridge,.011)*gaussian(z,.644,.064)
        for cz in [.690,.712,.734]:
            front_detail+=(.0020 if args.template == 'male' else .0007)*gaussian(abs(x),.089,.015)*gaussian(z,cz+(abs(x)-.089)*.5,.007)
        # Rectus femoris and teardrop-shaped vastus medialis over the knee.
        front_detail+=.0045*gaussian(abs(x),.090,.022)*gaussian(z,.393,.073)
        front_detail+=.0035*gaussian(abs(x),.065,.014)*gaussian(z,.300,.033)
        back_detail=.0035*gaussian(abs(x),.026,.018)*gaussian(z,.67,.10)
        back_detail+=.0030*gaussian(abs(x),.074,.027)*gaussian(z,.737,.058)
        sculpted=Vector((vx,vy+height*strength*(-front*front_detail+back*back_detail),vz))
        # Biceps/triceps bellies follow the rest upper-arm axis, not global scale.
        for sign in [-1,1]:
            shoulder=Vector((sign*.108,-.010,.795))*height
            elbow=Vector((sign*.203,-.009,.686))*height
            axis=elbow-shoulder; length=axis.length; axis.normalize()
            relative=Vector((vx,vy,vz))-shoulder; t=relative.dot(axis)/length
            radial=relative-axis*relative.dot(axis)
            if -.05<t<1.10 and radial.length<.063*height:
                anterior=Vector((0,-1,0)); anterior=(anterior-axis*anterior.dot(axis)).normalized()
                facing=radial.normalized().dot(anterior) if radial.length else 0.
                bulge=.0075*height*gaussian(t,.50,.29)*max(0.,facing)
                triceps=.0050*height*gaussian(t,.58,.30)*max(0.,-facing)
                fade=max(0.,min(1.,(abs(vx)/height-.120)/.040))
                radial_fade=max(0.,min(1.,(.065*height-radial.length)/(.02*height)))
                fade=fade*fade*(3-2*fade)*radial_fade*radial_fade*(3-2*radial_fade)
                sculpted+=anterior*float(strength*fade*(bulge-triceps))
        result.append(tuple(sculpted))
    return result


def smooth_shoulder_weights(mesh,weights,height):
    """Blend MPFB's deltoid/girdle influences across the curated shoulder surface.

    The shaped body has wider deltoids than the neutral imported rig. A local
    Laplacian weight pass avoids a sharp upper-arm/shoulder deformation boundary.
    Vertex positions and physique morphs stay untouched.
    """
    neighbors=defaultdict(set)
    for edge in mesh.edges:
        a,b=edge.vertices; neighbors[a].add(b); neighbors[b].add(a)
    def smooth(value):
        t=max(0.,min(1.,value)); return t*t*(3-2*t)
    factors={}
    for vertex in mesh.vertices:
        x,z=abs(vertex.co.x)/height,vertex.co.z/height
        factor=.5*smooth((x-.065)/.035)*smooth((.20-x)/.04)*smooth((z-.735)/.035)*smooth((.865-z)/.04)
        if factor>.001: factors[vertex.index]=factor
    result=[dict(row) for row in weights]
    for _ in range(10):
        previous=result; result=[dict(row) for row in previous]
        for i,factor in factors.items():
            surrounding=neighbors[i]
            names=set(previous[i]).union(*(previous[j] for j in surrounding))
            result[i]={name:(1-factor)*previous[i].get(name,0)+factor*sum(previous[j].get(name,0) for j in surrounding)/len(surrounding) for name in names}
    return result


def material():
    result = bpy.data.materials.new('InBody sculpt / anatomy')
    result.use_nodes = True
    shader = result.node_tree.nodes.get('Principled BSDF')
    shader.inputs['Base Color'].default_value = (.29,.32,.34,1)
    shader.inputs['Roughness'].default_value = .54
    shader.inputs['Metallic'].default_value = .04
    return result


def corrective_shapes(obj, rig, height):
    """Four local additive surface corrections; no change to rest rig or 30 forms."""
    base = [vertex.co.copy() for vertex in obj.data.vertices]
    for descriptor in POSE_CORRECTIVES:
        side = descriptor['bone'].rsplit('.', 1)[1]
        shoulder = rig.data.bones['upperarm01.' + side].head_local
        elbow = rig.data.bones['lowerarm01.' + side].head_local
        axis = (elbow - shoulder).normalized()
        center = shoulder if 'shoulder' in descriptor['name'] else elbow
        key = obj.shape_key_add(name=descriptor['name'])
        for vertex, point in zip(key.data, base):
            if point.z >= .855 * height:
                vertex.co = point
                continue
            relative = point - center
            along = relative.dot(axis)
            radial = relative - axis * along
            if 'shoulder' in descriptor['name']:
                support = gaussian(along / height, .015, .035) * gaussian(radial.length / height, .031, .025)
                displacement = radial.normalized() * (.0055 * height * support) if radial.length else Vector((0, 0, 0))
            else:
                support = gaussian(along / height, 0., .023) * gaussian(radial.length / height, .021, .017)
                displacement = radial.normalized() * (.0035 * height * support) if radial.length else Vector((0, 0, 0))
            vertex.co = point + displacement


def appearance_material(name, color, roughness=.7, metallic=0.):
    result = bpy.data.materials.new(name)
    result.use_nodes = True
    shader = result.node_tree.nodes.get('Principled BSDF')
    shader.inputs['Base Color'].default_value = color
    shader.inputs['Roughness'].default_value = roughness
    shader.inputs['Metallic'].default_value = metallic
    return result


def skinned_appearance(name, coords, faces, weights, rig, material, shapes=None):
    mesh = bpy.data.meshes.new(name + ' mesh')
    mesh.from_pydata(coords, [], faces); mesh.update()
    obj = bpy.data.objects.new(name, mesh); bpy.context.collection.objects.link(obj)
    mesh.materials.append(material)
    for polygon in mesh.polygons: polygon.use_smooth = True
    for index, row in enumerate(weights):
        total = sum(row.values())
        for bone, weight in row.items():
            group = obj.vertex_groups.get(bone) or obj.vertex_groups.new(name=bone)
            group.add([index], weight / total, 'REPLACE')
    obj.parent = rig
    modifier = obj.modifiers.new('Skin', 'ARMATURE'); modifier.object = rig
    if shapes:
        obj.shape_key_add(name='Basis')
        for shape, points in shapes.items():
            key = obj.shape_key_add(name='shape_' + shape); key.value = float(shape == NEUTRAL)
            for vertex, point in zip(key.data, points): vertex.co = point
    return obj


def wrap_bone(name):
    """The pelvis cage follows the torso and legs, never a hand beside the hem."""
    return name == 'root' or name.startswith(('pelvis.', 'spine', 'upperleg', 'lowerleg', 'foot', 'toe'))


def wrap_body_indices(weights):
    return [index for index, row in enumerate(weights)
            if sum(weight for bone, weight in row.items() if wrap_bone(bone)) > .7]


def wrap_skin_weights(body_coords, body_weights, cloth_coords):
    """Transfer the nearest pelvis skin weights with the same mask as fitting.

    The relaxed hands can be nearer an outer garment vertex than the hip. An
    unrestricted nearest search attached cloth to those hands and tore the wrap
    when the arms opened. Restrict both the source vertices and copied bones;
    normalize after the four-influence limit used by the exported skin.
    """
    eligible = wrap_body_indices(body_weights)
    if not eligible:
        raise RuntimeError('Missing lower-body skin samples for clothing')
    result = []
    for point in cloth_coords:
        def distance(index):
            return sum((a - b) ** 2 for a, b in zip(body_coords[index], point))
        nearest = sorted(eligible, key=distance)[:4]
        row = defaultdict(float)
        for index in nearest:
            influence = 1. / max(distance(index), .000001)
            for bone, weight in body_weights[index].items():
                if wrap_bone(bone):
                    row[bone] += weight * influence
        values = sorted(row.items(), key=lambda item: item[1], reverse=True)[:4]
        total = sum(weight for _, weight in values)
        if total <= 0.:
            raise RuntimeError('Missing retained clothing skin influence')
        result.append({bone: weight / total for bone, weight in values})
    return result


def wrap_fit_ring(band, z, height, sections, pleat_fraction, hem_fraction):
    """Offset an already containing ellipse; diagonal fitting cannot eat the gap.

    Adding clearance to the axis bounds before the containment scale leaves the
    farthest diagonal sample on the final ellipse. Fit first, then offset its
    surface along the ellipse normal by the existing clothing clearance.
    """
    rx = max(abs(point[0]) for point in band)
    front, back = min(point[1] for point in band), max(point[1] for point in band)
    cy, ry = (front + back) / 2., (back - front) / 2.
    if rx <= 0. or ry <= 0.:
        raise RuntimeError('Missing pelvis extent for clothing fit')
    contain = max(1., max(math.hypot(point[0] / rx, (point[1] - cy) / ry) for point in band))
    rx *= contain
    ry *= contain
    result = []
    for section in range(sections):
        angle = 2 * math.pi * section / sections
        c, s = math.cos(angle), math.sin(angle)
        nx, ny = c / rx, s / ry
        length = math.hypot(nx, ny)
        offset = (.014 + pleat_fraction * math.cos(12 * angle)) * height
        hem = hem_fraction * height * math.cos(6 * angle)
        result.append((rx * c + offset * nx / length,
                       cy + ry * s + offset * ny / length, z * height + hem))
    return result


def wrap_intersection_details(pairs, body_triangles, cloth_triangles, body_points, cloth_points,
                              body_weights, cloth_weights, sections, limit=128):
    """Keep contact evidence, including a hand that lies outside the fit mask."""
    def vertex(index, points, weights, clothing=False):
        result = {'index': index, 'position_m': tuple(points[index]),
                  'skin_weights': dict(sorted(weights[index].items()))}
        if clothing:
            result.update(row=index // sections, section=index % sections)
        return result
    result = []
    for cloth_index, body_index in sorted(set(pairs))[:limit]:
        result.append({'cloth_triangle': cloth_index, 'body_triangle': body_index,
                       'cloth_vertices': [vertex(index, cloth_points, cloth_weights, True)
                                          for index in cloth_triangles[cloth_index]],
                       'body_vertices': [vertex(index, body_points, body_weights)
                                         for index in body_triangles[body_index]]})
    return result


def wrap_failure(obj, cloth, body_mesh, cloth_mesh, shape, composition, stance, height,
                 body_triangles, cloth_triangles, intersections, sections, clearance=None):
    """Publish only bounded derived geometry diagnostics, never authoring input."""
    def weights(target):
        return [{target.vertex_groups[group.group].name: group.weight for group in vertex.groups}
                for vertex in target.data.vertices]
    body_weights, cloth_weights = weights(obj), weights(cloth)
    contacts = wrap_intersection_details(intersections, body_triangles, cloth_triangles,
                                        [tuple(vertex.co) for vertex in body_mesh.vertices],
                                        [tuple(vertex.co) for vertex in cloth_mesh.vertices],
                                        body_weights, cloth_weights, sections)
    def unskinned(target, index):
        point = target.data.vertices[index].co.copy()
        for key in target.data.shape_keys.key_blocks[1:]:
            if key.value:
                point += (key.data[index].co - key.relative_key.data[index].co) * key.value
        return tuple(point)
    for contact in contacts:
        for label, target in [('body_vertices', obj), ('cloth_vertices', cloth)]:
            for vertex in contact[label]:
                vertex['morphed_unskinned_position_m'] = unskinned(target, vertex['index'])
        triangle = body_mesh.loop_triangles[contact['body_triangle']]
        uv_layer = body_mesh.uv_layers.active
        contact['body_triangle_uv'] = ([tuple(uv_layer.data[loop].uv) for loop in triangle.loops]
                                      if uv_layer is not None else None)
    if clearance is not None:
        body_index = clearance['body_vertex']
        clearance['body_skin_weights'] = body_weights[body_index]
        clearance['body_morphed_unskinned_position_m'] = unskinned(obj, body_index)
        clearance['cloth_triangle_vertices'] = [
            {'index': index, 'row': index // sections, 'section': index % sections,
             'position_m': tuple(cloth_mesh.vertices[index].co), 'skin_weights': cloth_weights[index],
             'morphed_unskinned_position_m': unskinned(cloth, index)}
            for index in cloth_triangles[clearance['cloth_triangle']]]
    receipt = {'version': 'body-garment-contact-v1', 'template': args.template,
               'exporter_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               'character_id': args.character, 'shape': shape,
               'composition': {'fat': composition[0], 'muscle': composition[1]}, 'held_pose': stance,
               'body_height_m': height, 'intersection_pair_count': len(intersections),
               'reported_pair_count': len(contacts), 'contact_limit': 128, 'contacts': contacts,
               'body_morph_weights': {key.name: key.value for key in obj.data.shape_keys.key_blocks[1:]},
               'cloth_morph_weights': {key.name: key.value for key in cloth.data.shape_keys.key_blocks[1:]},
               'clearance_failure': clearance,
               'scope': 'actual evaluated pre-export authoring meshes; body/cloth coordinates are derived, no raw source assets'}
    path = args.review / 'garment-fit-failure.json'
    path.write_text(json.dumps(receipt, indent=2) + '\n')
    print('GARMENT_FIT_FAILURE ' + json.dumps(receipt, separators=(',', ':')), flush=True)


def wrap_clearance(obj, cloth, rig, grid, eligible, height, sections):
    """Measure skinned clearance on anchors, cell midpoints, and art presets.

    Distance samples exclude the open-edge strips and use the covered pelvis/
    upper-thigh surface. Intersection checks use the entire evaluated body and
    cloth, including the pleats, hem variation, and nearby hands.
    """
    from mathutils.bvhtree import BVHTree
    records = {}
    required = .005 * height
    physiques = {shape: (CONTROL_POINTS[shape]['fat'], CONTROL_POINTS[shape]['muscle_axis']) for shape in grid}
    physiques.update({f'preset_{name}': values for name, values in PRESETS.items()})
    runtime_art_presets = {'runtime_art_mixed': (.85, 1.)}
    physiques.update(runtime_art_presets)
    for fi in range(len(FAT_LEVELS) - 1):
        for mi in range(len(MUSCLE_LEVELS) - 1):
            physiques[f'midpoint_f{fi}_m{mi}'] = ((FAT_LEVELS[fi] + FAT_LEVELS[fi + 1]) / 2.,
                                                 (MUSCLE_LEVELS[mi] + MUSCLE_LEVELS[mi + 1]) / 2.)
    for shape, composition in physiques.items():
        set_physique(obj, composition)
        for stance in ('relaxed', 'flex', 'open'):
            pose(rig, stance)
            set_pose_corrections(obj, rig)
            bpy.context.view_layer.update()
            depsgraph = bpy.context.evaluated_depsgraph_get()
            evaluated_body = obj.evaluated_get(depsgraph)
            evaluated_cloth = cloth.evaluated_get(depsgraph)
            body_mesh = evaluated_body.to_mesh()
            cloth_mesh = evaluated_cloth.to_mesh()
            try:
                cloth_mesh.calc_loop_triangles()
                coords = [vertex.co.copy() for vertex in cloth_mesh.vertices]
                triangles = [tuple(face.vertices) for face in cloth_mesh.loop_triangles]
                tree = BVHTree.FromPolygons(coords, triangles, all_triangles=True)
                body_mesh.calc_loop_triangles()
                body_triangles = [tuple(face.vertices) for face in body_mesh.loop_triangles]
                body_tree = BVHTree.FromPolygons([vertex.co.copy() for vertex in body_mesh.vertices],
                                                 body_triangles,
                                                 all_triangles=True)
                intersections = tree.overlap(body_tree)
                if intersections:
                    wrap_failure(obj, cloth, body_mesh, cloth_mesh, shape, composition, stance, height,
                                 body_triangles, triangles, intersections, sections)
                    raise RuntimeError(f'Wrap intersects the body: {shape}/{stance}: {len(intersections)} triangle pairs')
                lower = max(point.z for point in coords[-sections:]) + .004 * height
                upper = min(point.z for point in coords[:sections]) - .004 * height
                minimum = math.inf
                samples = 0
                closest = None
                for index in eligible:
                    point = body_mesh.vertices[index].co
                    if not lower < point.z < upper:
                        continue
                    nearest, normal, triangle, _ = tree.find_nearest(point)
                    if nearest is None:
                        raise RuntimeError('Missing evaluated cloth side-wall sample')
                    distance = (nearest - point).dot(normal)
                    if distance < minimum:
                        minimum = distance
                        closest = {'body_vertex': index, 'cloth_triangle': triangle,
                                   'body_position_m': tuple(point), 'cloth_nearest_position_m': tuple(nearest),
                                   'cloth_normal': tuple(normal), 'minimum_signed_clearance_m': distance,
                                   'required_signed_clearance_m': required}
                    samples += 1
                if not samples or minimum < required:
                    wrap_failure(obj, cloth, body_mesh, cloth_mesh, shape, composition, stance, height,
                                 body_triangles, triangles, intersections, sections, closest)
                    raise RuntimeError(f'Insufficient wrap clearance: {shape}/{stance}: {minimum:.6f} m')
                records[f'{shape}/{stance}'] = {'samples': samples, 'minimum_signed_clearance_m': minimum,
                                               'body_triangle_intersections': len(intersections)}
            finally:
                evaluated_body.to_mesh_clear()
                evaluated_cloth.to_mesh_clear()
    set_physique(obj, 'neutral')
    pose(rig, 'relaxed')
    set_pose_corrections(obj, rig)
    return {'method': 'evaluated skinned body to triangulated cloth side-wall BVH',
            'required_signed_clearance_m': required, 'edge_margin_m': .004 * height,
            'body_shape_count': len(grid), 'held_poses': ['relaxed', 'flex', 'open'],
            'interpolation_cell_midpoint_count': (len(FAT_LEVELS) - 1) * (len(MUSCLE_LEVELS) - 1),
            'art_preset_count': len(PRESETS),
            'extra_runtime_art_presets': runtime_art_presets,
            'scope': 'sampled anchors, bilinear cell midpoints and presets in held poses; not all interpolation values or animation times',
            'states': records, 'minimum_signed_clearance_m': min(row['minimum_signed_clearance_m'] for row in records.values())}


def spartan_appearance(obj, rig, grid, height):
    """Morphing short wrap over the unchanged faceless production mannequin."""
    result = []
    sections = 48
    levels = [.548, .526, .498, .468, .438]
    body_coords = [tuple(vertex.co) for vertex in obj.data.vertices]
    body_weights = [{obj.vertex_groups[group.group].name: group.weight for group in vertex.groups}
                    for vertex in obj.data.vertices]
    eligible = wrap_body_indices(body_weights)
    wrap_shapes = {}
    for shape, points in grid.items():
        coords = []
        for row, z in enumerate(levels):
            # Fit each row against that shape's actual pelvis/upper thigh surface.
            band = [points[index] for index in eligible if abs(points[index][2] / height - z) < .014]
            assert band, 'Missing pelvis sample for clothing fit'
            coords.extend(wrap_fit_ring(band, z, height, sections,
                                        .0015 if row > 0 else .0005,
                                        .002 if row == len(levels) - 1 else 0.))
        wrap_shapes[shape] = coords
    faces = [((row + 1) * sections + section, (row + 1) * sections + (section + 1) % sections,
              row * sections + (section + 1) % sections, row * sections + section)
             for row in range(len(levels) - 1) for section in range(sections)]
    cloth_material = appearance_material('Spartan oxblood woven wrap', (.19,.035,.030,1), .86)
    weights = wrap_skin_weights(body_coords, body_weights, wrap_shapes[NEUTRAL])
    cloth = skinned_appearance('SpartanWrap', wrap_shapes[NEUTRAL], faces, weights, rig, cloth_material, wrap_shapes)
    result.append(cloth)
    fit = wrap_clearance(obj, cloth, rig, grid, eligible, height, sections)
    fit['ring_construction'] = 'per-shape containing pelvis ellipse with outward-normal clearance before pleats'
    fit['ring_clearance_before_pleats_m'] = .014 * height
    fit['skin_bones'] = sorted({bone for row in weights for bone in row})
    fit['forbidden_skin_influences'] = sum(not wrap_bone(bone) for row in weights for bone in row)
    if fit['forbidden_skin_influences']:
        raise RuntimeError('Clothing skin includes an arm or hand influence')
    return result, fit


def pose(rig, name):
    for bone in rig.pose.bones:
        bone.rotation_mode = 'QUATERNION'
        bone.matrix_basis = Matrix.Identity(4)
    for side, sign in [('L',1),('R',-1)]:
        controls = {
            'relaxed': (2,4,-20,0,-30,-40),
            'flex': (4,4,0,-45,75,0),
            'open': (10,8,40,0,-32,0),
        }[name]
        clavicle, shoulder, upper_z, upper_y, lower_x, lower_y = controls
        for bone_name, xyz in [
            (f'clavicle.{side}',(0,0,clavicle*sign)),
            (f'shoulder01.{side}',(0,0,shoulder*sign)),
            (f'upperarm01.{side}',(0,upper_y*sign,upper_z*sign)),
            (f'upperarm02.{side}',(0,(-45 if name=='flex' else 0)*sign,0)),
            (f'lowerarm01.{side}',(lower_x,lower_y*sign,0)),
        ]:
            bone=rig.pose.bones[bone_name]
            from mathutils import Euler
            bone.rotation_quaternion=Euler(tuple(math.radians(v) for v in xyz),'XYZ').to_quaternion()
    from mathutils import Euler
    for side,sign in [('L',1),('R',-1)]:
        for finger in range(2,6):
            for joint,angle in enumerate(([60,75,35] if name=='flex' else [8,12,5]),1):
                bone=rig.pose.bones.get(f'finger{finger}-{joint}.{side}')
                if bone: bone.rotation_quaternion=Quaternion((1,0,0),math.radians(angle))
        if name=='flex':
            for joint,xyz in [(1,(20,0,-40*sign)),(2,(25,0,0)),(3,(25,0,0))]:
                bone=rig.pose.bones[f'finger1-{joint}.{side}']
                bone.rotation_quaternion=Euler(tuple(math.radians(v) for v in xyz),'XYZ').to_quaternion()
    bpy.context.view_layer.update()
    bpy.context.view_layer.update()


def animation(rig):
    rig.animation_data_create()
    rig.animation_data.action = None
    for track in list(rig.animation_data.nla_tracks):
        rig.animation_data.nla_tracks.remove(track)
    for action in list(bpy.data.actions):
        if action.name in {'relaxed', 'flex', 'open', *SCENARIOS} and action.users == 0:
            bpy.data.actions.remove(action)
    bpy.context.scene.render.fps = 24
    for name in ('relaxed','flex','open'):
        action = bpy.data.actions.new(name)
        rig.animation_data.action = action
        pose(rig,name)
        for frame in (0,24):
            for bone in rig.pose.bones:
                bone.keyframe_insert(data_path='location',frame=frame,group=bone.name)
                bone.keyframe_insert(data_path='rotation_quaternion',frame=frame,group=bone.name)
                bone.keyframe_insert(data_path='scale',frame=frame,group=bone.name)
        rig.animation_data.action = None
        track = rig.animation_data.nla_tracks.new(); track.name = name
        strip = track.strips.new(name,0,action); strip.name = name
        track.mute = True
    pose(rig,'relaxed')
    return build_scenarios(rig, pose)


def secondary_relief(vertices, height, strength):
    """Only small tendinous separations; coarse muscle masses already exist below."""
    result = []
    for vx, vy, vz in vertices:
        x, y, z = vx / height, vy / height, vz / height
        front = max(0., min(1., (-y - .015) / .035))
        back = max(0., min(1., (y - .015) / .035))
        depth = .0004 * gaussian(x, 0., .005) * gaussian(z, .644, .058)
        for line in (.659, .623):
            depth += .0006 * gaussian(abs(x), .028, .025) * gaussian(z, line + (.002 if x > 0 else 0), .0045)
        depth += .00032 * gaussian(abs(x), .08, .006) * gaussian(z, .727, .024)
        spine = .00035 * gaussian(x, 0., .005) * gaussian(z, .72, .085)
        result.append((vx, vy + height * strength * (front * depth - back * spine), vz))
    return result


def bake_normals(obj,rig,height):
    """Bake secondary details on matching subdivided body surfaces.

    Large anatomical masses remain in the low geometry. The high meshes add
    only secondary relief after subdivision; each uses its matching low physique
    and the existing shared UV atlas. Low positions and all morphs stay untouched.
    """
    def low_surface_fingerprint():
        digest = hashlib.sha256()
        for key in obj.data.shape_keys.key_blocks:
            digest.update(key.name.encode() + b'\0')
            digest.update(np.asarray([tuple(point.co) for point in key.data], dtype='<f4').tobytes())
        digest.update(np.asarray([tuple(loop.uv) for loop in obj.data.uv_layers.active.data], dtype='<f4').tobytes())
        return digest.hexdigest()
    low_surface_before = low_surface_fingerprint()
    scene=bpy.context.scene
    for bone in rig.pose.bones: bone.matrix_basis=Matrix.Identity(4)
    obj.modifiers['Skin'].show_viewport=False
    obj.modifiers['Skin'].show_render=False
    material=obj.data.materials[0]; nodes=material.node_tree.nodes
    texture=nodes.new('ShaderNodeTexImage'); texture.name='Baked anatomical relief'
    scene.render.engine='CYCLES'; scene.cycles.samples=8
    scene.render.bake.use_selected_to_active=True
    scene.render.bake.cage_extrusion=.025
    scene.render.bake.max_ray_distance=.05
    scene.render.bake.margin=12
    scene.render.bake.normal_space='TANGENT'
    images, maps = {}, {}
    high_vertex_counts = []
    # Keep the previous candidate gains for the first secondary-only comparison.
    # They originated in an approximate broad+secondary response match and do
    # not establish amplitude equivalence after removing the repeated anatomy.
    recipes = [('soft', 'soft', 2. / 27.), ('neutral', 'neutral', 70. / 81.),
               ('defined', 'athletic', 1.25)]
    for label, preset, enhancement in recipes:
        set_physique(obj,preset)
        bpy.context.view_layer.update()
        depsgraph=bpy.context.evaluated_depsgraph_get()
        high_mesh=bpy.data.meshes.new_from_object(obj.evaluated_get(depsgraph),preserve_all_data_layers=True,depsgraph=depsgraph)
        high=bpy.data.objects.new('High resolution ' + label + ' secondary bake source',high_mesh)
        bpy.context.collection.objects.link(high)
        select(high)
        subdiv=high.modifiers.new('Sculpt subdivision','SUBSURF'); subdiv.levels=3
        bpy.ops.object.modifier_apply(modifier=subdiv.name)
        dense = [tuple(vertex.co) for vertex in high.data.vertices]
        high_vertex_counts.append(len(dense))
        detailed = secondary_relief(dense, height, enhancement)
        displacement_min, displacement_max = [math.inf] * 3, [-math.inf] * 3
        max_distance, head_max_distance, head_vertices = 0., 0., 0
        for vertex, original, coord in zip(high.data.vertices, dense, detailed):
            source_point = Vector(original)
            fade = max(0., min(1., (.855 * height - source_point.z) / (.025 * height)))
            fade = fade * fade * (3. - 2. * fade)
            point = source_point.lerp(Vector(coord), fade)
            delta = point - source_point
            if not all(math.isfinite(value) for value in point):
                raise RuntimeError('Nonfinite body surface enhancement')
            max_distance = max(max_distance, delta.length)
            for axis in range(3):
                displacement_min[axis] = min(displacement_min[axis], delta[axis])
                displacement_max[axis] = max(displacement_max[axis], delta[axis])
            if source_point.z >= .855 * height:
                head_vertices += 1
                head_max_distance = max(head_max_distance, delta.length)
            vertex.co = point
        if max_distance > scene.render.bake.cage_extrusion:
            raise RuntimeError('Body surface enhancement exceeds the unchanged bake cage')
        if not head_vertices or head_max_distance != 0.:
            raise RuntimeError('Body surface enhancement must leave the production head untouched')
        high.data.update()
        image=bpy.data.images.new('relief-' + label,width=2048,height=2048,alpha=False,float_buffer=False)
        image.colorspace_settings.name='Non-Color'; texture.image=image
        nodes.active=texture; texture.select=True
        select(high); obj.select_set(True); bpy.context.view_layer.objects.active=obj
        bpy.ops.object.bake(type='NORMAL')
        image.filepath_raw=str((args.output/f'relief-{label}.png').resolve()); image.file_format='PNG'; image.save(); image.pack()
        images[label] = image
        maps[label] = {'size': [2048, 2048], 'sha256': hashlib.sha256(Path(image.filepath_raw).read_bytes()).hexdigest(),
                       'reference_shape': {'fat': PRESETS[preset][0], 'muscle': PRESETS[preset][1]},
                       'surface_enhancement_gain': enhancement,
                       'surface_field': {'kind': 'secondary-only-after-subdivision',
                                         'coarse_anatomy_reapplied': False,
                                         'displacement_min_m': displacement_min,
                                         'displacement_max_m': displacement_max,
                                         'max_distance_m': max_distance,
                                         'max_secondary_distance_m': max_distance,
                                         'cage_extrusion_m': scene.render.bake.cage_extrusion,
                                         'head_cut_height_fraction': .855,
                                         'head_fade_height_fraction': .025,
                                         'head_vertices': head_vertices,
                                         'head_max_distance_m': head_max_distance}}
        baked_high_mesh=high.data
        bpy.data.objects.remove(high,do_unlink=True)
        bpy.data.meshes.remove(baked_high_mesh)
    texture.image = images['neutral']
    normal=nodes.new('ShaderNodeNormalMap'); normal.inputs['Strength'].default_value=BODY_NORMAL_STRENGTH
    material.node_tree.links.new(texture.outputs['Color'],normal.inputs['Color'])
    material.node_tree.links.new(normal.outputs['Normal'],nodes.get('Principled BSDF').inputs['Normal'])
    stats={'method':'Cycles selected-to-active tangent normal bake','size':[2048,2048],
           'high_vertices':min(high_vertex_counts),'subdivision':3,
           'recipe':'secondary-relief-only-v1',
           'coarse_anatomy_preserved_in_low':True,
           'procedural_sculpt':'matched subdivided low surface plus secondary relief only; coarse anatomy preserved in low geometry',
           'response_calibration': {'method': 'inherited candidate gains; secondary-only amplitude equivalence not established',
                                    'runtime_strength': BODY_NORMAL_STRENGTH,
                                    'offline_reference_strengths': {'soft': .08, 'neutral': .4, 'athletic': .75},
                                    'production_runtime_reference_strengths': {'soft': .08037, 'neutral': .3648, 'athletic': .779},
                                    'production_runtime_source': {'commit': 'eebec8980bd319cff622937d579c21fedc66eee3',
                                                                  'file': 'web/src/sections/personal/character/morphology.ts',
                                                                  'function': 'compositionNormalStrength'},
                                    'reference_relief_weights': {'soft': [.925, .075, 0.], 'neutral': [.25, .75, 0.],
                                                                'athletic': [0., 0., 1.]},
                                    'regional_strengths': {'torso': 1., 'arms': .85, 'legs': .8},
                                    'scope': 'historical broad+secondary references only; retained gains do not calibrate the new secondary-only surface'},
           'maps': maps}
    obj.modifiers['Skin'].show_viewport=True; obj.modifiers['Skin'].show_render=True
    set_physique(obj,'neutral'); pose(rig,'relaxed')
    # Bake a spatial RGB region mask through the same packed UVs. The head,
    # hands and feet receive zero relief; overlapping transition masks normalize.
    region=obj.data.color_attributes.new(name='Relief regions',type='FLOAT_COLOR',domain='CORNER')
    for loop in obj.data.loops:
        point=obj.data.vertices[loop.vertex_index].co; x=abs(point.x)/height; z=point.z/height
        def smooth(value):
            t=max(0.,min(1.,value)); return t*t*(3-2*t)
        torso=smooth((z-.52)/.04)*smooth((.855-z)/.04)*smooth((.145-x)/.035)
        arms=smooth((x-.105)/.045)*smooth((.32-x)/.035)*smooth((z-.53)/.045)*smooth((.85-z)/.03)
        legs=smooth((z-.08)/.05)*smooth((.56-z)/.05)
        total=max(1.,torso+arms+legs)
        region.data[loop.index].color=(torso/total,arms/total,legs/total,1.)
    colors=nodes.new('ShaderNodeVertexColor'); colors.layer_name=region.name
    emission=nodes.new('ShaderNodeEmission'); material.node_tree.links.new(colors.outputs['Color'],emission.inputs['Color'])
    output=nodes.get('Material Output'); original=output.inputs['Surface'].links[0].from_socket
    material.node_tree.links.new(emission.outputs[0],output.inputs['Surface'])
    mask=bpy.data.images.new('relief-regions',width=1024,height=1024,alpha=False,float_buffer=False)
    mask.colorspace_settings.name='Non-Color'
    mask_node=nodes.new('ShaderNodeTexImage'); mask_node.image=mask; nodes.active=mask_node
    scene.render.bake.use_selected_to_active=False
    select(obj); bpy.ops.object.bake(type='EMIT')
    mask.filepath_raw=str((args.output/'relief-regions.png').resolve()); mask.file_format='PNG'; mask.save(); mask.pack()
    material.node_tree.links.new(original,output.inputs['Surface'])
    for node in (colors,emission,mask_node): nodes.remove(node)
    obj.data.color_attributes.remove(region)
    nodes.active=texture
    stats['region_mask']={'size':[1024,1024],'channels':['torso','arms','legs'],
                          'sha256':hashlib.sha256((args.output/'relief-regions.png').read_bytes()).hexdigest()}
    low_surface_after = low_surface_fingerprint()
    if low_surface_after != low_surface_before:
        raise RuntimeError('Normal bake changed low body shape positions or the shared UV atlas')
    stats['low_surface_preservation'] = {'method': 'SHA-256 of ordered float32 shape positions and shared UV loops',
                                         'before_sha256': low_surface_before,
                                         'after_sha256': low_surface_after}
    return stats


def setup_preview_relief(obj):
    """Offline parity with TSL, installed only after the standard GLB export.

    Mixing RGB directly makes normals too short. Decode and normalize each
    direction, blend the three maps, renormalize, then use the same regional
    strength as the runtime. The private Blender project may use this graph;
    the shipped GLB keeps its simple standard neutral normalTexture.
    """
    global PREVIEW_RELIEF
    material = obj.data.materials[0]; nodes = material.node_tree.nodes
    texture = nodes['Baked anatomical relief']
    normal = next(node for node in nodes if node.type == 'NORMAL_MAP')
    maps = []
    for label in ('soft', 'neutral', 'defined'):
        image = bpy.data.images.get('relief-' + label)
        if image is None: image = bpy.data.images.load(str((args.output / f'relief-{label}.png').resolve()))
        image.colorspace_settings.name = 'Non-Color'; image.use_fake_user = True
        pixels = np.empty(len(image.pixels), dtype=np.float32); image.pixels.foreach_get(pixels)
        directions = pixels.reshape(-1, 4)[:, :3] * 2. - 1.
        directions /= np.maximum(np.linalg.norm(directions, axis=1, keepdims=True), .000001)
        maps.append(directions)
    blend = bpy.data.images.new('Review direction-blended relief', width=2048, height=2048,
                                alpha=False, float_buffer=True)
    blend.colorspace_settings.name = 'Non-Color'; texture.image = blend
    mask = bpy.data.images.get('relief-regions')
    if mask is None: mask = bpy.data.images.load(str((args.output / 'relief-regions.png').resolve()))
    mask.colorspace_settings.name = 'Non-Color'
    mask_texture = nodes.new('ShaderNodeTexImage'); mask_texture.name = 'Review relief region mask'; mask_texture.image = mask
    split = nodes.new('ShaderNodeSeparateColor'); split.mode = 'RGB'
    material.node_tree.links.new(mask_texture.outputs['Color'], split.inputs['Color'])

    def math_node(operation, left, right):
        node = nodes.new('ShaderNodeMath'); node.operation = operation
        for index, value in enumerate((left, right)):
            if isinstance(value, (int, float)): node.inputs[index].default_value = value
            else: material.node_tree.links.new(value, node.inputs[index])
        return node.outputs[0]

    region = math_node('ADD', split.outputs[0], math_node('MULTIPLY', split.outputs[1], .85))
    region = math_node('ADD', region, math_node('MULTIPLY', split.outputs[2], .8))
    region = math_node('MINIMUM', math_node('MAXIMUM', region, 0.), 1.)
    strength = nodes.new('ShaderNodeValue'); strength.name = 'Review composition relief strength'
    output = math_node('MULTIPLY', region, strength.outputs[0])
    material.node_tree.links.new(output, normal.inputs['Strength'])
    PREVIEW_RELIEF = {'maps': maps, 'image': blend, 'strength': strength, 'last': None}


def standard_export_relief(obj):
    """Keep review-only nodes out of the active standard glTF normal graph."""
    nodes = obj.data.materials[0].node_tree.nodes
    texture = nodes.get('Baked anatomical relief')
    if texture is None: return
    neutral = bpy.data.images.get('relief-neutral')
    if neutral is None: neutral = bpy.data.images.load(str((args.output / 'relief-neutral.png').resolve()))
    texture.image = neutral
    normal = next(node for node in nodes if node.type == 'NORMAL_MAP')
    for link in list(normal.inputs['Strength'].links): obj.data.materials[0].node_tree.links.remove(link)
    normal.inputs['Strength'].default_value = BODY_NORMAL_STRENGTH


def update_preview_relief(fat, muscle):
    if PREVIEW_RELIEF is None: return
    PREVIEW_RELIEF['strength'].outputs[0].default_value = BODY_NORMAL_STRENGTH
    weights = np.zeros(3, dtype=np.float32)
    for name, amount in grid_weights(fat, muscle).items():
        point = CONTROL_POINTS[name]
        weights += amount * np.asarray(relief_weights(point['fat'], point['muscle_axis']), dtype=np.float32)
    blend_key = tuple(float(value) for value in weights)
    if PREVIEW_RELIEF['last'] == blend_key: return
    directions = sum(amount * values for amount, values in zip(weights, PREVIEW_RELIEF['maps']))
    directions /= np.maximum(np.linalg.norm(directions, axis=1, keepdims=True), .000001)
    pixels = np.ones((len(directions), 4), dtype=np.float32)
    pixels[:, :3] = directions * .5 + .5
    PREVIEW_RELIEF['image'].pixels.foreach_set(pixels.ravel())
    PREVIEW_RELIEF['image'].update()
    PREVIEW_RELIEF['last'] = blend_key


def aim(obj, at):
    obj.rotation_euler = (Vector(at)-obj.location).to_track_quat('-Z','Y').to_euler()


def studio(height):
    scene = bpy.context.scene
    scene.render.engine = 'CYCLES'
    scene.cycles.samples = args.review_samples
    scene.cycles.use_denoising = True
    scene.render.resolution_x = args.review_size; scene.render.resolution_y = args.review_size
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = True
    scene.view_settings.view_transform = 'AgX'
    scene.world.color = (.04,.04,.04)
    bpy.ops.object.camera_add(location=(.3,-5,height*.53))
    camera = bpy.context.object
    camera.data.type = 'ORTHO'; camera.data.ortho_scale = height*1.17
    aim(camera,(0,0,height*.50)); scene.camera=camera
    for name,location,power,color,size in [
        ('Key',(-2.4,-2,3.2),330,(1,.94,.84),1.1),
        ('Fill',(2,-2,2.2),30,(.7,.83,1),3),
        ('Rim',(2,1,2.9),450,(.65,.8,1),1.8),
    ]:
        bpy.ops.object.light_add(type='AREA',location=location)
        lamp=bpy.context.object; lamp.name=name; lamp.data.energy=power
        lamp.data.color=color; lamp.data.size=size; aim(lamp,(0,0,height*.6))
    return camera


def select(obj):
    bpy.ops.object.select_all(action='DESELECT')
    obj.select_set(True); bpy.context.view_layer.objects.active=obj


def grid_weights(fat,muscle):
    fat=max(0.,min(1.,fat)); muscle=max(0.,min(1.,muscle))
    fi=min(len(FAT_LEVELS)-2,int(fat*(len(FAT_LEVELS)-1)))
    mi=min(len(MUSCLE_LEVELS)-2,int(muscle*(len(MUSCLE_LEVELS)-1)))
    ft=(fat-FAT_LEVELS[fi])/(FAT_LEVELS[fi+1]-FAT_LEVELS[fi])
    mt=(muscle-MUSCLE_LEVELS[mi])/(MUSCLE_LEVELS[mi+1]-MUSCLE_LEVELS[mi])
    return {f'f{f}_m{m}':fw*mw for f,fw in [(fi,1-ft),(fi+1,ft)] for m,mw in [(mi,1-mt),(mi+1,mt)]}


def set_physique(obj,preset):
    fat,muscle=PRESETS[preset] if isinstance(preset,str) else preset
    weights=grid_weights(fat,muscle)
    for target in [obj, *[item for item in bpy.data.objects if item.name == 'SpartanWrap']]:
        for key in target.data.shape_keys.key_blocks[1:]:
            key.value=weights.get(key.name.removeprefix('shape_'),0.)
    normal=next((node for node in obj.data.materials[0].node_tree.nodes if node.type=='NORMAL_MAP'),None)
    if normal:
        normal.inputs['Strength'].default_value=BODY_NORMAL_STRENGTH
    update_preview_relief(fat,muscle)
    bpy.context.view_layer.update()


def set_pose_corrections(obj, rig):
    """Art review and fitted-cloth checks use the client's bone-local twist."""
    for descriptor in POSE_CORRECTIVES:
        key = obj.data.shape_keys.key_blocks.get(descriptor['name'])
        if not key: continue
        quaternion = rig.pose.bones[descriptor['bone']].rotation_quaternion
        axis = Vector(descriptor['axis'])
        angle = 2 * math.atan2(Vector((quaternion.x, quaternion.y, quaternion.z)).dot(axis), quaternion.w)
        angle = (angle + math.pi) % (2 * math.pi) - math.pi
        t = max(0., min(1., (angle * descriptor['sign'] - descriptor['start_radians']) /
                            (descriptor['end_radians'] - descriptor['start_radians'])))
        key.value = t*t*(3-2*t)


def render(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    set_pose_corrections(obj, rig)
    bpy.context.view_layer.update()
    bpy.context.scene.render.image_settings.file_format='PNG'
    bpy.context.scene.render.image_settings.color_mode='RGBA'
    bpy.context.scene.render.filepath=str(path.resolve())
    bpy.ops.render.render(write_still=True)


if args.animations_only or args.motion_preview_only:
    bpy.ops.wm.open_mainfile(filepath=str((args.review/'character.blend').resolve()))
    obj=bpy.data.objects['InBodyCharacter']; rig=bpy.data.objects['InBodySkeleton']
    appearance=[item for item in bpy.data.objects if item.name.startswith('Spartan') and item.type=='MESH']
    mesh=obj.data; camera=bpy.context.scene.camera
    height=max(v.co.z for v in mesh.vertices)
    manifest=json.loads((args.output/'manifest.json').read_text())
    dense_morph_bytes=manifest['compile']['source_dense_morph_bytes']
    if args.motion_preview_only:
        states=author_poses(rig,pose)
        set_physique(obj,'athletic')
        bpy.context.scene.render.resolution_x=args.review_size; bpy.context.scene.render.resolution_y=args.review_size
        bpy.context.scene.cycles.samples=args.review_samples
        camera.location=(.3,-5,height*.53); aim(camera,(0,0,height*.5))
        for clip,time in [('scenario_calm',3.1),('scenario_athletic',4.6),('scenario_athletic',10.),('scenario_stage',11.3)]:
            sample_motion(rig,clip,time,states)
            render(args.review/f'{clip}-{time:.1f}-preview.png')
        set_physique(obj,(1.,1.)); sample_motion(rig,'scenario_stage',11.3,states)
        render(args.review/'scenario-stage-large-body.png')
        raise SystemExit(0)
    motion_metadata=animation(rig)
else:
    bpy.ops.object.select_all(action='SELECT'); bpy.ops.object.delete(use_global=False)
    grid={}; rig=None
    for name in [NEUTRAL]+[name for name in CONTROL_POINTS if name!=NEUTRAL]:
        coords,faces,uvs,weights,created_rig=surface(name)
        grid[name]=coords
        if created_rig: rig=created_rig; neutral_faces=faces; neutral_uvs=uvs; neutral_weights=weights
        if faces != neutral_faces: raise RuntimeError('Different physique topology')
    grid=independent_shapes(grid,neutral_faces)
    stage_mesh = bpy.data.meshes.new('independent-fat-stage')
    stage_mesh.from_pydata(grid[NEUTRAL], [], neutral_faces); stage_mesh.calc_loop_triangles()
    stage_triangles = [tuple(face.vertices) for face in stage_mesh.loop_triangles]
    stage_height = max(point[2] for point in grid[NEUTRAL])
    for name in sorted(FAT_VOLUME_RECEIPT_SHAPES):
        FAT_VOLUME_STAGE_RECEIPTS[name] = body_sections(grid[name], grid[NEUTRAL], stage_triangles, stage_height)
    bpy.data.meshes.remove(stage_mesh)
    grid,faces,uvs,weights=sculpture(grid,neutral_faces,neutral_uvs,neutral_weights)
    mesh=bpy.data.meshes.new('InBody prototype mesh')
    mesh.from_pydata(grid[NEUTRAL],[],faces); mesh.update()
    obj=bpy.data.objects.new('InBodyCharacter',mesh); bpy.context.collection.objects.link(obj)
    for polygon in mesh.polygons: polygon.use_smooth=True
    select(obj)
    # Assemble the source UVs, then repack body and new head into one non-overlapping bake atlas.
    uv=mesh.uv_layers.new(name='MPFB source UV')
    for polygon,values in zip(mesh.polygons,uvs):
        for loop,value in zip(polygon.loop_indices,values): uv.data[loop].uv=value
    uv=mesh.uv_layers.new(name='Anatomy bake UV'); mesh.uv_layers.active=uv
    bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.uv.smart_project(angle_limit=math.radians(72),island_margin=.015)
    bpy.ops.object.mode_set(mode='OBJECT')
    mesh.uv_layers.remove(mesh.uv_layers['MPFB source UV'])
    obj.data.materials.append(material())
    obj.shape_key_add(name='Basis')
    for name in CONTROL_POINTS:
        coords=grid[name]
        key=obj.shape_key_add(name=f'shape_{name}'); key.value=float(name==NEUTRAL)
        for vertex,coord in zip(key.data,coords): vertex.co=coord
    weights=smooth_shoulder_weights(mesh,weights,max(v[2] for v in grid[NEUTRAL]))
    used=set(); render_skin_rows=[]
    for index,row in enumerate(weights):
        values=sorted(row.items(),key=lambda item:item[1],reverse=True)[:4]
        total=sum(v for _,v in values)
        if not total: values=[('root',1.)]; total=1.
        render_skin_rows.append({name: value/total for name,value in values})
        for name,value in values:
            used.add(name)
            group=obj.vertex_groups.get(name) or obj.vertex_groups.new(name=name)
            group.add([index],value/total,'REPLACE')
    grid, fat_volume_receipt = refine_fat_volume(grid, mesh, render_skin_rows)
    # Preserve the complete bounded stage receipt even if later validation fails;
    # CI consoles truncate the large completion line before these measurements.
    (args.review / 'fat-volume-authoring.json').write_text(json.dumps(fat_volume_receipt, indent=2, allow_nan=False) + '\n')
    for name, coords in grid.items():
        if CONTROL_POINTS[name]['fat'] > .4:
            for vertex, coord in zip(obj.data.shape_keys.key_blocks[f'shape_{name}'].data, coords):
                vertex.co = coord
    # Discard eye, mouth, and other unused facial bones while retaining required ancestors.
    keep=set(used)
    for name in list(used):
        for parent in rig.data.bones[name].parent_recursive: keep.add(parent.name)
    select(rig); bpy.ops.object.mode_set(mode='EDIT')
    for bone in list(rig.data.edit_bones):
        if bone.name not in keep: rig.data.edit_bones.remove(bone)
    bpy.ops.object.mode_set(mode='OBJECT')
    rig.name='InBodySkeleton'; obj.parent=rig
    rig['body_template'] = args.template
    modifier=obj.modifiers.new('Skin','ARMATURE'); modifier.object=rig
    rig['body_resource'] = 'male_spartan' if args.character == 'spartan' else args.template
    corrective_shapes(obj, rig, max(v[2] for v in grid[NEUTRAL]))
    motion_metadata=animation(rig)
    height=max(v[2] for v in grid[NEUTRAL])
    appearance, garment_fit = spartan_appearance(obj,rig,grid,height) if args.character == 'spartan' else ([], None)
    bake=bake_normals(obj,rig,height) if not args.quick else False
    camera=studio(height)
    mesh.calc_loop_triangles()
    triangles=len(mesh.loop_triangles)
    for item in appearance:
        item.data.calc_loop_triangles(); triangles+=len(item.data.loop_triangles)
    if triangles>30000:
        raise RuntimeError('Prototype exceeds the triangle budget')
    dense_morph_bytes=len(mesh.vertices)*(len(CONTROL_POINTS)+len(POSE_CORRECTIVES))*3*4*2
    dense_morph_bytes+=sum(len(item.data.vertices)*len(CONTROL_POINTS)*3*4*2 for item in appearance if item.data.shape_keys)
    if dense_morph_bytes>16*1024*1024:
        raise RuntimeError('Prototype exceeds the CPU morph-buffer budget')
    manifest={'version':'inbody-character-v3','upstream':'https://github.com/makehumancommunity/mpfb2',
              'revision':REVISION,'license':'CC0-1.0','blender':bpy.app.version_string,
              'grid':{'fat':FAT_LEVELS,'muscle':MUSCLE_LEVELS,'default':NEUTRAL,'interpolation':'bilinear','construction':'fixed fat surface plus cumulative outward muscle growth'},
              'presets':PRESETS,'control_points':CONTROL_POINTS,'anatomy_targets':ANATOMY,'triangles':triangles,
              'vertices':len(mesh.vertices),'bones':len(rig.data.bones),'clips':['relaxed','flex','open'],
              'normal_bake':bake,'normal_strength':{name:BODY_NORMAL_STRENGTH for name in PRESETS},
              'normal_strength_grid':{name:BODY_NORMAL_STRENGTH for name in CONTROL_POINTS},
              'pose_correctives':POSE_CORRECTIVES,'production':False}
    manifest['fat_volume'] = fat_volume_receipt
    if garment_fit is not None:
        manifest['garment_fit'] = garment_fit
manifest['template']=args.template
manifest['character_id']=args.character
if SCULPTURE_HEAD_LANDMARKS is not None:
    manifest['head_landmarks']=SCULPTURE_HEAD_LANDMARKS
manifest['clips']=['relaxed','flex','open',*SCENARIOS]
manifest['authored_motion']=motion_metadata
set_physique(obj,'neutral'); pose(rig,'relaxed')
standard_export_relief(obj)
# Export held poses and original authored scenarios. Shape keys have no animation data.
select(obj); rig.select_set(True)
for item in appearance: item.select_set(True)
path=args.output/'character.glb'
bpy.ops.export_scene.gltf(filepath=str(path.resolve()),export_format='GLB',use_selection=True,
    export_texcoords=True,export_normals=True,export_materials='EXPORT',export_morph=True,
    export_morph_normal=True,export_animations=True,export_animation_mode='NLA_TRACKS',
    export_skins=True,export_yup=True,export_cameras=False,export_lights=False,
    export_force_sampling=True,export_frame_range=False)
surfaces=[{'node':'InBodyCharacter','role':'skin','material':obj.data.materials[0].name}]
surfaces.extend({'node':item.name,'role':'cloth','material':item.data.materials[0].name} for item in appearance)
if not args.quick:
    manifest['body_asset']=embed_contract(path,args.output,surfaces)
if args.head_baseline is not None:
    manifest['production_rig_retention']=retain_production_rest(path,args.head_baseline,args.template)
    manifest['production_head_retention']=retain_production_head(path,args.head_baseline,args.template)
    manifest['head_baseline']=validate_head(path,args.head_baseline,args.template)
manifest['sha256']=hashlib.sha256(path.read_bytes()).hexdigest(); manifest['bytes']=path.stat().st_size
if 'fat_volume' in manifest:
    manifest['fat_volume']['exported_glb_sha256'] = manifest['sha256']
    (args.review / 'fat-volume-authoring.json').write_text(json.dumps(manifest['fat_volume'], indent=2, allow_nan=False) + '\n')
if manifest['bytes']>20*1024*1024:
    raise RuntimeError('Prototype exceeds the uncompressed GLB transfer budget')
manifest['compile']={'mode':'animation-only' if args.animations_only else 'full-geometry-and-animation','seconds_before_review':round(time.monotonic()-build_started,2),
    'peak_rss_bytes':int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*(1 if platform.system()=='Darwin' else 1024)),
    'source_dense_morph_bytes':dense_morph_bytes,'transfer_budget_bytes':20*1024*1024,
    'source_dense_morph_budget_bytes':16*1024*1024}
if not args.animations_only and not args.skip_review:
    manifest['geometry_compile']=dict(manifest['compile'])
manifest['authored_motion']['source']='tools/assets/inbody_character_motion.py'
manifest['authored_motion']['source_sha256']=hashlib.sha256((ROOT/'tools/assets/inbody_character_motion.py').read_bytes()).hexdigest()
(args.output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
for source_name,out_name in [('LICENSE.md','LICENSE.MPFB.md'),('LICENSE.ASSETS.md','LICENSE.CC0.md')]:
    license_path=source/source_name
    if license_path.exists(): shutil.copyfile(license_path,args.output/out_name)
if not args.quick:
    setup_preview_relief(obj)
    set_physique(obj,'neutral')
bpy.ops.wm.save_as_mainfile(filepath=str((args.review/'character.blend').resolve()))
if args.quick and not args.animations_only and not args.skip_review:
    for body,stance in [('neutral','relaxed'),('athletic','relaxed'),('athletic','flex')]:
        set_physique(obj,body); pose(rig,stance)
        render(args.review/f'{body}-{stance}.png')
if args.animations_only and not args.skip_review:
    states=author_poses(rig,pose)
    set_physique(obj,'athletic')
    bpy.context.scene.render.resolution_x=700; bpy.context.scene.render.resolution_y=700
    bpy.context.scene.cycles.samples=24
    camera.location=(.3,-5,height*.53); aim(camera,(0,0,height*.5))
    for clip,time in [('scenario_calm',3.1),('scenario_athletic',1.1),('scenario_athletic',4.6),('scenario_athletic',10.),('scenario_stage',8.7),('scenario_stage',11.3),('scenario_stage',16.)]:
        sample_motion(rig,clip,time,states)
        render(args.review/f'{clip}-{time:.1f}.png')
    set_physique(obj,(1.,1.)); sample_motion(rig,'scenario_stage',11.3,states)
    render(args.review/'scenario-stage-large-body.png')
set_physique(obj,'neutral'); pose(rig,'relaxed')
if not args.quick and not args.skip_review:
    for preset in PRESETS:
        for angle,label in [(0.,'front'),(math.pi/2,'side'),(math.pi,'back'),(math.pi/4,'three-quarter')]:
            set_physique(obj,preset); pose(rig,'relaxed')
            camera.location=(5*math.sin(angle),-5*math.cos(angle),height*.53)
            aim(camera,(0,0,height*.5)); render(args.review/'art'/f'{preset}-{label}.png')
    if args.character=='spartan':
        set_physique(obj,'neutral'); pose(rig,'relaxed')
        landmarks=manifest['head_landmarks']
        head_center=Vector((0,landmarks['head_center_y'],landmarks['neck_z']+landmarks['head_height']*.58))
        original_scale=camera.data.ortho_scale
        camera.data.ortho_scale=landmarks['head_height']*1.38
        bpy.context.scene.render.resolution_x=900; bpy.context.scene.render.resolution_y=900
        for angle,label in [(0.,'front'),(math.pi/4,'three-quarter'),(math.pi/2,'side')]:
            camera.location=head_center+Vector((2*math.sin(angle),-2*math.cos(angle),0))
            aim(camera,head_center); render(args.review/'art'/f'head-{label}.png')
        camera.data.ortho_scale=original_scale
        bpy.context.scene.render.resolution_x=args.review_size; bpy.context.scene.render.resolution_y=args.review_size
    states=author_poses(rig,pose)
    for preset in ('athletic','mixed'):
        set_physique(obj,preset)
        for clip,time in [('scenario_calm',3.1),('scenario_athletic',4.6),('scenario_athletic',10.),('scenario_stage',11.3)]:
            sample_motion(rig,clip,time,states)
            camera.location=(2.6,-4.4,height*.53); aim(camera,(0,0,height*.5))
            render(args.review/'motion'/f'{preset}-{clip}-{time:.1f}.png')
set_physique(obj,'neutral'); pose(rig,'relaxed')
camera.location=(.3,-5,height*.53); aim(camera,(0,0,height*.5))
bpy.context.scene.render.resolution_x=700; bpy.context.scene.render.resolution_y=900
render(args.output/'poster.png')
if PREVIEW_RELIEF is not None: PREVIEW_RELIEF['image'].pack()
bpy.ops.wm.save_as_mainfile(filepath=str((args.review/'character.blend').resolve()))
print('CHARACTER_PROTOTYPE_COMPLETE',json.dumps(manifest))
