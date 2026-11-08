"""Restore the exact upstream working-tree snapshot used by the port."""
import hashlib,json,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent

def main():
    target=ROOT/'aether-mod'
    if (target/'src/main/resources/assets/aether').is_dir():
        print('Aether source is already present; preserved existing working tree.');return
    archive=ROOT/'vendor/the-aether-source.zip'
    expected=json.loads((ROOT/'vendor/upstream.json').read_text(encoding='utf8'))['snapshot_sha256']
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:raise ValueError('Aether source archive checksum mismatch')
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            resolved=(target/entry.filename).resolve()
            if not resolved.is_relative_to(target.resolve()):raise ValueError('Unsafe source archive path')
        source.extractall(target)
    print('Restored aether-mod/ from vendor/the-aether-source.zip')

if __name__=='__main__':main()
