"""Exercise the real emulator input pipeline; adb input keyevent bypasses key filters."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import threading
import re

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5558')
parser.add_argument('--output', default='app/build/reports/hardware-keys.txt')
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    sys.exit('Emulator only.')
sdk = Path(os.environ['ANDROID_HOME'])
adb = str(sdk / ('platform-tools/adb.exe' if os.name == 'nt' else 'platform-tools/adb'))
base = [adb, '-s', args.serial]
# Privileged injection is test-only, inside an SDK emulator. The released app needs no root.
subprocess.run(base + ['root'], check=True, capture_output=True, timeout=10)
subprocess.run(base + ['wait-for-device'], check=True, timeout=15)
if subprocess.check_output(base + ['shell', 'id', '-u'], text=True).strip() != '0':
    sys.exit('SDK userdebug emulator required for kernel-level test input.')
devices = subprocess.check_output(base + ['shell', 'getevent', '-lp'], text=True, timeout=10)
blocks = re.findall(r'add device \d+: (/dev/input/event\d+)\s*(.*?)(?=add device|\Z)', devices, re.S)
keyboard = next((path for path, info in blocks if 'KEY_VOLUMEUP' in info and 'KEY_VOLUMEDOWN' in info), None)
if keyboard is None:
    sys.exit('No emulated hardware volume-key device found.')
command = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'hardwareKeyBridge', 'true', '-e', 'class',
                 'com.abhayshah.volumecontrol.ExperienceTest#physicalKeysAdjustFineLevelFromAnotherAppAndReleaseOnStop',
                 'com.abhayshah.volumecontrol.test/androidx.test.runner.AndroidJUnitRunner']
proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
watchdog = threading.Timer(90, proc.kill)
watchdog.start()
lines = []
try:
    for line in proc.stdout:
        lines.append(line)
        if line.startswith('INSTRUMENTATION_STATUS: hardware_key='):
            key = line.strip().split('=', 1)[1]
            code = {'UP': '115', 'DOWN': '114'}[key]
            # Unlike adb input keyevent, these pass through the kernel input and accessibility filter.
            for event in [('1', code, '1'), ('0', '0', '0'), ('1', code, '0'), ('0', '0', '0')]:
                subprocess.run(base + ['shell', 'sendevent', keyboard] + list(event), check=True, capture_output=True, timeout=5)
    proc.wait(timeout=5)
finally:
    watchdog.cancel()
text = ''.join(lines)
output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(text, encoding='utf-8')
print(text)
sys.exit(0 if proc.returncode == 0 and 'OK (1 test)' in text and 'FAILURES' not in text else 1)
