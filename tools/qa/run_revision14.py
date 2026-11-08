"""Isolated localhost-only Paper + protocol client; no graphical Minecraft."""
from pathlib import Path
import json, os, shutil, subprocess, time, zipfile, argparse

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument('--revision',type=int,choices=(14,15,16,17,18),default=14)
parser.add_argument('--plugin-revision',type=int,choices=(14,15,16,17,18))
parser.add_argument('--paper-build',type=int,choices=(82,129),default=129)
args=parser.parse_args();REVISION=args.revision;PLUGIN_REVISION=args.plugin_revision or REVISION
BASE=ROOT/(f'build/qa-r{REVISION}-server' if args.paper_build==129 else f'build/qa-r{REVISION}-paper82-server')
JAVA=Path('C:/Program Files/Eclipse Adoptium/jdk-25.0.4.7-hotspot/bin/java.exe')

def link(source,target):
    target.parent.mkdir(parents=True,exist_ok=True)
    if not target.exists(): os.link(source,target)

def main():
    BASE.mkdir(parents=True,exist_ok=True)
    (BASE/'qa-input.jsonl').write_text('',encoding='utf8')
    for folder in (('libraries','versions') if args.paper_build==129 else ('libraries','cache')):
        for source in (ROOT/'server'/folder).rglob('*'):
            if source.is_file():
                target=BASE/folder/source.relative_to(ROOT/'server'/folder)
                if args.paper_build==129:link(source,target)
                elif not target.exists():target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
    source=ROOT/'server/paper-26.2-129.jar' if args.paper_build==129 else ROOT/'build/codex/r16/paper-26.2-82.jar'
    if args.paper_build==129:link(source,BASE/'paper.jar')
    else:shutil.copy2(source,BASE/'paper.jar')
    (BASE/'eula.txt').write_text('eula=true\n')
    (BASE/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25566\nonline-mode=false\nlevel-name=world\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\nmax-tick-time=120000\n')
    (BASE/'config').mkdir(exist_ok=True)
    shutil.copy2(ROOT/'server/config/paper-global.yml',BASE/'config/paper-global.yml')
    (BASE/'plugins/Aether').mkdir(parents=True,exist_ok=True)
    shutil.copy2(ROOT/f'build/codex/r{PLUGIN_REVISION}/Aether-1.0.0.jar',BASE/'plugins/Aether-1.0.0.jar')
    shutil.copy2(ROOT/f'build/codex/r{REVISION}/Revision{REVISION}.jar',BASE/f'plugins/Revision{REVISION}.jar')
    (BASE/'plugins/Aether/config.yml').write_text('resource-pack:\n  self-host: false\n  url: ""\nworld:\n  install-datapack: false\n  auto-create: false\ndungeons:\n  enabled: false\n')
    datapack=BASE/'world/datapacks/aether-port';datapack.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(ROOT/'plugin/src/main/resources/aether-datapack.zip') as archive:archive.extractall(datapack)
    server=None;bot=None
    try:
        with (BASE/'console.log').open('w',encoding='utf8') as log:
            server=subprocess.Popen([str(JAVA),'-Xms512m','-Xmx2G','-jar','paper.jar','nogui'],cwd=BASE,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
            deadline=time.monotonic()+180
            while time.monotonic()<deadline:
                text=(BASE/'console.log').read_text(encoding='utf8',errors='replace')
                if 'Done (' in text:break
                if server.poll() is not None:raise RuntimeError('server exited before ready')
                time.sleep(1)
            else:raise TimeoutError('server startup timeout')
            print('Paper ready on localhost:25566',flush=True)
            with (BASE/'protocol.log').open('w',encoding='utf8') as output:
                bot=subprocess.Popen([str(Path(os.sys.executable)),str(ROOT/'tools/protocol_probe.py'),'--port','25566'],cwd=ROOT,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
                deadline=time.monotonic()+180
                command_offset=0
                while time.monotonic()<deadline:
                    commands=(BASE/'qa-input.jsonl').read_text(encoding='utf8')
                    if len(commands)>command_offset:
                        bot.stdin.write(commands[command_offset:]);bot.stdin.flush();command_offset=len(commands)
                    text=(BASE/'console.log').read_text(encoding='utf8',errors='replace')
                    marker=f'REVISION{REVISION}_FINISHED'
                    if marker in text:
                        print(text[text.rfind(marker):].splitlines()[0],flush=True);break
                    if bot.poll() is not None:raise RuntimeError('protocol client exited')
                    time.sleep(1)
                else:raise TimeoutError('QA timeout')
    finally:
        if bot and bot.poll() is None:
            bot.stdin.write('{"op":"quit"}\n');bot.stdin.flush()
            try:bot.wait(timeout=10)
            except subprocess.TimeoutExpired:bot.terminate()
        if server and server.poll() is None:
            server.stdin.write('stop\n');server.stdin.flush()
            try:server.wait(timeout=60)
            except subprocess.TimeoutExpired:print('Server still saving; PID='+str(server.pid),flush=True)
    report=BASE/f'revision{REVISION}.txt'
    if report.exists():
        shutil.copy2(report,ROOT/(f'history/verification/revision-r{REVISION}.txt' if args.paper_build==129 else f'history/verification/revision-r{REVISION}-paper82.txt'))
        lines=report.read_text().splitlines();failed=[line for line in lines if line.startswith('FAIL')]
        print('\n'.join(failed) if failed else f'PASS {len(lines)} checks',flush=True)
        if failed:raise SystemExit(1)
    else:raise RuntimeError('missing QA report')

if __name__=='__main__':main()
