"""Display-only models for real, individually identified slabs. Vanilla assets stay intact."""
import json
import io,zipfile
from pathlib import Path
from PIL import Image
from minecraft_paths import client_jar
ROOT=Path(__file__).resolve().parent.parent

def build():
    blocks=json.loads((ROOT/'plugin/src/main/resources/content/blocks.json').read_text(encoding='utf8'))
    # Vanilla destruction textures use multiplicative blending. Convert the
    # white background to alpha for an item-model overlay on the custom visual.
    textures=ROOT/'build/resourcepack/assets/aether/textures/block/slab_cracks';textures.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(client_jar()) as client:
        for stage in range(10):
            source=Image.open(io.BytesIO(client.read(f'assets/minecraft/textures/block/destroy_stage_{stage}.png'))).convert('RGBA')
            result=Image.new('RGBA',source.size)
            result.putdata([(0,0,0,round((255-r)*a/255)) for r,g,b,a in source.getdata()])
            result.save(textures/f'{stage}.png')
    count=0
    for id in blocks:
        if not id.endswith('_slab'):continue
        paths=[ROOT/'aether-mod'/base/'assets/aether/blockstates'/(id+'.json') for base in ('src/generated/resources','src/main/resources')]
        source=next(p for p in paths if p.exists());variants=json.loads(source.read_text(encoding='utf8'))['variants']
        for shape in ('bottom','top','double'):
            model=next(value['model'] for key,value in variants.items() if 'type='+shape in key)
            target=ROOT/'build/resourcepack/assets/aether/items/slabs'/id/(shape+'.json');target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(json.dumps({'model':{'type':'minecraft:model','model':model}},indent=2),encoding='utf8');count+=1
            for stage in range(10):
                overlay=f'aether:block/slab_cracks/{id}/{shape}_{stage}'
                model_path=ROOT/'build/resourcepack/assets/aether/models/block/slab_cracks'/id/f'{shape}_{stage}.json';model_path.parent.mkdir(parents=True,exist_ok=True)
                texture=f'aether:block/slab_cracks/{stage}'
                model_path.write_text(json.dumps({'parent':model,'textures':{name:texture for name in ('all','texture','side','top','bottom','end','particle')}},indent=2),encoding='utf8')
                definition={'model':{'type':'minecraft:composite','models':[{'type':'minecraft:model','model':model},{'type':'minecraft:model','model':overlay}]}}
                target.with_name(f'{shape}_crack_{stage}.json').write_text(json.dumps(definition,indent=2),encoding='utf8')
    print('Slab visual definitions:',count,'(no vanilla slab overrides)')

if __name__=='__main__':build()
