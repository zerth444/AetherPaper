#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Converts Java entity Model classes into item-model JSON (cubes + UV) for
ItemDisplay-based mob visuals. Handles the common CubeListBuilder/PartPose
subset; reports anything it cannot parse.
Outputs: plugin/src/main/resources/assets/aether/models/entity/<mob>.json
"""
import json
import math
import os
import re
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JDIR = os.path.join(ROOT, "aether-mod", "src", "main", "java", "com", "aetherteam",
                    "aether", "client", "renderer", "entity")
MDIR = os.path.join(JDIR, "model")
OUTDIR = os.path.join(ROOT, "plugin", "src", "main", "resources", "assets",
                      "aether", "models", "entity")

CONSTS = {"Mth.HALF_PI": math.pi / 2, "Mth.PI": math.pi,
          "Mth.DEG_TO_RAD": math.pi / 180, "Mth.PI_2": math.pi / 2}

ADD = re.compile(
    r'(?:(\w+)\s*=\s*)?(\w+)\.addOrReplaceChild\(\s*"([^"]+)"\s*,'
    r'\s*CubeListBuilder\.create\(\)(.*?)'
    r',\s*PartPose\.(\w+)(?:\((.*?)\))?\s*\)\s*;',
    re.S)
TEX = re.compile(r"\.texOffs\(\s*([^,]+?)\s*,\s*([^)]+?)\s*\)")
BOX = re.compile(r"\.addBox\(\s*([^)]+?)\s*\)")
MIRROR = re.compile(r"\.mirror\(\s*(true|false)?\s*\)")
LAYER = re.compile(r"LayerDefinition\.create\(\s*meshDefinition\s*,\s*(\d+)\s*,\s*(\d+)\s*\)")
RENDER_TEX = re.compile(
    r'ResourceLocation\.fromNamespaceAndPath\(\s*Aether\.MODID\s*,\s*"(textures/entity/[^"]+\.png)"\)')


def parse_box_args(s):
    """Returns (u, v, mirror, [x,y,z,w,h,d]) or None. Handles both the plain
    addBox(x,y,z,w,h,d[, deformation[, u,v]]) and named variants."""
    s = s.strip()
    m = re.match(r'"[^"]*"\s*,\s*(.*)$', s, re.S)
    rest = m.group(1) if m else s
    parts = [p.strip() for p in rest.split(",")]
    if len(parts) >= 6 and not parts[0].startswith('"'):
        try:
            return None, None, None, [float(p.rstrip("FfDd")) for p in parts[:6]]
        except ValueError:
            return None
    if len(parts) >= 10 and m:
        try:
            coords = [float(parts[i].rstrip("FfDd")) for i in range(1, 7)]
            u = int(float(parts[8].rstrip("FfDd")))
            v = int(float(parts[9].rstrip("FfDd")))
            return u, v, False, coords
        except (ValueError, IndexError):
            return None
    return None


def num(s):
    s = s.strip().rstrip("FfDd")
    if s in CONSTS:
        return CONSTS[s]
    try:
        return float(s)
    except ValueError:
        return 0.0


def parse_args(s):
    return [num(a) for a in s.split(",")]


def find_renderer(mob):
    for dirpath, _d, files in os.walk(JDIR):
        for fn in files:
            if not fn.endswith("Renderer.java"):
                continue
            p = os.path.join(dirpath, fn)
            t = open(p, encoding="utf-8", errors="replace").read()
            if re.search(r"\b" + mob + r"\b", t, re.I) and "Model" in t:
                return p
    return None


EXPLICIT_MODEL = {
    "valkyrie_queen": "ValkyrieModel",
    "fire_minion": "SunSpiritModel",
}


def model_for_mob(mob):
    if mob in EXPLICIT_MODEL:
        for dirpath, _d, files in os.walk(MDIR):
            if EXPLICIT_MODEL[mob] + ".java" in files:
                return os.path.join(dirpath, EXPLICIT_MODEL[mob] + ".java")
    # direct <Mob>Model.java first
    for cand in (mob.title().replace("_", "") + "Model",
                 "".join(w.title() for w in mob.split("_")) + "Model"):
        for dirpath, _d, files in os.walk(MDIR):
            if cand + ".java" in files:
                return os.path.join(dirpath, cand + ".java")
    # via renderer usage
    rp = find_renderer(mob)
    if rp:
        t = open(rp, encoding="utf-8", errors="replace").read()
        m = re.search(r"(\w+Model)\s+\w+\s*=", t) or re.search(r"new\s+(\w+Model)\s*\(", t)
        if m:
            for dirpath, _d, files in os.walk(MDIR):
                if m.group(1) + ".java" in files:
                    return os.path.join(dirpath, m.group(1) + ".java")
    return None


def parent_model(path):
    t = open(path, encoding="utf-8", errors="replace").read()
    m = re.search(r"class\s+\w+\s+extends\s+(\w+)\s*[<{]", t)
    if not m or m.group(1) in ("EntityModel", "ListModel", "HierarchicalModel",
                                "AgeableListModel"):
        return None
    for dirpath, _d, files in os.walk(MDIR):
        if m.group(1) + ".java" in files:
            return os.path.join(dirpath, m.group(1) + ".java")
    return None


def exact_mob_texture(mob):
    base = os.path.join(ROOT, "aether-mod", "src", "main", "resources", "assets",
                        "aether", "textures", "entity", "mobs", mob, mob + ".png")
    if os.path.isfile(base):
        return "textures/entity/mobs/%s/%s.png" % (mob, mob)
    return None


def renderer_texture(mob):
    rp = find_renderer(mob)
    if not rp:
        return None
    t = open(rp, encoding="utf-8", errors="replace").read()
    m = RENDER_TEX.search(t)
    return m.group(1) if m else None


def has_boxes(path):
    t = open(path, encoding="utf-8", errors="replace").read()
    return ".addBox(" in t


def rot_point(px, py, pz, rx, ry, rz):
    # apply x, then y, then z rotation (radians) to a point
    x, y, z = px, py, pz
    cx, sx = math.cos(rx), math.sin(rx)
    y, z = y * cx - z * sx, y * sx + z * cx
    cy, sy = math.cos(ry), math.sin(ry)
    x, z = x * cy + z * sy, -x * sy + z * cy
    cz, sz = math.cos(rz), math.sin(rz)
    x, y = x * cz - y * sz, x * sz + y * cz
    return x, y, z


def rotated_faces(faces, chain):
    """Keep UV faces attached to cubes when a Java body pose turns 90 degrees.
    Rebuilding only its AABB swaps the physical dimensions without moving the
    textures, stretching a sheep's long body UV across its short end face.
    Arbitrary-angle boxes still use the documented AABB approximation.
    """
    normals={'north':(0,0,-1),'south':(0,0,1),'east':(1,0,0),'west':(-1,0,0),
             'up':(0,1,0),'down':(0,-1,0)}
    us={'north':(-1,0,0),'south':(1,0,0),'east':(0,0,-1),'west':(0,0,1),
        'up':(1,0,0),'down':(1,0,0)}
    vs={'north':(0,-1,0),'south':(0,-1,0),'east':(0,-1,0),'west':(0,-1,0),
        'up':(0,0,1),'down':(0,0,-1)}
    def turn(v):
        v=(v[0],-v[1],v[2])
        for pose in chain: v=rot_point(*v,*pose[3:6])
        return (v[0],-v[1],v[2])
    def dot(a,b): return sum(x*y for x,y in zip(a,b))
    result={}
    for name,face in faces.items():
        normal=turn(normals[name])
        target=max(normals,key=lambda key:dot(normal,normals[key]))
        if dot(normal,normals[target])<.99999: return faces
        u=turn(us[name])
        candidates=[us[target],vs[target],tuple(-v for v in us[target]),tuple(-v for v in vs[target])]
        rotation=max(range(4),key=lambda r:dot(u,candidates[r]))*90
        result[target]={**face,'rotation':rotation}
    return result


def quadruped_source(mob):
    """Vanilla quadruped base used by the mod, plus its original wing layer.
    Cow geometry follows CowModel (UV layout 64x32); pig follows PigModel.
    Wing boxes/UVs come from QuadrupedWingsModel, in a folded standing pose.
    """
    cow = mob == 'flying_cow'
    parts = []
    def part(name, box, uv, pose=(0, 0, 0), parent='root'):
        kind = 'offsetAndRotation' if len(pose) == 6 else 'offset'
        parts.append(f'PartDefinition {name} = {parent}.addOrReplaceChild("{name}", '
                     f'CubeListBuilder.create().texOffs({uv[0]},{uv[1]}).addBox('
                     + ','.join(map(str, box)) + f'), PartPose.{kind}('
                     + ','.join(map(str, pose)) + '));')
    part('head', (-4,-4,-6,8,8,6) if cow else (-4,-4,-8,8,8,8), (0,0),
         (0,4,-8) if cow else (0,12,-6))
    if cow:
        part('horn_r', (-1,-1.5,-.5,1,3,1), (22,0), (-4,-3.5,-4.5), 'head')
        part('horn_l', (-.5,-1.5,-.5,1,3,1), (22,0), (4.5,-3.5,-4.5), 'head')
    else:
        part('snout', (-2,-1,-1,4,3,1), (16,16), (0,0,-8), 'head')
    part('body', (-6,-10,-7,12,18,10) if cow else (-5,-8,-7,10,16,8),
         (18,4) if cow else (28,8), (0,5 if cow else 11,2,math.pi/2,0,0))
    if cow:
        part('udder', (-2,2,-8,4,6,1), (52,0), (0,0,0), 'body')
    for side in (-1,1):
        for front in (False,True):
            part(f'leg_{side}_{front}'.replace('-','n'), (-2,0,-2,4,12 if cow else 6,4),
                 (0,16), (side*(4 if cow else 3),12 if cow else 18,-5 if front else 7))
        name = 'wing_l' if side < 0 else 'wing_r'
        part(name, (-1,-16,0,2,16,8), (0,0),
             (side*4,2 if cow else 12,-4,0,0,side*.1))
        part(name+'_outer', (-.8,-16,.3,1.6,16,7.4), (20,0),
             (0,-16,0,0,0,side*2.94), name)
    return '\n'.join(parts) + '\nLayerDefinition.create(meshDefinition, 64, 32)'


def sheep_source():
    # SheepuffModel inherits four 12-pixel legs from QuadrupedModel. Its wool
    # layer uses a separate texture and inflated cubes (1.75 body, .6 head).
    lines = []
    def part(name, box, uv, pose, inflate=0):
        args = ','.join(map(str,box)) + (f',new CubeDeformation({inflate})' if inflate else '')
        kind = 'offsetAndRotation' if len(pose)==6 else 'offset'
        lines.append(f'root.addOrReplaceChild("{name}",CubeListBuilder.create().texOffs({uv[0]},{uv[1]}).addBox({args}),PartPose.{kind}('
                     + ','.join(map(str,pose))+'));')
    part('head',(-3,-4,-6,6,6,8),(0,0),(0,6,-8))
    part('body',(-4,-10,-7,8,16,6),(28,8),(0,5,2,math.pi/2,0,0))
    part('wool_head',(-3,-4,-4,6,6,6),(0,0),(0,6,-8),.6)
    part('wool_body',(-4,-10,-7,8,16,6),(28,8),(0,5,2,math.pi/2,0,0),1.75)
    for side in (-1,1):
        for front in (False,True):
            name=f'leg_{side}_{front}'.replace('-','n')
            pose=(side*3,12,-5 if front else 7)
            part(name,(-2,0,-2,4,12,4),(0,16),pose)
            part('wool_'+name,(-2,0,-2,4,6,4),(0,16),pose,.5)
    return '\n'.join(lines)+'\nLayerDefinition.create(meshDefinition,64,32)'


def aechor_source():
    """Expand the original model's loops, in its neutral setupAnim pose.
    The generic Java parser cannot interpret concatenated names or for loops.
    """
    lines=[]
    def part(name,box,uv,pose=(0,0,0,0,0,0),parent='root',inflate=0):
        builder=','.join(map(str,box))
        if inflate: builder+=f',new CubeDeformation({inflate})'
        lines.append(f'PartDefinition {name} = {parent}.addOrReplaceChild("{name}", CubeListBuilder.create().texOffs({uv[0]},{uv[1]}).addBox({builder}), PartPose.offsetAndRotation('+','.join(map(str,pose))+'));')
    part('stem',(-1,0,-1,2,6,2),(24,13),(0,.875,0,0,0,0))
    part('head',(-3,-3,-3,6,2,6),(0,12),(0,1.75,0,0,0,0),inflate=.75)
    for n,box in enumerate([(-1.75,1.25,-1,1,1,1),(-1,2.25,.75,1,1,1),(.75,1.25,0,1,1,1),(0,2.25,-1.75,1,1,1)]):
        part(f'thorn_{n}',box,(32,13),parent='stem',inflate=-.25)
    for n in range(3):
        name=f'stamen_{n}'
        part(name,(0,-9,-1.5,1,6,1),(36,13),(0,1.75,0,.2+n/15,.1+math.tau*n/3,0),'stem',-.25)
        part(f'tip_{n}',(0,-9,-1.5,1,1,1),(32,15),parent=name,inflate=.125)
    for n in range(10):
        part(f'leaf_{n}',(-2,-1,-9.5,4,1,8),(38,13),(0,1.75,0,.1 if n%2==0 else .2,math.pi/10+math.tau*n/10,0),'stem',-.15)
        part(f'petal_{n}',(-4,-1,-12,8,1,10),(28,2) if n%2==0 else (0,0),(0,1.75,0,-.25 if n%2==0 else -.4125,math.tau*n/10,0),'stem',-.25)
    return '\n'.join(lines)+'\nLayerDefinition.create(meshDefinition,64,32)'

def main(mobs):
    os.makedirs(OUTDIR, exist_ok=True)
    report = OrderedDict()
    for mob in mobs:
        path = model_for_mob(mob)
        if path and not has_boxes(path):
            parent = parent_model(path)
            if parent:
                path = parent
        if not path and mob not in ('flying_cow', 'phyg'):
            report[mob] = "no-model-file"
            continue
        text = (quadruped_source(mob) if mob in ('flying_cow', 'phyg') else sheep_source() if mob=='sheepuff'
                else open(path, encoding="utf-8", errors="replace").read())
        if mob=='aerbunny':
            text=text.replace('2.0F, 2.0F, 2.0F), PartPose.offset(', '2.0F, 4.0F, 2.0F), PartPose.offset(')
            text=text.replace('2.0F, 2.0F, 4.0F), PartPose.offset(', '2.0F, 4.0F, 4.0F), PartPose.offset(')
            # setupAnim counter-rotates the front legs against the horizontal
            # body; without this their cubes are buried in the torso.
            text=text.replace('PartPose.offset(-3.0F, -3.0F, -3.0F)', 'PartPose.offsetAndRotation(-3,-3,-3,-1.57079632679,0,0)')
            text=text.replace('PartPose.offset(3.0F, -3.0F, -3.0F)', 'PartPose.offsetAndRotation(3,-3,-3,-1.57079632679,0,0)')
            # setupAnim counter-rotates ALL four feet, not just the front pair.
            text=text.replace('PartPose.offset(-3.0F, 4.0F, -3.0F)', 'PartPose.offsetAndRotation(-3,4,-3,-1.57079632679,0,0)')
            text=text.replace('PartPose.offset(3.0F, 4.0F, -3.0F)', 'PartPose.offsetAndRotation(3,4,-3,-1.57079632679,0,0)')
        if mob=='aechor_plant': text=aechor_source()
        lm = LAYER.search(text)
        tw, th = (int(lm.group(1)), int(lm.group(2))) if lm else (64, 32)
        tex = exact_mob_texture(mob) or renderer_texture(mob)
        # parent tracking: var name -> parent var
        parents = {}
        poses = {}
        cubes = []  # (part, u, v, mirror, box)
        for m in ADD.finditer(text):
            var, parent, name = m.group(1), m.group(2), m.group(3)
            builder, pose_kind, pose_args = m.group(4), m.group(5), m.group(6)
            part_key = var or parent + '/' + name
            parents[part_key] = parent
            u = v = 0
            mirror = False
            # walk builder calls in order
            for part in re.finditer(r"\.(texOffs|addBox|mirror)\(([^)]*)\)", builder):
                kind, args = part.group(1), part.group(2)
                if kind == "texOffs":
                    a = parse_args(args)
                    u, v = int(a[0]), int(a[1])
                elif kind == "mirror":
                    mirror = args.strip() != "false"
                elif kind == "addBox":
                    box = parse_box_args(args)
                    if box is not None:
                        bu, bv, bm, coords = box
                        deformation = re.search(r'new CubeDeformation\(\s*(-?[\d.]+)', args)
                        if deformation: coords.append(float(deformation.group(1)))
                        cubes.append((part_key, name,
                                      bu if bu is not None else u,
                                      bv if bv is not None else v,
                                      bm if bm is not None else mirror, coords))
            pa = parse_args(pose_args) if pose_args else []
            if pose_kind == "offset":
                poses[part_key] = (pa + [0, 0, 0, 0, 0, 0])[:6]
            elif pose_kind in ("offsetAndRotation", "rotation"):
                while len(pa) < 6:
                    pa.append(0.0)
                poses[part_key] = ([0, 0, 0] + pa[:3]) if pose_kind == 'rotation' else pa[:6]
            else:  # ZERO etc.
                poses[part_key] = [0, 0, 0, 0, 0, 0]
        # resolve world transform per part (walk up parents)
        # map child name -> defining var
        elements = []
        failed = 0
        raw = []
        top = 24.0
        for part_var, cname, u, v, mirror, box in cubes:
            if mob == 'aerbunny' and cname == 'puff':
                # renderToBuffer translates the separate puff part down by one block.
                poses[part_var][1] = 16.0
            if len(box) < 6:
                failed += 1
                continue
            # accumulate offsets/rotations from root to this cube's part
            chain = []
            cur = part_var
            seen = set()
            while cur and cur not in seen:
                seen.add(cur)
                # find pose key: vars are keyed by var name or child name
                pose = poses.get(cur)
                if pose is None:
                    # try child-name lookup for root-level parts
                    pose = poses.get(cname if cur == part_var else cur, [0]*6)
                chain.append(pose or [0]*6)
                cur = parents.get(cur)
            ox = oy = oz = rx = ry = rz = 0.0
            for p in reversed(chain):
                ox += p[0]
                oy += p[1]
                oz += p[2]
            # rotations: apply from root outward (approx: sum each axis)
            for p in reversed(chain):
                rx += p[3]
                ry += p[4]
                rz += p[5]
            x, y, z, w, h, d = box[:6]
            inflation = box[6] if len(box)>6 else 0
            # 8 corners, transform, re-derive AABB (handles 90-degree poses)
            corners = []
            for cxi in (x-inflation, x + w+inflation):
                for cyi in (y-inflation, y + h+inflation):
                    for czi in (z-inflation, z + d+inflation):
                        point = (cxi, cyi, czi)
                        for pose in chain:
                            point = rot_point(*point, *pose[3:6])
                            point = tuple(point[i] + pose[i] for i in range(3))
                        corners.append(point)
            xs = [c[0] for c in corners]
            ys = [c[1] for c in corners]
            zs = [c[2] for c in corners]
            joint = (0,0,0)
            for pose in chain:
                joint=rot_point(*joint,*pose[3:6])
                joint=tuple(joint[i]+pose[i] for i in range(3))
            raw.append((xs, ys, zs, u, v, mirror, w, h, d, cname,joint,chain))
        limb_elements = []
        for cube_index,(xs, ys, zs, u, v, mirror, w, h, d, cname,joint,chain) in enumerate(raw):
            x0, x1 = min(xs), max(xs)
            y1, y0 = top - min(ys), top - max(ys)
            z0, z1 = min(zs), max(zs)
            # Bird cube definitions specify texScaleU/V=0.5 (not geometry scale).
            uv_scale = .5 if mob in ('moa', 'cockatrice') else 1
            faces = box_uv(u, v, w, h, d, tw * uv_scale, th * uv_scale, mirror)
            faces = rotated_faces(faces,chain)
            if mob in ('flying_cow', 'phyg') and cname.startswith('wing_'):
                for face in faces.values(): face['texture'] = '#wings'
            if mob=='sheepuff' and cname.startswith('wool_'):
                for face in faces.values(): face['texture'] = '#wool'
            if (mob=='aechor_plant' and cname.startswith('petal_')) or (mob in ('flying_cow','phyg') and cname.startswith('wing_')):
                # The AABB approximation must not leave large coplanar faces.
                y0+=cube_index*.015;y1+=cube_index*.015
            element = {"from": [r2(x0), r2(y0), r2(z0)],
                             "to": [r2(x1), r2(y1), r2(z1)],
                             "faces": faces}
            if ('leg' in cname) and mob in ('flying_cow','phyg','sheepuff','moa','cockatrice','aerbunny'):
                limb_elements.append((cname,element,(joint[0],24-joint[1],joint[2])))
            else: elements.append(element)
        if mob=='aerbunny' and limb_elements:
            # Match the lowest foot to the carrier's ground plane. Apply the
            # same shift to the torso and joint pivots, never lengthen a limb.
            bottom=min(part['from'][1] for _,part,_ in limb_elements)
            for element in elements:
                for corner in ('from','to'): element[corner][1]-=bottom
            shifted=[]
            for name,element,joint in limb_elements:
                for corner in ('from','to'): element[corner][1]-=bottom
                shifted.append((name,element,(joint[0],joint[1]-bottom,joint[2])))
            limb_elements=shifted
        texref = None
        if tex:
            texref = "aether:" + tex.replace("textures/", "", 1)
            if texref.endswith(".png"):
                texref = texref[:-4]
        if not texref or not elements:
            raise ValueError(f"Incomplete entity model: {mob}")
        if mob=='aechor_plant':
            bottom=min(element['from'][1] for element in elements)
            for element in elements:
                for corner in ('from','to'): element[corner][1]-=bottom
        display_height = max(e['to'][1] for e in elements) / 16.0
        scale = max(1.0, max(abs(e[k][axis]) / 23.9
                            for e in elements for k in ('from', 'to') for axis in (0, 2)),
                    max(e['to'][1] for e in elements) / 31.9)
        for element in elements:
            for corner in ('from', 'to'):
                x, y, z = element[corner]
                element[corner] = [round(8 + x / scale, 6), round(y / scale, 6),
                                   round(8 + z / scale, 6)]
        model = {"credit": "Converted from The Aether (fan port, do not distribute)",
                 "texture_size": [tw, th],
                 "aether_display_scale": scale,
                 "aether_display_height": display_height,
                 "aether_renderer_scale": {'aerwhale': 2.0, 'zephyr': 4.5, 'moa': 1.8, 'cockatrice': 1.8}.get(mob, 1.0),
                 "textures": {"0": texref, "particle": texref},
                 "elements": elements}
        if mob in ('flying_cow', 'phyg'):
            model['textures']['wings'] = f'aether:entity/mobs/{mob}/{mob}_wings'
        if mob=='sheepuff': model['textures']['wool']='aether:entity/mobs/sheepuff/sheepuff_wool'
        model['aether_limbs']=[]
        for name,element,joint in limb_elements:
            limb_name=mob+'__'+name.lower()
            for corner in ('from','to'):
                element[corner]=[round(element[corner][i]-joint[i]+(8 if i!=1 else 0),6) for i in range(3)]
            limb={'textures':model['textures'],'elements':[element], 'aether_display_scale':1}
            json.dump(limb,open(os.path.join(OUTDIR,limb_name+'.json'),'w',encoding='utf-8'),indent=1)
            model['aether_limbs'].append({'model':'aether:entity/'+limb_name,'pivot':[v/16 for v in joint],
                                          'phase':0 if joint[0]*joint[2]>0 else math.pi,
                                          'walk':'leg' in name,
                                          'wool':name.startswith('wool_')})
        json.dump(model, open(os.path.join(OUTDIR, mob + ".json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
        report[mob] = f"cubes={len(elements)} failed={failed} tex={tex}"
    print(json.dumps(report, ensure_ascii=False, indent=1))


def r2(v):
    return round(v, 2)


def box_uv(u, v, w, h, d, tw, th, mirror):
    def uv(x0, y0, x1, y1):
        if mirror:
            x0, x1 = x1, x0
        return {"uv": [round(x0 * 16 / tw, 6), round(y0 * 16 / th, 6),
                       round(x1 * 16 / tw, 6), round(y1 * 16 / th, 6)], "texture": "#0"}
    return {
        "north": uv(u + d, v + d, u + d + w, v + d + h),
        "east": uv(u + d + w, v + d, u + d + w + d, v + d + h),
        "south": uv(u + d + w + d, v + d, u + d + w + d + w, v + d + h),
        "west": uv(u, v + d, u + d, v + d + h),
        "up": uv(u + d, v, u + d + w, v + d),
        "down": uv(u + d + w, v, u + d + w + w, v + d),
    }


if __name__ == "__main__":
    import sys
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import json as _j
    inv = _j.load(open(os.path.join(ROOT, "tools", "inventory.json"), encoding="utf-8"))
    mobs = [e for e in inv["entities"] if e not in
            {"skyroot_boat", "skyroot_chest_boat", "cold_parachute", "golden_parachute",
             "floating_block", "tnt_present", "zephyr_snowball", "cloud_crystal",
             "fire_crystal", "ice_crystal", "thunder_crystal", "golden_dart",
             "poison_dart", "enchanted_dart", "poison_needle", "lightning_knife",
             "hammer_projectile"}]
    main(mobs)
    # The original Swet renderer uses vanilla SlimeModel geometry with Aether
    # textures. Keep this per-entity so ordinary Overworld slimes stay green.
    for mob, colour in [('blue_swet','blue'),('golden_swet','golden')]:
        texture=f'aether:entity/mobs/swet/swet_{colour}'
        model={'textures':{'0':texture,'particle':texture},
               'aether_display_height':.75,'aether_display_scale':1,
               'aether_renderer_scale':1.5,'elements':[],'aether_limbs':[]}
        for x,y,z,w,h,d,u,v in [(4,0,4,8,8,8,0,0),(5,1,5,6,6,6,0,16),(4.75,4,3.5,2,2,2,32,0),(9.25,4,3.5,2,2,2,32,4),(7.5,2,3.5,1,1,1,32,8)]:
            model['elements'].append({'from':[x,y,z],'to':[x+w,y+h,z+d],
                                     'faces':box_uv(u,v,w,h,d,64,32,False)})
        with open(os.path.join(OUTDIR,mob+'.json'),'w',encoding='utf-8') as f:
            json.dump(model,f,indent=1)
