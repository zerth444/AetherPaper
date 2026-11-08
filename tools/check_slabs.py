"""Check private slab display definitions and absence of vanilla slab overrides."""
import json,zipfile
import io
from PIL import Image
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
blocks=json.loads((ROOT/'plugin/src/main/resources/content/blocks.json').read_text(encoding='utf8'))
with zipfile.ZipFile(ROOT/'dist/aether-resourcepack.zip') as pack:
    count=0
    assert not any(name.startswith('assets/minecraft/blockstates/') and name.endswith('_slab.json') for name in pack.namelist())
    for id in blocks:
        if not id.endswith('_slab'):continue
        source=ROOT/'aether-mod/src/generated/resources/assets/aether/blockstates'/(id+'.json')
        if not source.exists():source=ROOT/'aether-mod/src/main/resources/assets/aether/blockstates'/(id+'.json')
        variants=json.loads(source.read_text(encoding='utf8'))['variants']
        for shape in ('bottom','top','double'):
            definition=json.loads(pack.read('assets/aether/items/slabs/'+id+'/'+shape+'.json'))
            expected=next(v['model'] for key,v in variants.items() if 'type='+shape in key)
            assert definition['model']=={'type':'minecraft:model','model':expected},(id,shape)
            ns,path=expected.split(':');assert 'assets/'+ns+'/models/'+path+'.json' in pack.namelist(),(id,shape,expected)
            for stage in range(10):
                composite=json.loads(pack.read(f'assets/aether/items/slabs/{id}/{shape}_crack_{stage}.json'))['model']
                assert composite['type']=='minecraft:composite' and composite['models'][0]['model']==expected
                path=composite['models'][1]['model'].split(':')[1]
                overlay=json.loads(pack.read('assets/aether/models/'+path+'.json'))
                assert overlay['parent']==expected and set(overlay['textures'].values())=={f'aether:block/slab_cracks/{stage}'}
            count+=1
    for stage in range(10):
        image=Image.open(io.BytesIO(pack.read(f'assets/aether/textures/block/slab_cracks/{stage}.png'))).convert('RGBA')
        lo,hi=image.getchannel('A').getextrema();assert lo==0 and hi>0,(stage,lo,hi)
print('PASS:',count,'slab shape definitions; every original model resolves; vanilla slabs untouched')
