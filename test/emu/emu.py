#!/usr/bin/env python3
"""Drive MicroEmulator on :99. Usage: emu.py type TEXT | key NAME [n] | soft left|right | fire | shot FILE | clear [n]"""
import subprocess, sys, os, time
os.environ['DISPLAY'] = ':99'
SYM = {'.': 'period', ' ': 'space', '-': 'minus', '_': 'underscore', '/': 'slash', ':': 'colon', '@': 'at'}
def xd(*a): subprocess.run(['xdotool'] + list(a), check=False)
cmd = sys.argv[1]
if cmd == 'type':
    for ch in sys.argv[2]:
        xd('key', SYM.get(ch, ch)); time.sleep(0.03)
elif cmd == 'key':
    for _ in range(int(sys.argv[3]) if len(sys.argv) > 3 else 1):
        xd('key', sys.argv[2]); time.sleep(0.08)
elif cmd == 'clear':
    for _ in range(int(sys.argv[2]) if len(sys.argv) > 2 else 30): xd('key', 'BackSpace')
elif cmd == 'soft':  # click the skin's soft buttons
    x = 45 if sys.argv[2] == 'left' else 183
    xd('mousemove', str(x), '340', 'click', '1')
elif cmd == 'fire':
    xd('mousemove', '114', '340', 'click', '1')
elif cmd == 'shot':
    time.sleep(0.4)
    subprocess.run(['import', '-window', 'root', '-crop', '228x528+0+0', sys.argv[2]])
