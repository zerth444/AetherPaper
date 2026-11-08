"""Record distributable checksums; does not claim game validation for rebuilds."""
import hashlib,json,re,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent

def main():
    source=(ROOT/'plugin/src/main/java/com/aetherport/aether/AetherPlugin.java').read_text(encoding='utf8')
    build=re.search(r'BUILD_ID\s*=\s*"([^"]+)"',source).group(1)
    names=('Aether-1.0.0.jar','aether-resourcepack.zip','aether-datapack.zip')
    checksums={name:hashlib.sha256((ROOT/'dist'/name).read_bytes()).hexdigest() for name in names}
    with zipfile.ZipFile(ROOT/'dist/Aether-1.0.0.jar') as jar:
        layout=json.loads(jar.read('content/lore_gui.json'))
        embedded=hashlib.sha256(jar.read('aether-datapack.zip')).hexdigest()
    if embedded!=checksums['aether-datapack.zip']:raise ValueError('Embedded and standalone datapacks differ')
    manifest={'build':build,'java':25,'minecraft':'26.2','tested_paper_build':82,'book_entries':len(layout['entries'])-2,'sha256':checksums,'validation_reports':'docs/verification/ (reports identify tested builds; rebuilding alone is not gameplay validation)'}
    (ROOT/'dist/release.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    (ROOT/'dist/SHA256SUMS').write_text(''.join(value+'  '+name+'\n' for name,value in checksums.items()),encoding='utf8')
    print('Release manifest:',build)

if __name__=='__main__':main()
