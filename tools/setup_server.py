"""Prepare a standalone server; never starts Java or changes network settings."""
import argparse,hashlib,json,re,shutil,urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory',type=Path,default=ROOT/'play-server')
    parser.add_argument('--paper-jar',type=Path,help='Use a local Paper 26.2 build 82 jar instead of downloading')
    parser.add_argument('--public-host',default='127.0.0.1',help='Hostname/IP reachable by players for the resource pack')
    parser.add_argument('--accept-eula',action='store_true',help='Accept https://aka.ms/MinecraftEULA')
    args=parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9.\-:\[\]]+',args.public_host):parser.error('public-host must be a hostname or IP, without a scheme/path')
    target=args.directory.resolve()
    if any((target/name).exists() for name in ('paper.jar','server.properties','plugins')):parser.error('Use a new empty server directory. Existing installations: follow README update instructions.')
    files={name:ROOT/'dist'/name for name in ('Aether-1.0.0.jar','aether-resourcepack.zip')}
    for path in files.values():
        if not path.is_file():parser.error('Missing release artifact: '+str(path))
    manifest=json.loads((ROOT/'dist/release.json').read_text(encoding='utf8'))
    for name,path in files.items():
        if hashlib.sha256(path.read_bytes()).hexdigest()!=manifest['sha256'][name]:raise ValueError('Release checksum mismatch: '+name)
    paper=json.loads((ROOT/'tools/paper-build.json').read_text(encoding='utf8'))['downloads']['server:default']
    target.mkdir(parents=True,exist_ok=True);jar=target/'paper.jar'
    try:
        if args.paper_jar:shutil.copyfile(args.paper_jar,jar)
        else:
            request=urllib.request.Request(paper['url'],headers={'User-Agent':'AetherPaper-setup/1.0 (private fan port)'})
            with urllib.request.urlopen(request,timeout=120) as response,jar.open('wb') as output:shutil.copyfileobj(response,output)
        if hashlib.sha256(jar.read_bytes()).hexdigest()!=paper['checksums']['sha256']:raise ValueError('Paper checksum mismatch; expected build 82')
    except Exception:
        jar.unlink(missing_ok=True);raise
    plugins=target/'plugins';(plugins/'Aether').mkdir(parents=True)
    shutil.copy2(files['Aether-1.0.0.jar'],plugins/'Aether-1.0.0.jar')
    shutil.copy2(files['aether-resourcepack.zip'],plugins/'Aether/resourcepack.zip')
    config=(ROOT/'plugin/src/main/resources/config.yml').read_text(encoding='utf8').replace('public-host: ""','public-host: "'+args.public_host+'"')
    (plugins/'Aether/config.yml').write_text(config,encoding='utf8')
    (target/'eula.txt').write_text('# https://aka.ms/MinecraftEULA\neula='+str(args.accept_eula).lower()+'\n',encoding='utf8')
    (target/'server.properties').write_text('online-mode=true\nserver-port=25565\nlevel-name=world\nview-distance=8\nsimulation-distance=6\n',encoding='utf8')
    (target/'start.bat').write_text('@echo off\r\ncd /d "%~dp0"\r\njava -Xms1G -Xmx3G -jar paper.jar nogui\r\npause\r\n',encoding='utf8')
    shell=target/'start.sh';shell.write_text('#!/bin/sh\ncd -- "$(dirname -- "$0")" || exit 1\nexec java -Xms1G -Xmx3G -jar paper.jar nogui\n',encoding='utf8');shell.chmod(0o755)
    print('Prepared:',target)
    print('Requires Java 25. Read/accept eula.txt, then run start.bat or sh start.sh.')
    print('After the first successful startup, run stop and start again to load the Aether dimension.')
    print('Players must reach game port 25565 and resource-pack HTTP port 8127, or use an external pack URL.')

if __name__=='__main__':main()
