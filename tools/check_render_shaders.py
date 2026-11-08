"""Compile and render the actual pack shaders offscreen. No Minecraft/window.
Requires local test-only moderngl, installed under build/python-deps.
"""
import json
import re
import struct
import zipfile
import sys
from pathlib import Path
import moderngl
import numpy as np

ROOT=Path(__file__).resolve().parent.parent
from minecraft_paths import client_jar
CLIENT=Path(client_jar())
pack=zipfile.ZipFile(ROOT/'dist/aether-resourcepack.zip')
client=zipfile.ZipFile(CLIENT)

def expand(source,include=False):
    def imported(match):
        namespace,path=match.group(1).split(':')
        key=f'assets/{namespace}/shaders/include/{path}'
        return expand((pack.read(key) if key in pack.namelist() else client.read(key)).decode(),True)
    source=re.sub(r'#moj_import\s+<([^>]+)>',imported,source)
    return re.sub(r'^#version.*$', '',source,flags=re.M) if include else source

ctx=moderngl.create_standalone_context(require=330)
quad=ctx.buffer(np.array([-1,-1,3,-1,-1,3],dtype='f4').tobytes())
target=ctx.texture((2,2),4,dtype='f4');fb=ctx.framebuffer([target]);fb.use()
checks=[]
def check(condition,message):
    checks.append({'pass':bool(condition),'check':message})
    assert condition,message

vsh='''#version 330
in vec2 Position;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
uniform float testDistance;
void main(){gl_Position=vec4(Position,0,1);sphericalVertexDistance=testDistance;cylindricalVertexDistance=testDistance;}
'''
new=ctx.program(vertex_shader=vsh,fragment_shader=expand(pack.read('assets/minecraft/shaders/core/sky.fsh').decode()))
old=ctx.program(vertex_shader=vsh,fragment_shader=expand(client.read('assets/minecraft/shaders/core/sky.fsh').decode()))

def sky(program,marker,color,distance):
    program['Fog'].binding=0;program['DynamicTransforms'].binding=1
    fog=ctx.buffer(struct.pack('10f',.75,.85,1,1,0,marker,144,192,192,192)+bytes(8));fog.bind_to_uniform_block(0)
    data=bytearray(program['DynamicTransforms'].size)
    struct.pack_into('4f',data,64,*color)
    transform=ctx.buffer(data);transform.bind_to_uniform_block(1)
    program['testDistance'].value=distance
    vao=ctx.simple_vertex_array(program,quad,'Position');vao.render(moderngl.TRIANGLES)
    pixel=np.frombuffer(fb.read(components=4,dtype='f4'),dtype='f4')[:4].copy()
    vao.release();fog.release();transform.release();return pixel

for distance in (5,64,512):
    pixel=sky(new,8191.25,(0,0,0,1),distance)
    check(np.isfinite(pixel).all() and min(pixel[:3])>.7,f'Aether black lower sky disc renders blue at shader distance {distance}: {pixel.tolist()}')
for color in ((0,0,0,1),(.71,.85,1,1)):
    for distance in (20,512):
        before=sky(old,1024,color,distance);after=sky(new,1024,color,distance)
        check(np.array_equal(before,after),f'Other dimension sky unchanged, color={color}, distance={distance}')

lvsh='''#version 330
in vec2 Position;
out vec2 texCoord;
void main(){gl_Position=vec4(Position,0,1);texCoord=vec2(0.01);}
'''
light_key='assets/minecraft/shaders/core/lightmap.fsh'
original_light=ctx.program(vertex_shader=lvsh,fragment_shader=expand(client.read(light_key).decode()))
light=ctx.program(vertex_shader=lvsh,fragment_shader=expand((pack.read(light_key) if light_key in pack.namelist() else client.read(light_key)).decode()))
def light_pixel(ambient,night_color,night_factor,gamma=.5,program=None):
    program=program or light
    program['LightmapInfo'].binding=0
    data=bytearray(program['LightmapInfo'].size)
    struct.pack_into('6f',data,0,1,1,night_factor,0,0,gamma)
    for offset,color in ((32,(1,1,1)),(48,(1,1,1)),(64,ambient),(80,night_color)):
        struct.pack_into('3f',data,offset,*color)
    buf=ctx.buffer(data);buf.bind_to_uniform_block(0)
    vao=ctx.simple_vertex_array(program,quad,'Position');vao.render(moderngl.TRIANGLES)
    pixel=np.frombuffer(fb.read(components=4,dtype='f4'),dtype='f4')[:4].copy()
    vao.release();buf.release();return pixel
before=light_pixel((0,0,0),(0,0,0),0,program=original_light)
after=light_pixel((24/255,28/255,36/255),(1,1,1),0)
check(np.isfinite(after).all() and min(after[:3])>0,f'Unlit block lightmap finite, old={before.tolist()}, new={after.tolist()}')
night=light_pixel((24/255,28/255,36/255),(1,1,1),1)
check(np.isfinite(night).all() and min(night[:3])>.99,f'Night vision/fullbright finite white lightmap: {night.tolist()}')
zero=light_pixel((0,0,0),(0,0,0),0,16)
check(np.isfinite(zero).all(),f'Guarded shader finite even with zero ambient and Gamma16: {zero.tolist()}')
gamma=light_pixel((24/255,28/255,36/255),(1,1,1),0,16)
check(np.isfinite(gamma).all() and min(gamma[:3])>0,f'Aether ambient with Fullbright Gamma16 finite: {gamma.tolist()}')
control=(.1,.12,.14)
check(np.array_equal(light_pixel(control,(1,1,1),0),light_pixel(control,(1,1,1),0,program=original_light)), 'Non-zero ordinary-world light unchanged')
report={'renderer':ctx.info['GL_RENDERER'],'minecraft_started':False,'visible_window':False,'checks':checks}
output=ROOT/(sys.argv[1] if len(sys.argv)>1 else 'history/verification/render-r4.json');output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(report,ensure_ascii=False,indent=2));ctx.release()
