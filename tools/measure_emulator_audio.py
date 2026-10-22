"""Measure post-Android output through the emulator's authenticated localhost audio stream.

Requires grpcio + grpcio-tools, ANDROID_HOME, adb-installed debug and test APKs,
and an emulator started with -grpc PORT -grpc-use-token. Never run against a phone.
"""
import argparse
import array
import importlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

import grpc
from grpc_tools import protoc
import grpc_tools


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', default='emulator-5558')
    parser.add_argument('--port', type=int, default=8558)
    parser.add_argument('--output', required=True)
    parser.add_argument('--player', choices=('fixture', 'localmusic'), default='fixture')
    parser.add_argument('--localmusic-eq', choices=('true', 'false'))
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise SystemExit('This probe is for an emulator only.')
    sdk = Path(os.environ['ANDROID_HOME'])
    generated = Path('.gradle/audio-probe/proto').resolve()
    generated.mkdir(parents=True, exist_ok=True)
    includes = Path(grpc_tools.__file__).parent / '_proto'
    proto = sdk / 'emulator/lib/emulator_controller.proto'
    result = protoc.main(['protoc', f'-I{proto.parent}', f'-I{includes}',
                          f'--python_out={generated}', f'--grpc_python_out={generated}', str(proto)])
    if result:
        raise SystemExit('Could not compile the installed emulator API.')
    sys.path.insert(0, str(generated))
    pb = importlib.import_module('emulator_controller_pb2')
    api = importlib.import_module('emulator_controller_pb2_grpc')
    token = None
    discovery = Path(os.environ['LOCALAPPDATA']) / 'Temp/avd/running'
    for entry in discovery.glob('pid_*.ini'):
        config = dict(line.strip().split('=', 1) for line in entry.read_text().splitlines() if '=' in line)
        if config.get('grpc.port') == str(args.port):
            token = config.get('grpc.token')
    if not token:
        raise SystemExit('Authenticated emulator endpoint not found.')
    channel = grpc.insecure_channel(f'127.0.0.1:{args.port}')
    stub = api.EmulatorControllerStub(channel)
    call = stub.streamAudio(pb.AudioFormat(samplingRate=48000, channels=0, format=1),
                            metadata=(('authorization', f'Bearer {token}'),), timeout=90)
    phase = {'level': None, 'since': 0.0}
    measurements = {n: [0, 0.0] for n in (100, 67, 34)}
    errors = []

    def collect():
        try:
            for packet in call:
                n = phase['level']
                age = time.monotonic() - phase['since']
                if n in measurements and 1.2 < age < 4.0:
                    samples = array.array('h', packet.audio)
                    count, total = measurements[n]
                    measurements[n] = [count + len(samples), total + sum(float(s) * s for s in samples)]
        except grpc.RpcError as exc:
            if exc.code() != grpc.StatusCode.CANCELLED:
                errors.append(str(exc.code()))

    capture = threading.Thread(target=collect, daemon=True)
    capture.start()
    adb = sdk / 'platform-tools/adb.exe' if os.name == 'nt' else sdk / 'platform-tools/adb'
    test = 'com.abhayshah.volumecontrol.InteroperabilityTest#localMusicKeepsPlayingWhileFineGainChanges' if args.player == 'localmusic' else 'com.abhayshah.volumecontrol.ExperienceTest#sharedOutputProbeForExternalCapture'
    command = [str(adb), '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
               '-e', 'externalAudioProbe', 'true', '-e', 'externalPlayer', args.player, '-e', 'class', test,
               'com.abhayshah.volumecontrol.test/androidx.test.runner.AndroidJUnitRunner']
    if args.localmusic_eq:
        command[-1:-1] = ['-e', 'externalEq', args.localmusic_eq]
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    watchdog = threading.Timer(85, proc.kill)
    watchdog.start()
    transcript = []
    for line in proc.stdout:
        transcript.append(line)
        if line.startswith('INSTRUMENTATION_STATUS: phase='):
            phase.update(level=int(line.split('=', 1)[1]), since=time.monotonic())
    proc.wait(timeout=10)
    watchdog.cancel()
    call.cancel()
    capture.join(timeout=5)
    channel.close()
    levels = {}
    for n, (count, total) in measurements.items():
        rms = math.sqrt(total / count) if count else 0
        levels[str(n)] = {'samples': count, 'rms_dbfs': 20 * math.log10(rms / 32768) if rms else None}
    transcript_text = ''.join(transcript)
    valid = not errors and proc.returncode == 0 and 'OK (1 test)' in transcript_text and 'FAILURES' not in transcript_text and all(v['samples'] > 24000 and v['rms_dbfs'] is not None for v in levels.values())
    if valid:
        valid = levels['67']['rms_dbfs'] < levels['100']['rms_dbfs'] - 15 and levels['34']['rms_dbfs'] < levels['67']['rms_dbfs'] - 15
    report = {'serial': args.serial, 'source': 'emulator speaker output, after Android processing',
              'levels': levels, 'passed': valid, 'capture_errors': errors}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    output.with_suffix('.instrumentation.txt').write_text(''.join(transcript), encoding='utf-8')
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if valid else 1)


if __name__ == '__main__':
    main()
