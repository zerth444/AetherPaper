"""Portable reference paths. Minecraft downloads are never included in Git."""
import os
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent

def client_jar():
    configured=os.environ.get('AETHER_CLIENT_JAR')
    if configured:return configured
    home=Path(os.environ.get('APPDATA',str(Path.home())))
    candidates=[ROOT/'build/reference/client-26.2.jar',home/'.minecraft/versions/26.2/26.2.jar',Path.home()/'.minecraft/versions/26.2/26.2.jar',Path.home()/'Library/Application Support/minecraft/versions/26.2/26.2.jar']
    for path in candidates:
        if path.is_file():return str(path)
    raise FileNotFoundError('Set AETHER_CLIENT_JAR to the Minecraft 26.2 client .jar. See docs/BUILD.md.')

def server_jar():
    configured=os.environ.get('AETHER_SERVER_JAR')
    if configured:return configured
    path=ROOT/'server/versions/26.2/paper-26.2.jar'
    if path.is_file():return str(path)
    # The installed vanilla client also contains the vanilla data registries.
    return client_jar()
