"""Book of Lore: original art, safe 90px text bounds, vanilla glyphs.
Build-time rasterization keeps all entry text visible inside the menu, not tooltips.
"""
import io, json, os, math, zipfile
from pathlib import Path
from PIL import Image
from gen_lore import load_lang
from lore_content import available_entries

ROOT=Path(__file__).resolve().parent.parent
ASSETS=ROOT/'build/resourcepack/assets/aether'
SOURCE=ROOT/'aether-mod/src/main/resources/assets/aether'
from minecraft_paths import client_jar
CLIENT=client_jar()

def dump(path,value):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,ensure_ascii=False,indent=1),encoding='utf8')

def build():
    glyphs={}
    with zipfile.ZipFile(CLIENT) as client:
        definition=json.loads(client.read('assets/minecraft/font/include/default.json'))
        for provider in definition['providers']:
            if provider['type']!='bitmap':continue
            ns,path=provider['file'].split(':');texture=Image.open(io.BytesIO(client.read(f'assets/{ns}/textures/{path}'))).convert('RGBA')
            rows=provider['chars'];cw=texture.width//len(rows[0]);ch=texture.height//len(rows)
            for row,chars in enumerate(rows):
                for col,char in enumerate(chars):
                    if char=='\0' or char in glyphs:continue
                    crop=texture.crop((col*cw,row*ch,(col+1)*cw,(row+1)*ch))
                    box=crop.getbbox();width=box[2] if box else 0
                    # Vanilla bitmap font's default height is eight screen pixels.
                    if ch!=8:crop=crop.resize((round(cw*8/ch),8),Image.Resampling.NEAREST);width=round(width*8/ch)
                    glyphs[char]=(crop,width+1)
    glyphs[' ']=(Image.new('RGBA',(4,8)),4)
    missing=set()
    def width(text):return sum(glyphs.get(c,glyphs.get('?',(None,6)))[1] for c in text)
    def draw(image,text,x,y):
        for c in text:
            if c not in glyphs:missing.add(c)
            glyph,advance=glyphs.get(c,glyphs['?']);ink=Image.new('RGBA',glyph.size,(64,64,64,255));ink.putalpha(glyph.getchannel('A'));image.alpha_composite(ink,(int(x),int(y)));x+=advance
    def wrap(text,limit=90):
        lines=[]
        for paragraph in text.split('\n'):
            if not paragraph:lines.append('');continue
            line=''
            for word in paragraph.split():
                if line and width(line+' '+word)>limit:lines.append(line);line=''
                while width(word)>limit:
                    n=1
                    while n<len(word) and width(word[:n+1])<=limit:n+=1
                    if line:lines.append(line);line=''
                    lines.append(word[:n]);word=word[n:]
                line+=(' ' if line else '')+word
            if line:lines.append(line)
        return lines
    source_lore=json.loads((ROOT/'plugin/src/main/resources/content/lore.json').read_text(encoding='utf8'))
    lore=available_entries(source_lore)
    lore['__empty']={'en':'Place an item in the slot on the left to read its original lore. Use the arrows to turn pages. The index opens every entry.','ru':'Поместите предмет в слот слева, чтобы прочитать о нём. Листайте страницы стрелками. В каталоге доступны все записи.'}
    lore['__unknown']={'en':'This item has no available entry in the current Book of Lore. Choose another item or open the index.','ru':'В текущей версии Книги Эфира нет доступной записи об этом предмете. Выберите другой предмет или откройте каталог.'}
    texdir=ASSETS/'textures/gui/lore';texdir.mkdir(parents=True,exist_ok=True)
    for old in texdir.glob('pages_*.png'):old.unlink()
    backgrounds={};providers=[{'type':'space','advances':{'\ue100':-48,'\ue101':-120}}]
    labels={}
    for locale,lang in [('en','en_us'),('ru','ru_ru')]:
        labels[locale]=load_lang(lang+'.json')
        assert labels[locale].get('block.aether.holystone'),locale
        for prev in (False,True):
            for nxt in (False,True):
                backing=Image.open(SOURCE/'textures/gui/menu/lore_backing.png').convert('RGBA')
                image=Image.new('RGBA',(256,256));image.alpha_composite(backing.crop((0,0,256,116)),(0,0))
                image.alpha_composite(Image.new('RGBA',(254,32),(198,198,198,255)),(1,115))
                image.alpha_composite(backing.crop((0,112,256,199)),(0,136))
                image.alpha_composite(Image.open(SOURCE/'textures/gui/menu/lore_book.png').convert('RGBA'),(13,12))
                for offset,key in enumerate(('book','of_lore')):
                    text=labels[locale].get('gui.aether.book_of_lore.'+key,('Book','of Lore')[offset]);draw(image,text,76-width(text)//2,29+10*offset)
                text=labels[locale].get('gui.aether.book_of_lore.item','Item:');draw(image,text,79-width(text),76)
                for x,enabled,arrow in ((48,prev,'<'),(192,nxt,'>')):
                    path=SOURCE/('textures/gui/sprites/menu/lore_button.png' if enabled else 'textures/gui/sprites/menu/lore_button_disabled.png')
                    button=Image.open(path).convert('RGBA').resize((18,18),Image.Resampling.NEAREST);image.alpha_composite(button,(x,108));draw(image,arrow,x+6,113)
                draw(image,'Каталог' if locale=='ru' else 'Index',112 if locale=='ru' else 118,116)
                # Crop the otherwise unused transparent tail; original backing remains.
                code=chr(0xe110+len(backgrounds));key=f'{locale}_{int(prev)}_{int(nxt)}';backgrounds[key]=code
                filename=f'background_{key}.png';image.save(texdir/filename)
                providers.append({'type':'bitmap','file':'aether:gui/lore/'+filename,'height':256,'ascent':13,'chars':[code]})
    entries={};titles={};pages=[];page_meta=[]
    for key,data in lore.items():
        entries[key]={}
        titles[key]={}
        for locale in ('en','ru'):
            text=data[locale];assert text.strip(),(key,locale);lines=wrap(text)
            title=('Книга Эфира' if locale=='ru' else 'Book of Lore') if key.startswith('__') else labels[locale].get(key.removeprefix('lore.'),key.split('.')[-1].replace('_',' '))
            titles[key][locale]=title
            title_lines=wrap(title);assert len(title_lines)<=6,(key,locale,title_lines)
            first_capacity=min(6,8-len(title_lines));body_y=max(22,len(title_lines)*10+2)
            chunks=[lines[:first_capacity]]+[lines[i:i+8] for i in range(first_capacity,len(lines),8)]
            codes=[]
            for index,chunk in enumerate(chunks):
                cell=Image.new('RGBA',(128,128));y=0
                if index==0:
                    for n,line in enumerate(title_lines):draw(cell,line,0,n*10)
                    y=body_y
                for n,line in enumerate(chunk):draw(cell,line,0,y+n*10)
                code=chr(0xe800+len(pages));codes.append(code);pages.append(cell)
                page_meta.append({'key':key,'locale':locale,'page':index,'lines':chunk,'title':title,'title_lines':title_lines if index==0 else [],'body_y':y,'glyph':code})
            entries[key][locale]=codes
    for start in range(0,len(pages),256):
        group=pages[start:start+256];rows=math.ceil(len(group)/16);atlas=Image.new('RGBA',(2048,rows*128));chars=[]
        for i,page in enumerate(group):atlas.alpha_composite(page,((i%16)*128,(i//16)*128))
        for row in range(rows):chars.append(''.join(chr(0xe800+start+row*16+col) if row*16+col<len(group) else '\0' for col in range(16)))
        filename=f'pages_{start//256}.png';atlas.save(texdir/filename)
        providers.append({'type':'bitmap','file':'aether:gui/lore/'+filename,'height':128,'ascent':-6,'chars':chars})
    dump(ASSETS/'font/lore_gui.json',{'providers':providers})
    # Keep the pack's Russian article text/names consistent with the baked pages.
    ru_path=ASSETS/'lang/ru_ru.json'
    ru_pack=json.loads(ru_path.read_text(encoding='utf8'))
    ru_pack.update(labels['ru'])
    ru_pack.update({key:data['ru'] for key,data in source_lore.items()})
    dump(ru_path,ru_pack)
    # Item models sample the item atlas, not the particle atlas.
    empty=ASSETS/'textures/item/gui_empty.png';empty.parent.mkdir(parents=True,exist_ok=True)
    Image.new('RGBA',(16,16),(0,0,0,0)).save(empty)
    dump(ASSETS/'models/gui/lore_invisible.json',{'parent':'minecraft:item/generated','textures':{'layer0':'aether:item/gui_empty'}})
    dump(ASSETS/'items/gui/lore_invisible.json',{'model':{'type':'minecraft:model','model':'aether:gui/lore_invisible'}})
    manifest={'backgrounds':backgrounds,'entries':entries,'titles':titles,'input_slot':29,'previous_slot':45,'catalog_slot':49,'next_slot':53}
    dump(ROOT/'plugin/src/main/resources/content/lore_gui.json',manifest)
    dump(ROOT/'build/lore_gui_pages.json',page_meta)
    assert not missing,missing
    # Standalone, honest texture preview, with the same font overlay coordinates.
    sample=next(i for i,p in enumerate(page_meta) if p['locale']=='ru' and p['key'].endswith('.holystone') and p['page']==0)
    preview=Image.open(texdir/'background_ru_0_1.png').convert('RGBA');preview.alpha_composite(pages[sample],(137,19))
    preview.resize((768,768),Image.Resampling.NEAREST).save(ROOT/'build/lore-gui-preview.png')
    dump(ROOT/'build/lore_availability.json',{'included':list(k for k in entries if not k.startswith('__')),'excluded':sorted(set(source_lore)-set(entries))})
    print(f'Lore menu: {len(entries)-2} available entries, {len(pages)} text pages, missing glyphs={len(missing)}')

if __name__=='__main__':build()
