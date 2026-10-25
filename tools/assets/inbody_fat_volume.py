"""Bounded artistic fat volume and body sections for the CI asset compiler.

Coordinates use Blender's body frame: X lateral, Y posterior, Z vertical, metres.
The field is defined once on the neutral surface, never on a muscle-dependent
shape. It does not estimate fat tissue, weight or an individual's anatomy.
"""
from collections import defaultdict
import math
from typing import Mapping, Sequence


Vec3 = tuple[float, float, float]
VERSION = 'authoring-fat-volume-v1'
RECIPES = {
    'male': {'abdomen': .014, 'flanks': .006, 'back': .003, 'hips': 0., 'cap': .014},
    'female': {'abdomen': .009, 'flanks': .006, 'back': 0., 'hips': .005, 'cap': .012},
}
SECTIONS = {'pelvis': .525, 'lower_abdomen': .545, 'abdomen': .585,
            'flanks': .625, 'lower_torso': .665}
REVIEW_SAMPLES = {f'fat_midpoint_{fat:.1f}_muscle_{muscle:.1f}': (fat, muscle)
                  for fat in (.5, .7, .9) for muscle in (0., .5, 1.)}
REVIEW_SAMPLES.update(neutral=(.4, .5), athletic=(.2, 1.), soft=(.85, .2),
                      mixed=(.8, .9), runtime_art_mixed=(.85, 1.))
DIAGNOSTIC_LIMITS = {'branch_nodes': 48, 'triangles': 64, 'open_paths': 12,
                     'path_nodes': 48, 'incident_triangles_per_node': 16, 'neighbors_per_node': 16}


class FatSectionError(AssertionError):
    """Keep the strict assertion while preserving its exact diagnostic context."""
    def __init__(self, message: str, case_id: str, section: str, stage: str):
        super().__init__(message)
        self.case_id = case_id
        self.section = section
        self.stage = stage


def smooth_step(value: float) -> float:
    value = min(1., max(0., value))
    return value * value * (3. - 2. * value)


def fat_volume_blend(fat: float) -> float:
    if not math.isfinite(fat) or not 0. <= fat <= 1.:
        raise ValueError('Fat coordinate must be finite and within the authored grid')
    return smooth_step((fat - .4) / .6)


def compact_bump(value: float, center: float, radius: float) -> float:
    """A compact support with zero value and derivative at both boundaries."""
    unit = (value - center) / radius
    return (1. - unit * unit) ** 2 if abs(unit) < 1. else 0.


def fat_volume_field(reference: Sequence[Vec3], skin_rows: Sequence[Mapping[str, float]],
                     height: float, template: str) -> list[Vec3]:
    """Return a fixed torso/pelvis field, with no head, joint or limb offsets.

    Eligibility uses the same normalized top-four weights as the exported mesh.
    Component directions are outward in the fixed body frame and have no Z
    component. Compact supports cannot move the skeleton or change body height.
    """
    if template not in RECIPES or not math.isfinite(height) or height <= 0.:
        raise ValueError('Invalid body template or height')
    if len(reference) != len(skin_rows):
        raise ValueError('Body coordinates and skin rows differ')
    recipe = RECIPES[template]
    field = []
    for point, row in zip(reference, skin_rows):
        if len(point) != 3 or not all(math.isfinite(value) for value in point):
            raise ValueError('Nonfinite or invalid body coordinate')
        if not row or any(not math.isfinite(value) or value < 0. for value in row.values()):
            raise ValueError('Invalid skin weights')
        total = sum(row.values())
        if total <= 0.:
            raise ValueError('Empty skin support')
        torso = sum(value for bone, value in row.items()
                    if bone == 'root' or bone.startswith(('spine', 'pelvis'))) / total
        eligibility = smooth_step((torso - .7) / .3)
        x, y, z = (value / height for value in point)
        front = smooth_step((-y - .005) / .020)
        back = smooth_step((y - .005) / .020)
        abdomen = compact_bump(x, 0., .105) * compact_bump(z, .585, .085)
        flank = (compact_bump(abs(x), .075, .075) * compact_bump(y, 0., .14)
                 * compact_bump(z, .60, .10))
        low_back = compact_bump(x, 0., .105) * compact_bump(z, .60, .10)
        hip = (compact_bump(abs(x), .095, .065) * compact_bump(y, 0., .14)
               * compact_bump(z, .51, .08))
        sign = 1. if x > 0. else -1. if x < 0. else 0.
        dx = sign * eligibility * (recipe['flanks'] * flank + recipe['hips'] * hip)
        dy = eligibility * (-recipe['abdomen'] * abdomen * front + recipe['back'] * low_back * back)
        length = math.hypot(dx, dy)
        scale = min(1., recipe['cap'] / length) if length else 1.
        field.append((height * scale * dx, height * scale * dy, 0.))
    return field


def apply_fat_volume(coords: Sequence[Vec3], field: Sequence[Vec3], fat: float) -> list[Vec3]:
    if len(coords) != len(field):
        raise ValueError('Fat field and shape differ')
    blend = fat_volume_blend(fat)
    # Preserve exact coordinates, including signed zeros, on unmodified rows.
    if blend == 0.:
        return list(coords)
    return [point if not any(offset) else tuple(value + blend * delta for value, delta in zip(point, offset))
            for point, offset in zip(coords, field)]


def sample_body_grid(grid: Mapping[str, Sequence[Vec3]], fat: float, muscle: float) -> list[Vec3]:
    """The existing four-weight bilinear body grid, including exact endpoints."""
    fat_volume_blend(fat)
    if not math.isfinite(muscle) or not 0. <= muscle <= 1.:
        raise ValueError('Muscle coordinate must be finite and within the authored grid')
    fi, mi = min(4, math.floor(fat * 5)), min(3, math.floor(muscle * 4))
    ft, mt = fat * 5 - fi, muscle * 4 - mi
    rows = [(grid[f'f{fi}_m{mi}'], (1. - ft) * (1. - mt)),
            (grid[f'f{fi+1}_m{mi}'], ft * (1. - mt)),
            (grid[f'f{fi}_m{mi+1}'], (1. - ft) * mt),
            (grid[f'f{fi+1}_m{mi+1}'], ft * mt)]
    return [tuple(sum(coords[index][axis] * weight for coords, weight in rows) for axis in range(3))
            for index in range(len(rows[0][0]))]


def sampled_fat_volume_blend(fat: float) -> float:
    """Runtime interpolates stored anchor offsets, not a new continuous curve."""
    fat_volume_blend(fat)
    index = min(4, math.floor(fat * 5))
    blend = fat * 5 - index
    return fat_volume_blend(index / 5) * (1. - blend) + fat_volume_blend((index + 1) / 5) * blend


def body_sections(coords: Sequence[Vec3], reference: Sequence[Vec3],
                  triangles: Sequence[tuple[int, int, int]], height: float,
                  diagnostics: dict | None = None, diagnostic_section: str | None = None) -> dict:
    """Measure complete triangle-plane contours, then select the neutral torso.

    UV duplicates are welded only for contour adjacency. The torso is the largest
    closed contour whose neutral polygon centroid lies near the body midline.
    Limbs remain complete closed contours, rather than being clipped by a cage.
    These geometric areas and lengths are not estimates of tissue volumes.
    """
    if len(coords) != len(reference) or not math.isfinite(height) or height <= 0.:
        raise ValueError('Invalid section geometry')
    tolerance = height * 1.e-7
    result = {}
    for name, level in SECTIONS.items():
        record = diagnostics is not None and name == diagnostic_section
        plane = height * level
        points, neutral_points, segments = {}, {}, set()
        node_sources, triangle_states, near_triangles, odd_hit_triangles = defaultdict(set), {}, [], []
        path_traces = []
        for triangle_id, triangle in enumerate(triangles):
            hits = {}
            edge_states = []
            for i, j in zip(triangle, triangle[1:] + triangle[:1]):
                a, b = coords[i], coords[j]
                da, db = a[2] - plane, b[2] - plane
                # Plane classification must agree with interpolation. Accepting
                # a same-side "near" endpoint and clamping an extrapolated ratio
                # invented a third hit and discarded real closed-surface edges.
                coplanar = da == 0. and db == 0.
                crossing = da == 0. or db == 0. or da < 0. < db or db < 0. < da
                ratio = da / (da - db) if da != db else None
                if record:
                    edge_states.append({'vertices': [i, j], 'signed_plane_distances_m': [da, db],
                        'near_plane': [abs(da) < tolerance, abs(db) < tolerance],
                        'unclamped_ratio': ratio, 'accepted': crossing and not coplanar,
                        'reason': 'coplanar' if coplanar else 'crossing_or_exact_endpoint' if crossing else 'same_side'})
                if coplanar or not crossing:
                    continue
                hit = (a[0] + ratio * (b[0] - a[0]), a[1] + ratio * (b[1] - a[1]))
                key = tuple(round(value / tolerance) for value in hit)
                ra, rb = reference[i], reference[j]
                neutral_hit = (ra[0] + ratio * (rb[0] - ra[0]), ra[1] + ratio * (rb[1] - ra[1]))
                hits[key] = (hit, neutral_hit)
                if record:
                    edge_states[-1].update(raw_hit_xy_m=hit, neutral_hit_xy_m=neutral_hit, weld_key=key)
                    node_sources[key].add(triangle_id)
            if record and (hits or any(abs(coords[index][2] - plane) < tolerance for index in triangle)):
                triangle_states[triangle_id] = {'triangle': triangle_id, 'vertices': triangle,
                    'actual_positions_m': [coords[index] for index in triangle],
                    'neutral_positions_m': [reference[index] for index in triangle],
                    'hit_count': len(hits), 'hit_keys': sorted(hits), 'edges': edge_states}
                if any(abs(coords[index][2] - plane) < tolerance for index in triangle):
                    near_triangles.append(triangle_id)
                if len(hits) != 2:
                    odd_hit_triangles.append(triangle_id)
            if len(hits) == 2:
                keys = sorted(hits)
                segments.add(tuple(keys))
                for key in keys:
                    points.setdefault(key, hits[key][0])
                    neutral_points.setdefault(key, hits[key][1])
        adjacency = defaultdict(set)
        for a, b in segments:
            adjacency[a].add(b); adjacency[b].add(a)
        remaining = set(segments)
        loops, open_paths = [], 0
        while remaining:
            first = min(remaining)
            start, current = first
            remaining.remove(first)
            path = [start, current]
            closed = False
            while len(path) <= len(segments) + 1:
                choices = sorted(other for other in adjacency[current]
                                 if tuple(sorted((current, other))) in remaining)
                if len(adjacency[current]) != 2 or len(choices) != 1:
                    break
                following = choices[0]
                remaining.remove(tuple(sorted((current, following))))
                if following == start:
                    closed = True
                    break
                path.append(following)
                current = following
            if closed:
                polygon = [points[key] for key in path]
                area = abs(sum(a[0] * b[1] - b[0] * a[1]
                               for a, b in zip(polygon, polygon[1:] + polygon[:1]))) / 2.
                neutral_polygon = [neutral_points[key] for key in path]
                crosses = [a[0] * b[1] - b[0] * a[1]
                           for a, b in zip(neutral_polygon, neutral_polygon[1:] + neutral_polygon[:1])]
                signed_twice_area = sum(crosses)
                centroid = None
                if abs(signed_twice_area) > tolerance * tolerance:
                    centroid = tuple(sum((a[axis] + b[axis]) * cross for a, b, cross in
                        zip(neutral_polygon, neutral_polygon[1:] + neutral_polygon[:1], crosses))
                        / (3. * signed_twice_area) for axis in (0, 1))
                loops.append((area, polygon, centroid))
            else:
                open_paths += 1
                if record and len(path_traces) < DIAGNOSTIC_LIMITS['open_paths']:
                    path_traces.append({'start_key': start, 'end_key': current,
                        'end_degree': len(adjacency[current]), 'path_node_count': len(path),
                        'path_keys': path[:DIAGNOSTIC_LIMITS['path_nodes']],
                        'path_nodes_truncated': len(path) > DIAGNOSTIC_LIMITS['path_nodes']})
        torso_loops = [loop for loop in loops if loop[2] is not None
                       and abs(loop[2][0]) < .06 * height and abs(loop[2][1]) < .12 * height]
        largest = max(torso_loops, key=lambda item: item[0]) if torso_loops else None
        polygon = largest[1] if largest else []
        xs, ys = [point[0] for point in polygon], [point[1] for point in polygon]
        result[name] = {'height_fraction': level, 'segments': len(segments),
                        'closed_contours': len(loops), 'open_paths': open_paths,
                        'torso_contours': len(torso_loops), 'other_closed_contours': len(loops) - len(torso_loops),
                        'selected_neutral_centroid_m': largest[2] if largest else None,
                        'area_m2': largest[0] if largest else None,
                        'width_m': max(xs) - min(xs) if xs else None,
                        'depth_m': max(ys) - min(ys) if ys else None,
                        'front_y_m': min(ys) if ys else None, 'back_y_m': max(ys) if ys else None}
        if record:
            branch_keys = sorted(key for key, neighbors in adjacency.items() if len(neighbors) != 2)
            selected_keys = branch_keys[:DIAGNOSTIC_LIMITS['branch_nodes']]
            incident = sorted({index for key in selected_keys for index in node_sources[key]})
            priorities = incident + [index for index in odd_hit_triangles + near_triangles if index not in incident]
            selected_triangles = list(dict.fromkeys(priorities))[:DIAGNOSTIC_LIMITS['triangles']]
            diagnostics.update({'section': name, 'plane_z_m': plane, 'weld_tolerance_m': tolerance,
                'classification': 'strict sign crossing or exact endpoint; no interpolation clamping',
                'summary': result[name], 'bounds': DIAGNOSTIC_LIMITS,
                'branch_node_count': len(branch_keys), 'near_plane_triangle_count': len(near_triangles),
                'nonsegment_triangle_count': len(odd_hit_triangles),
                'branch_nodes': [{'weld_key': key, 'degree': len(adjacency[key]),
                    'actual_hit_xy_m': points[key], 'neutral_hit_xy_m': neutral_points[key],
                    'neighbor_keys': sorted(adjacency[key])[:DIAGNOSTIC_LIMITS['neighbors_per_node']],
                    'neighbors_truncated': len(adjacency[key]) > DIAGNOSTIC_LIMITS['neighbors_per_node'],
                    'incident_triangle_count': len(node_sources[key]),
                    'incident_triangles': sorted(node_sources[key])[:DIAGNOSTIC_LIMITS['incident_triangles_per_node']]}
                    for key in selected_keys],
                'triangles': [triangle_states[index] for index in selected_triangles],
                'open_path_traces': path_traces,
                'truncated': {'branch_nodes': len(branch_keys) > len(selected_keys),
                    'triangles': len(set(priorities)) > len(selected_triangles),
                    'open_paths': open_paths > len(path_traces)}})
    return result


def check_fat_volume_sections(receipt: Mapping, actual: Mapping, recovered: Mapping,
                             actual_samples: Mapping | None = None,
                             recovered_samples: Mapping | None = None) -> dict:
    """Gate decoded final-GLB sections against the actual pre/post source receipt."""
    if receipt['version'] != VERSION or receipt['template'] not in RECIPES:
        raise AssertionError('Unexpected fat-volume receipt')
    height = receipt['height_m']
    if not math.isfinite(height) or height <= 0.:
        raise AssertionError('Invalid fat-volume receipt height')
    names = {f'f{f}_m{m}' for f in range(6) for m in range(5)}
    if not all(set(rows) == names for rows in (actual, recovered, receipt['before_sections'], receipt['after_sections'])):
        raise AssertionError('Fat-volume receipt must cover all 30 anchors')
    tolerance = height * 1.e-5
    maximum_error = 0.
    for name in sorted(names):
        for label in SECTIONS:
            before, after = receipt['before_sections'][name][label], receipt['after_sections'][name][label]
            for stage, measured, expected in (('actual', actual[name][label], after), ('recovered', recovered[name][label], before)):
                if measured['closed_contours'] < 1 or measured['open_paths']:
                    raise FatSectionError(f'Incomplete final body section: {name}/{label}', name, label, stage)
                for metric in ('width_m', 'depth_m', 'front_y_m', 'back_y_m', 'area_m2'):
                    if measured[metric] is None or expected[metric] is None:
                        raise FatSectionError(f'Missing body section: {name}/{label}/{metric}', name, label, stage)
                    error = abs(measured[metric] - expected[metric])
                    allowed = tolerance * height if metric == 'area_m2' else tolerance
                    if not math.isfinite(error) or error > allowed:
                        raise FatSectionError(f'Final fat volume differs from authoring receipt: {name}/{label}/{metric}', name, label, stage)
                    maximum_error = max(maximum_error, error / height if metric == 'area_m2' else error)
            fat_index = int(name[1])
            if fat_index <= 2 and any(before[metric] != after[metric]
                                      for metric in ('width_m', 'depth_m', 'area_m2', 'front_y_m', 'back_y_m')):
                raise FatSectionError('Fat volume changed a protected lean or neutral section', name, label, 'authoring_protected')
        if int(name[1]) >= 3:
            for label in ('lower_abdomen', 'abdomen'):
                if actual[name][label]['depth_m'] <= recovered[name][label]['depth_m'] + tolerance:
                    raise FatSectionError(f'High-fat abdomen did not expand: {name}/{label}', name, label, 'actual')
            if actual[name]['flanks']['width_m'] <= recovered[name]['flanks']['width_m'] + tolerance:
                raise FatSectionError(f'High-fat flank did not expand: {name}', name, 'flanks', 'actual')
    # Only modified high-fat transitions are gated. Inherited low-fat volume
    # behavior is intentionally covered by the existing independent-axis gates.
    for muscle in range(5):
        for fat in range(2, 5):
            a, b = f'f{fat}_m{muscle}', f'f{fat+1}_m{muscle}'
            for label, metric in (('lower_abdomen', 'depth_m'), ('abdomen', 'depth_m'), ('flanks', 'width_m')):
                if actual[b][label][metric] < actual[a][label][metric] - tolerance:
                    raise FatSectionError(f'High-fat profile is not monotonic: {a}/{b}/{label}', b, label, 'actual')
    if 'before_samples' in receipt:
        if actual_samples is None or recovered_samples is None or any(set(rows) != set(REVIEW_SAMPLES)
                for rows in (actual_samples, recovered_samples, receipt['before_samples'], receipt['after_samples'])):
            raise AssertionError('Fat-volume interpolation receipt is incomplete')
        for name in REVIEW_SAMPLES:
            for label in SECTIONS:
                for stage, measured, expected in (('actual', actual_samples[name][label], receipt['after_samples'][name][label]),
                                                  ('recovered', recovered_samples[name][label], receipt['before_samples'][name][label])):
                    if measured['closed_contours'] < 1 or measured['open_paths']:
                        raise FatSectionError(f'Incomplete interpolated body section: {name}/{label}', name, label, stage)
                    for metric in ('width_m', 'depth_m', 'front_y_m', 'back_y_m', 'area_m2'):
                        if measured[metric] is None or expected[metric] is None:
                            raise FatSectionError(f'Missing interpolated body section: {name}/{label}/{metric}', name, label, stage)
                        error = abs(measured[metric] - expected[metric])
                        allowed = tolerance * height if metric == 'area_m2' else tolerance
                        if not math.isfinite(error) or error > allowed:
                            raise FatSectionError(f'Final interpolated fat volume differs: {name}/{label}/{metric}', name, label, stage)
                        maximum_error = max(maximum_error, error / height if metric == 'area_m2' else error)
    return {'all_30_sections_match_authoring': True, 'protected_low_fat_sections_identical': True,
            'high_fat_abdomen_and_flanks_expand': True, 'modified_high_fat_profiles_monotonic': True,
            'maximum_section_error_equivalent_m': maximum_error, 'section_tolerance_m': tolerance,
            'actual_sections': actual, 'recovered_unrefined_sections': recovered,
            'actual_interpolated_sections': actual_samples, 'recovered_unrefined_interpolated_sections': recovered_samples}
