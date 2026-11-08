"""Interactive offline protocol-776 client for local Paper regression checks.

JSON lines on stdin: {"op":"break","pos":[x,y,z]}, {"op":"use","pos":[x,y,z]},
{"op":"pick","pos":[x,y,z]}, {"op":"move","pos":[x,y,z]}, {"op":"quit"}.
Packet IDs are pinned to the project's Paper 26.2 jar, not newer protocol versions.
"""
import hashlib
import json
import queue
import socket
import struct
import sys
import threading
import time
import zlib
from pathlib import Path

def nbt(data, at, kind=None):
    if kind is None: kind,at=data[at],at+1
    formats={1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}
    if kind in formats:
        fmt=formats[kind];return struct.unpack_from(fmt,data,at)[0],at+struct.calcsize(fmt)
    if kind==8:
        size=struct.unpack_from('>H',data,at)[0];at+=2
        return data[at:at+size].decode('utf-8'),at+size
    if kind==10:
        result={}
        while data[at]:
            child,at=data[at],at+1;name,at=nbt(data,at,8);result[name],at=nbt(data,at,child)
        return result,at+1
    if kind==9:
        child,size=struct.unpack_from('>Bi',data,at);at+=5;result=[]
        for _ in range(size):value,at=nbt(data,at,child);result.append(value)
        return result,at
    if kind in (7,11,12):
        size=struct.unpack_from('>i',data,at)[0];at+=4
        fmt={7:'b',11:'i',12:'q'}[kind];values=struct.unpack_from('>'+fmt*size,data,at)
        return list(values),at+size*struct.calcsize(fmt)
    if kind==0:return None,at
    raise ValueError('Unknown NBT tag '+str(kind))


def vi(value):
    value &= 0xffffffff
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def read_vi(data, offset=0):
    value = 0
    for i in range(5):
        b = data[offset + i]
        value |= (b & 127) << (7 * i)
        if b < 128:
            return value, offset + i + 1
    raise ValueError("Invalid VarInt")


def string(value):
    data = value.encode()
    return vi(len(data)) + data


def read_string(data, offset):
    length, start = read_vi(data, offset)
    return data[start:start+length].decode('utf-8'), start+length


def spawn_info(data, offset):
    _, offset = read_vi(data, offset)  # dimension-type holder
    dimension, offset = read_string(data, offset)
    offset += 8 + 2  # seed, current/previous game mode
    debug, flat = bool(data[offset]), bool(data[offset+1])
    return {'dimension': dimension, 'is_flat': flat, 'is_debug': debug}


def position(pos):
    x, y, z = map(int, pos)
    return struct.pack('>Q', ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 4095))


def main():
    name = 'AetherBot'
    port=int(sys.argv[sys.argv.index('--port')+1]) if '--port' in sys.argv else 25565
    sock = socket.create_connection(('127.0.0.1', port), timeout=10)
    sock.settimeout(0.1)
    compression = False
    state = 'login'
    buffer = bytearray()
    commands = queue.Queue()
    report = Path(__file__).resolve().parent.parent / 'history/verification/protocol-probe.jsonl'
    report.parent.mkdir(parents=True, exist_ok=True)

    def emit(**data):
        line = json.dumps({'time': time.time(), **data})
        with report.open('a', encoding='utf-8') as log:
            log.write(line + '\n')
        print(line, flush=True)

    def send(packet, payload=b''):
        body = vi(packet) + payload
        if compression:
            body = vi(0) + body
        sock.sendall(vi(len(body)) + body)

    def input_loop():
        for line in sys.stdin:
            try:
                commands.put(json.loads(line))
            except ValueError as error:
                emit(error=str(error))

    threading.Thread(target=input_loop, daemon=True).start()
    send(0, vi(776) + string('localhost') + struct.pack('>H', 25565) + vi(2))
    uid = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    uid[6] = (uid[6] & 15) | 48
    uid[8] = (uid[8] & 63) | 128
    send(0, string(name) + uid)
    sequence = 0
    entity_id = None
    simulate_launch='--simulate-launch' in sys.argv
    physics_pos=None
    physics_velocity=[0.,0.,0.]
    physics_active=False
    next_physics=time.monotonic()
    physics_tick=0
    floor_y=float(sys.argv[sys.argv.index('--floor')+1]) if '--floor' in sys.argv else 151.
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        if simulate_launch and physics_active and physics_pos is not None and time.monotonic()>=next_physics:
            next_physics=time.monotonic()+.05;physics_tick+=1
            for i in range(3):physics_pos[i]+=physics_velocity[i]
            ground=physics_pos[1]<=floor_y
            if ground:physics_pos[1]=floor_y;physics_velocity[1]=0
            else:physics_velocity[1]=(physics_velocity[1]-.08)*.98
            physics_velocity[0]*=.91;physics_velocity[2]*=.91
            send(30,struct.pack('>dddB',*physics_pos,int(ground)))
            if physics_tick%5==0:emit(physics_pos=physics_pos,velocity_y=physics_velocity[1],ground=ground)
        while not commands.empty() and state == 'play':
            command = commands.get()
            op = command['op']
            sequence += 1
            if op == 'quit':
                sock.close()
                return
            if op == 'break':
                send(41, vi(0) + position(command['pos']) + b'\x01' + vi(sequence))
            elif op == 'dig':
                send(41,vi(command.get('action',0))+position(command['pos'])+bytes([command.get('face',1)])+vi(sequence))
            elif op == 'use':
                send(66, vi(command.get('hand', 0)) + position(command['pos'])
                     + vi(command.get('face', 1)) + struct.pack('>fff', *command.get('cursor', [.5, 1., .5]))
                     + b'\x00\x00' + vi(sequence))
            elif op == 'move':
                send(30, struct.pack('>dddB', *command['pos'], 1))
            elif op == 'command':
                send(7, string(command['command']))
            elif op == 'pick':
                send(command['packet'], position(command['pos']) + b'\x00')
            emit(sent=command)
        try:
            data = sock.recv(1048576)
            if not data:
                emit(disconnected=True)
                return
            buffer.extend(data)
        except socket.timeout:
            continue
        while buffer:
            try:
                length, start = read_vi(buffer)
            except IndexError:
                break
            if len(buffer) < start + length:
                break
            body = bytes(buffer[start:start + length])
            del buffer[:start + length]
            if compression:
                uncompressed, offset = read_vi(body)
                body = zlib.decompress(body[offset:]) if uncompressed else body[offset:]
            packet, offset = read_vi(body)
            payload = body[offset:]
            if state == 'login':
                if packet == 3:
                    compression = True
                elif packet == 2:
                    send(3)
                    state = 'config'
                    send(0, string('en_us') + b'\x04' + vi(0) + b'\x01\x7f'
                         + vi(1) + b'\x00\x01' + vi(0))
                elif packet == 0:
                    emit(login_disconnect=payload.hex())
                    return
            elif state == 'config':
                if packet == 7:
                    registry,at=read_string(payload,0)
                    if registry=='minecraft:dimension_type':
                        count,at=read_vi(payload,at)
                        for _ in range(count):
                            entry,at=read_string(payload,at);present=payload[at];at+=1
                            if present:
                                value,at=nbt(payload,at)
                                emit(dimension_registry=entry,attributes=value.get('attributes',{}))
                elif packet == 14:
                    send(7, vi(0))
                elif packet == 4:
                    send(4, payload)
                elif packet == 5:
                    send(5, payload)
                elif packet == 3:
                    send(3)
                    state = 'play'
                    emit(joined=True)
                elif packet == 9:
                    send(6, payload[:16] + vi(1))
            else:
                if packet == 49:
                    entity_id = struct.unpack_from('>i', payload)[0]
                    emit(entity_id=entity_id)
                    try:
                        count, at = read_vi(payload, 5)
                        for _ in range(count): _, at = read_string(payload, at)
                        for _ in range(3): _, at = read_vi(payload, at)
                        emit(spawn_info=spawn_info(payload, at+3), packet=packet)
                    except (IndexError, UnicodeDecodeError) as error: emit(world_info_error=str(error), packet=packet)
                elif packet == 82:
                    emit(spawn_info=spawn_info(payload, 0), packet=packet)
                elif packet == 11:
                    send(11, struct.pack('>f', 20.0))
                elif packet == 44:
                    send(28, payload)
                elif packet == 72:
                    teleport, at = read_vi(payload)
                    coords = struct.unpack_from('>ddd', payload, at)
                    delta=struct.unpack_from('>ddd',payload,at+24)
                    flags=struct.unpack_from('>i',payload,at+56)[0]
                    if simulate_launch:
                        old=physics_pos or [0.,0.,0.]
                        physics_pos=[coords[i]+(old[i] if flags & (1<<i) else 0) for i in range(3)]
                        physics_velocity=[delta[i]+(physics_velocity[i] if flags & (1<<(5+i)) else 0) for i in range(3)]
                        physics_active=abs(physics_velocity[1])>.01
                        coords=physics_pos
                        sock.settimeout(.01)
                    send(0, vi(teleport))
                    send(44)
                    send(30, struct.pack('>dddB', *coords, int(not physics_active)))
                    emit(teleport=teleport, pos=coords,delta=delta,flags=flags)
                elif packet == 117:
                    emit(sound=payload.hex())
                elif packet == 58:
                    emit(open_book=True, hand=read_vi(payload)[0])
                elif packet == 46:
                    emit(level_event=struct.unpack_from('>i', payload)[0])
                elif packet == 8:
                    emit(block_update=payload.hex())
                elif packet == 101:
                    moving, _ = read_vi(payload)
                    if moving == entity_id:
                        emit(motion=payload.hex(),motion_ignored=simulate_launch)
                elif packet == 32:
                    emit(play_disconnect=payload.hex())
    sock.close()


if __name__ == '__main__':
    main()
