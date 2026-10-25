"""Validate the generated rigged character prototype without Blender or a GPU.

This checks the artist-export contract, not the quality of anatomy or deformation.
Run after export: python tools/assets/inbody_character_validate.py
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct

if __package__:
    from .inbody_asset_contract import BODY_MORPHS, POSE_CORRECTIVES, VERSION
    from .inbody_fat_volume import RECIPES as FAT_VOLUME_RECIPES, REVIEW_SAMPLES as FAT_VOLUME_SAMPLES, VERSION as FAT_VOLUME_VERSION, FatSectionError, body_sections, check_fat_volume_sections, fat_volume_blend, fat_volume_field, sample_body_grid, sampled_fat_volume_blend
else:
    from inbody_asset_contract import BODY_MORPHS, POSE_CORRECTIVES, VERSION
    from inbody_fat_volume import RECIPES as FAT_VOLUME_RECIPES, REVIEW_SAMPLES as FAT_VOLUME_SAMPLES, VERSION as FAT_VOLUME_VERSION, FatSectionError, body_sections, check_fat_volume_sections, fat_volume_blend, fat_volume_field, sample_body_grid, sampled_fat_volume_blend

ROOT = Path(__file__).resolve().parents[2]
COMPONENTS = {5120: 'b', 5121: 'B', 5122: 'h', 5123: 'H', 5125: 'I', 5126: 'f'}
WIDTHS = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}


def write_fat_section_failure(path, manifest, error, reference, skin_rows, triangles,
                              actual_coords, recovered_coords, authoring_before, authoring_after):
    """Preserve bounded derived geometry diagnostics before rethrowing the guard."""
    receipt = manifest['fat_volume']
    traces = {}
    for stage, coords in (('actual', actual_coords), ('recovered', recovered_coords)):
        trace = {}
        body_sections(coords, reference, triangles, receipt['height_m'],
                      diagnostics=trace, diagnostic_section=error.section)
        for triangle in trace.get('triangles', []):
            triangle['vertex_skin_weights'] = [skin_rows[index] for index in triangle['vertices']]
        traces[stage] = trace
    result = {'version': 'body-fat-section-failure-v1',
        'template': manifest['template'], 'character_id': manifest['character_id'],
        'model_sha256': manifest['sha256'], 'source_helper_sha256': receipt['source_helper_sha256'],
        'authoring_receipt_sha256': hashlib.sha256(json.dumps(receipt, sort_keys=True, separators=(',', ':')).encode()).hexdigest(),
        'failure': {'message': str(error), 'case_id': error.case_id, 'section': error.section, 'stage': error.stage},
        'body_frame': receipt['body_frame'], 'geometry_state': 'unskinned neutral-relative body morphs; no pose applied',
        'height_m': receipt['height_m'], 'body_vertex_count_with_uv_seams': len(reference),
        'body_triangle_count': len(triangles), 'authoring_before': authoring_before,
        'authoring_after': authoring_after, 'decoded_contour_diagnostics': traces,
        'interpretation': 'An incomplete contour can arise from intersection, weld or geometric degeneracy; it alone does not prove a mesh hole.'}
    path.with_name('fat-section-failure.json').write_text(json.dumps(result, indent=2, allow_nan=False) + '\n')
    return result


def validate(path, baseline=None, require_scenarios=True):
    raw = path.read_bytes()
    magic, version, length = struct.unpack_from('<4sII', raw)
    assert magic == b'glTF' and version == 2 and length == len(raw), 'Invalid GLB header'
    size, kind = struct.unpack_from('<II', raw, 12)
    assert kind == 0x4E4F534A
    doc = json.loads(raw[20:20 + size])
    offset = 20 + size
    binary_size, kind = struct.unpack_from('<II', raw, offset)
    assert kind == 0x004E4942
    binary = raw[offset + 8:offset + 8 + binary_size]
    assert len(doc['buffers']) == 1 and 'uri' not in doc['buffers'][0]
    assert len(doc['skins']) == 1
    contract=doc['scenes'][doc.get('scene',0)]['extras']['bodyAsset']
    assert contract['version']==VERSION
    assert contract['body_morph_names']==BODY_MORPHS
    assert contract['body_surface_nodes']==['InBodyCharacter']
    assert contract['pose_correctives']==POSE_CORRECTIVES
    surfaces=contract['surfaces']
    assert len({entry['node'] for entry in surfaces})==len(surfaces)
    nodes={node['name']:node for node in doc['nodes'] if 'mesh' in node}
    assert set(nodes) <= {'InBodyCharacter', 'SpartanWrap'}, 'Facial overlays are forbidden on the production mannequin'
    assert set(nodes)=={entry['node'] for entry in surfaces}, 'Every visible surface needs an explicit role'
    assert all(entry['role'] in {'skin','hair','cloth','accessory'} for entry in surfaces)
    assert all(doc['materials'][primitive['material']]['name']==entry['material']
               for entry in surfaces for primitive in doc['meshes'][nodes[entry['node']]['mesh']]['primitives'])

    def accessor(index):
        entry = doc['accessors'][index]
        fmt = '<' + COMPONENTS[entry['componentType']] * WIDTHS[entry['type']]
        if 'bufferView' in entry:
            view = doc['bufferViews'][entry['bufferView']]
            stride = view.get('byteStride', struct.calcsize(fmt))
            start = view.get('byteOffset', 0) + entry.get('byteOffset', 0)
            values = [struct.unpack_from(fmt, binary, start + i * stride) for i in range(entry['count'])]
        else:
            values = [(0,) * WIDTHS[entry['type']] for _ in range(entry['count'])]
        if 'sparse' in entry:
            sparse = entry['sparse']
            indices = sparse['indices']; replacements = sparse['values']
            iv = doc['bufferViews'][indices['bufferView']]
            vv = doc['bufferViews'][replacements['bufferView']]
            index_fmt = '<' + COMPONENTS[indices['componentType']]
            index_start = iv.get('byteOffset', 0) + indices.get('byteOffset', 0)
            value_start = vv.get('byteOffset', 0) + replacements.get('byteOffset', 0)
            for i in range(sparse['count']):
                target = struct.unpack_from(index_fmt, binary, index_start + i * struct.calcsize(index_fmt))[0]
                values[target] = struct.unpack_from(fmt, binary, value_start + i * struct.calcsize(fmt))
        assert all(math.isfinite(v) for row in values for v in row), 'Nonfinite accessor'
        return values

    mesh = doc['meshes'][nodes['InBodyCharacter']['mesh']]
    names = BODY_MORPHS
    correction_names=[entry['name'] for entry in POSE_CORRECTIVES]
    assert mesh['extras']['targetNames'] == names+correction_names
    assert mesh['weights'] == [float(i==12) for i in range(34)]
    joints = doc['skins'][0]['joints']
    assert len(accessor(doc['skins'][0]['inverseBindMatrices'])) == len(joints)
    geometry_hash=hashlib.sha256()
    def fingerprint(label, rows):
        geometry_hash.update(label.encode())
        for row in rows:
            geometry_hash.update(struct.pack('<'+'f'*len(row),*row))
    fingerprint('inverse-bind',accessor(doc['skins'][0]['inverseBindMatrices']))
    geometry_hash.update(json.dumps([doc['nodes'][index]['name'] for index in joints]).encode())
    triangles = vertices = 0
    volumes=[0.]*30; waist_depth=[0.]*30; position_hashes=[]; fat_inputs=[]
    for primitive in mesh['primitives']:
        attrs = primitive['attributes']
        fingerprint('indices',accessor(primitive['indices']))
        for name,index in sorted(attrs.items()):
            fingerprint(name,accessor(index))
        for i,target in enumerate(primitive['targets']):
            for name,index in sorted(target.items()):
                fingerprint(f'morph-{i}-{name}',accessor(index))
        assert {'POSITION', 'NORMAL', 'TEXCOORD_0', 'JOINTS_0', 'WEIGHTS_0'} <= attrs.keys()
        positions = accessor(attrs['POSITION'])
        vertices += len(positions)
        indices=[row[0] for row in accessor(primitive['indices'])]
        triangles += len(indices) // 3
        assert len(primitive['targets']) == 34
        height=max(row[1] for row in positions)
        waist=[i for i,row in enumerate(positions) if .585*height<row[1]<.625*height and abs(row[0])<.09*height]
        effective_shapes = []
        for target_index,target in enumerate(primitive['targets'][:30]):
            assert {'POSITION', 'NORMAL'} <= target.keys()
            deltas=accessor(target['POSITION'])
            assert len(deltas) == len(positions)
            assert len(accessor(target['NORMAL'])) == len(positions)
            effective=[tuple(p+d for p,d in zip(base,delta)) for base,delta in zip(positions,deltas)]
            effective_shapes.append(effective)
            position_hashes.append(hashlib.sha256(b''.join(struct.pack('<3f',*row) for row in effective)).hexdigest())
            signed_volume=0.
            for start in range(0,len(indices),3):
                a,b,c=[effective[i] for i in indices[start:start+3]]
                signed_volume+=a[0]*(b[1]*c[2]-b[2]*c[1])+a[1]*(b[2]*c[0]-b[0]*c[2])+a[2]*(b[0]*c[1]-b[1]*c[0])
            volumes[target_index]+=abs(signed_volume)/6
            waist_depth[target_index]=max(effective[i][2] for i in waist)-min(effective[i][2] for i in waist)
            assert min(row[1] for row in effective)>-.01, 'Morph moves feet below the common ground'

        fat_inputs.append((positions, indices, effective_shapes,
                           accessor(attrs['JOINTS_0']), accessor(attrs['WEIGHTS_0'])))

        assert all(abs(sum(row) - 1) < .0001 for row in accessor(attrs['WEIGHTS_0']))
        assert all(0 <= j < len(joints) for row in accessor(attrs['JOINTS_0']) for j in row)
        assert len(accessor(attrs['TEXCOORD_0'])) == len(positions)
        for target in primitive['targets'][30:]:
            assert {'POSITION','NORMAL'} <= target.keys()
            deltas=accessor(target['POSITION'])
            assert len(deltas)==len(positions)
            assert any(abs(v)>.000001 for row in deltas for v in row), 'Empty joint correction'
            assert max(abs(v) for row in deltas for v in row)<.025, 'Joint correction exceeds the local surface budget'
            assert len(accessor(target['NORMAL']))==len(positions)
    body_vertices=vertices
    for entry in surfaces:
        if entry['node']=='InBodyCharacter': continue
        appearance=doc['meshes'][nodes[entry['node']]['mesh']]
        target_names=appearance.get('extras',{}).get('targetNames',[])
        if entry['role']=='cloth':
            assert target_names==BODY_MORPHS, 'Clothing must follow all 30 body shapes'
            assert appearance['weights']==[float(i==12) for i in range(30)]
        else:
            assert not target_names, 'Head overlays must not introduce facial morph animation'
        for primitive in appearance['primitives']:
            attrs=primitive['attributes']
            assert {'POSITION','NORMAL','JOINTS_0','WEIGHTS_0'} <= attrs.keys()
            positions=accessor(attrs['POSITION']); vertices+=len(positions)
            indices=accessor(primitive['indices']); triangles+=len(indices)//3
            assert all(0<=row[0]<len(positions) for row in indices)
            assert all(abs(sum(row)-1)<.0001 for row in accessor(attrs['WEIGHTS_0']))
            assert all(0<=j<len(joints) for row in accessor(attrs['JOINTS_0']) for j in row)
            for target in primitive.get('targets',[]):
                assert len(accessor(target['POSITION']))==len(positions)
                for name,index in sorted(target.items()): fingerprint(entry['node']+'-morph-'+name,accessor(index))
            fingerprint(entry['node']+'-indices',indices)
            for name,index in sorted(attrs.items()): fingerprint(entry['node']+'-'+name,accessor(index))
    assert triangles <= 30000
    assert len(set(position_hashes))==30, 'Expected 30 distinct authored shapes'
    assert all(value>0 for value in volumes)
    volume_decreases=[]; waist_decreases=[]; muscle_waist_decreases=[]
    for fat in range(6):
        for muscle in range(4):
            a=fat*5+muscle; b=a+1
            if volumes[b]<volumes[a]-.000001:
                volume_decreases.append([fat,muscle,muscle+1])
            if waist_depth[b]<waist_depth[a]-.0001:
                muscle_waist_decreases.append([fat,muscle,muscle+1])
    for muscle in range(5):
        for fat in range(5):
            a=fat*5+muscle; b=a+5
            if waist_depth[b]<waist_depth[a]-.0001:
                waist_decreases.append([muscle,fat,fat+1])
    assert not volume_decreases, 'Muscle axis unexpectedly removes mesh volume'
    assert not waist_decreases, 'Fat axis unexpectedly shrinks waist depth'
    assert not muscle_waist_decreases, 'Muscle axis unexpectedly removes the fat abdomen'
    held_names={'relaxed','flex','open'}
    scenario_durations={'scenario_calm':10.,'scenario_athletic':14.,'scenario_stage':18.}
    expected_names=held_names | (set(scenario_durations) if require_scenarios else set())
    assert {clip['name'] for clip in doc['animations']} == expected_names
    relaxed=next(clip for clip in doc['animations'] if clip['name']=='relaxed')
    relaxed_values={}
    def difference(a,b,path):
        direct=max(abs(x-y) for x,y in zip(a,b))
        if path=='rotation':
            return min(direct,max(abs(x+y) for x,y in zip(a,b)))
        return direct
    for channel in relaxed['channels']:
        target=channel['target']; sampler=relaxed['samplers'][channel['sampler']]
        relaxed_values[(target['node'],target['path'])]=accessor(sampler['output'])[0]
    durations={}; scenario_checks={}; held_fingerprint=hashlib.sha256()
    for clip in doc['animations']:
        assert len(clip['channels']) == len(joints)*3, 'Every clip must fully specify the skeleton'
        duration=0.; moving_bones=set(); max_endpoint_error=0.; frame_times=set()
        targets=set()
        for channel in clip['channels']:
            target=channel['target']; path_name=target['path']; node=target['node']
            assert path_name in {'translation','rotation','scale'}, 'Clip overwrites physique'
            assert node in joints, 'Clip animates a nonbone node'
            assert (node,path_name) not in targets, 'Duplicate channel'
            targets.add((node,path_name))
            sampler=clip['samplers'][channel['sampler']]
            times=accessor(sampler['input']); values=accessor(sampler['output'])
            assert len(times)==len(values)
            assert sampler.get('interpolation','LINEAR') in {'LINEAR','STEP'}
            assert abs(times[0][0])<.00001, 'Clip must begin at zero'
            assert all(b[0]>a[0] for a,b in zip(times,times[1:])), 'Nonmonotonic key times'
            duration=max(duration,times[-1][0])
            frame_times.update(round(t[0],5) for t in times)
            variation=max(difference(row,values[0],path_name) for row in values)
            if variation>.00001:
                assert sampler.get('interpolation','LINEAR')=='LINEAR', 'Moving track needs interpolated sampling'
            if clip['name'] in held_names:
                assert variation<.00001, 'Held pose changed over time'
                held_fingerprint.update(clip['name'].encode()+doc['nodes'][node]['name'].encode()+path_name.encode())
                held_fingerprint.update(struct.pack('<'+'f'*len(values[0]),*values[0]))
            else:
                reference=relaxed_values[(node,path_name)]
                error=max(difference(values[0],reference,path_name),difference(values[-1],reference,path_name))
                max_endpoint_error=max(error,max_endpoint_error)
                assert error<.00002, 'Scenario must enter and exit the exact relaxed pose'
                if variation>.0001: moving_bones.add(doc['nodes'][node]['name'])
                bone_name=doc['nodes'][node]['name']
                if bone_name=='root' or bone_name.startswith(('pelvis','upperleg','lowerleg','foot','toe')):
                    assert variation<.00001, 'Planted foot or leg moves'
                if path_name=='scale':
                    assert variation<.00001, 'Scenario introduces animated bone scaling'
        expected_duration=1. if clip['name'] in held_names else scenario_durations[clip['name']]
        assert abs(duration-expected_duration)<.001, 'Wrong clip duration'
        durations[clip['name']]=duration
        if clip['name'] not in held_names:
            assert len(moving_bones)>=6, 'Scenario lacks articulated temporal motion'
            assert len(frame_times)>=expected_duration*20, 'Motion not sampled densely enough'
            scenario_checks[clip['name']]={'duration':duration,'animated_bones':len(moving_bones),
                'sample_times':len(frame_times),'endpoint_max_error':max_endpoint_error,'planted_feet':True}
    relief=contract['relief']
    assert relief['region_channels']==['torso','arms','legs']
    assert set(relief['normal_textures'])=={'soft','neutral','defined'}
    indices=list(relief['normal_textures'].values())+[relief['mask_texture']]
    assert len(set(indices))==4, 'Relief maps must be separate texture resources'
    assert len(relief['grid_weights'])==30
    assert all(len(row)==3 and all(math.isfinite(value) and 0<=value<=1 for value in row)
               and abs(sum(row)-1)<.000001 for row in relief['grid_weights'])
    assert len(doc['images'])==4, 'Expected three embedded relief maps and one regional mask'
    dimensions=[]
    for index in indices:
        image=doc['images'][doc['textures'][index]['source']]
        assert 'uri' not in image and image['mimeType']=='image/png'
        view=doc['bufferViews'][image['bufferView']]; start=view.get('byteOffset',0)
        png=binary[start:start+view['byteLength']]
        assert png[:8]==b'\x89PNG\r\n\x1a\n'
        size=struct.unpack_from('>II',png,16); dimensions.append(size)
        assert size==((1024,1024) if index==relief['mask_texture'] else (2048,2048))
        geometry_hash.update(png)
    geometry_hash.update(json.dumps(doc['materials'],sort_keys=True).encode())
    geometry_digest=geometry_hash.hexdigest()
    manifest = json.loads(path.with_name('manifest.json').read_text())
    if manifest.get('version') == VERSION:
        manifest = manifest['assets'][path.stem]['export']
    assert manifest['version']=='inbody-character-v3'
    expected_surfaces = {'InBodyCharacter', 'SpartanWrap'} if manifest['character_id'] == 'spartan' else {'InBodyCharacter'}
    assert set(nodes) == expected_surfaces, 'Expected the faceless mannequin and optional wrap only'
    assert manifest['body_asset']==contract
    assert manifest['grid']['fat']==[0,.2,.4,.6,.8,1]
    assert manifest['grid']['muscle']==[0,.25,.5,.75,1]
    assert len(manifest['control_points'])==30
    assert len(raw)<=20*1024*1024, 'Transfer budget exceeded'
    assert manifest['sha256'] == hashlib.sha256(raw).hexdigest()
    assert manifest['normal_bake']['high_vertices'] > 500000
    assert manifest['normal_bake']['method'] == 'Cycles selected-to-active tangent normal bake'
    fat_volume_checks = None
    if 'fat_volume' in manifest:
        receipt = manifest['fat_volume']
        assert receipt['version'] == FAT_VOLUME_VERSION
        assert receipt['template'] == manifest['template']
        assert receipt['recipe_height_fractions'] == FAT_VOLUME_RECIPES[manifest['template']]
        helper_hash = hashlib.sha256(Path(__file__).with_name('inbody_fat_volume.py').read_bytes()).hexdigest()
        assert receipt['source_helper_sha256'] == helper_hash, 'Fat receipt uses a different compiler helper'
        expected_lean = {f'f{f}_m{m}' for f in range(3) for m in range(5)}
        assert set(receipt['exact_low_fat_coordinate_hashes']) == expected_lean
        assert set(receipt['exact_protected_coordinate_hashes']) == {f'f{f}_m{m}' for f in range(6) for m in range(5)}
        assert receipt['max_muscle_delta_error_m'] <= receipt['height_m'] * 1.e-12
        assert 0 < receipt['affected_vertex_count'] < receipt['protected_vertex_count'] + receipt['affected_vertex_count']
        assert receipt['maximum_field_length_m'] <= receipt['height_m'] * FAT_VOLUME_RECIPES[manifest['template']]['cap'] + 1.e-12
        reference, skin_rows, body_triangles = [], [], []
        decoded = [[] for _ in range(30)]
        for positions, indices, effective_shapes, skin_indices, skin_values in fat_inputs:
            offset = len(reference)
            reference.extend((x, -z, y) for x, y, z in positions)
            for bone_indices, bone_values in zip(skin_indices, skin_values):
                row = {}
                for index, value in zip(bone_indices, bone_values):
                    if value:
                        name = doc['nodes'][joints[index]]['name']
                        row[name] = row.get(name, 0.) + value
                skin_rows.append(row)
            body_triangles.extend(tuple(offset + index for index in indices[start:start+3])
                                  for start in range(0, len(indices), 3))
            for index, effective in enumerate(effective_shapes):
                decoded[index].extend((x, -z, y) for x, y, z in effective)
        field = fat_volume_field(reference, skin_rows, receipt['height_m'], manifest['template'])
        actual_sections, recovered_sections = {}, {}
        for index, coords in enumerate(decoded):
            name = f'f{index//5}_m{index%5}'
            blend = fat_volume_blend((index//5) / 5)
            recovered = [tuple(value - blend * delta for value, delta in zip(point, offset))
                         for point, offset in zip(coords, field)]
            actual_sections[name] = body_sections(coords, reference, body_triangles, receipt['height_m'])
            recovered_sections[name] = body_sections(recovered, reference, body_triangles, receipt['height_m'])
        assert receipt['sample_compositions'] == {name: list(value) for name, value in FAT_VOLUME_SAMPLES.items()}
        decoded_grid = {f'f{index//5}_m{index%5}': coords for index, coords in enumerate(decoded)}
        actual_samples, recovered_samples = {}, {}
        for name, composition in FAT_VOLUME_SAMPLES.items():
            coords = sample_body_grid(decoded_grid, *composition)
            blend = sampled_fat_volume_blend(composition[0])
            recovered = [tuple(value - blend * delta for value, delta in zip(point, offset))
                         for point, offset in zip(coords, field)]
            actual_samples[name] = body_sections(coords, reference, body_triangles, receipt['height_m'])
            recovered_samples[name] = body_sections(recovered, reference, body_triangles, receipt['height_m'])
        try:
            fat_volume_checks = check_fat_volume_sections(receipt, actual_sections, recovered_sections, actual_samples, recovered_samples)
        except FatSectionError as error:
            try:
                if error.case_id in decoded_grid:
                    coords = decoded_grid[error.case_id]
                    blend = fat_volume_blend(int(error.case_id[1]) / 5)
                    before = receipt['before_sections'][error.case_id][error.section]
                    after = receipt['after_sections'][error.case_id][error.section]
                else:
                    composition = FAT_VOLUME_SAMPLES[error.case_id]
                    coords = sample_body_grid(decoded_grid, *composition)
                    blend = sampled_fat_volume_blend(composition[0])
                    before = receipt['before_samples'][error.case_id][error.section]
                    after = receipt['after_samples'][error.case_id][error.section]
                recovered = [tuple(value - blend * delta for value, delta in zip(point, offset))
                             for point, offset in zip(coords, field)]
                write_fat_section_failure(path, manifest, error, reference, skin_rows, body_triangles,
                                          coords, recovered, before, after)
            except Exception as diagnostic_error:
                error.add_note(f'Could not preserve fat-section diagnostics: {type(diagnostic_error).__name__}: {diagnostic_error}')
            raise
        fat_volume_checks['authoring_receipt_sha256'] = hashlib.sha256(json.dumps(receipt, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        fat_volume_checks['final_glb_sha256'] = manifest['sha256']
    comparison=None
    if baseline:
        original=validate(baseline,require_scenarios=False)
        assert original['geometry_material_fingerprint']==geometry_digest, 'Accepted geometry, skin, UV, texture or material changed'
        assert original['held_pose_fingerprint']==held_fingerprint.hexdigest(), 'Accepted held pose changed'
        comparison={'file':str(baseline),'geometry_material_identical':True,'held_poses_identical':True}
    if require_scenarios:
        assert manifest['authored_motion']['provenance'].startswith('Original keyframed')
        assert manifest['authored_motion']['ground_transform_max_drift']<.000001
    return {'file': str(path), 'bytes': len(raw), 'vertices_with_uv_seams': vertices,
            'triangles': triangles, 'joints': len(joints), 'morph_count':len(names),'morphs': names,
            'grid':manifest['grid'],
            'morph_decoded_attribute_bytes':body_vertices*34*3*4*2,
            'morph_gpu_rgba_estimate_bytes':body_vertices*34*4*4*2,
            'art_mesh_checks':{'muscle_volume_decreases':volume_decreases,
                'fat_waist_depth_decreases':waist_decreases,'muscle_waist_depth_decreases':muscle_waist_decreases,
                'mesh_volume_grid':[[round(v,5) for v in volumes[f*5:f*5+5]] for f in range(6)]},
            'clips_seconds':durations,'scenario_checks':scenario_checks,'embedded_normal':dimensions,
            'pose_corrective_count':len(correction_names),'surface_roles':surfaces,
            'fat_volume_validation': fat_volume_checks,
            'geometry_material_fingerprint':geometry_digest,'held_pose_fingerprint':held_fingerprint.hexdigest(),
            'accepted_asset_comparison':comparison,
            'sha256': manifest['sha256']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('path', nargs='?', type=Path, default=ROOT / 'runtime/inbody-character/assets/character.glb')
    parser.add_argument('--baseline',type=Path,help='Verify accepted 30-shape geometry, material and held poses remain exactly unchanged')
    args=parser.parse_args()
    print(json.dumps(validate(args.path,args.baseline),indent=2))
