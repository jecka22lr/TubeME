#!/bin/sh
# Start MicroEmulator (with a window manager, so keys reach it) on :99. Usage: run-emu.sh App.jar
pkill -f "org.microemu.app.Main" 2>/dev/null; pkill openbox 2>/dev/null; pkill Xvfb 2>/dev/null; sleep 0.5
Xvfb :99 -screen 0 480x800x24 >/tmp/xvfb.log 2>&1 &
sleep 1
export DISPLAY=:99
openbox >/tmp/openbox.log 2>&1 &
sleep 1
CP=$(dirname "$0")/build/classes:/usr/share/java/asm.jar:/usr/share/java/asm-commons.jar
java -Duser.home=$(dirname "$0")/home -cp $CP org.microemu.app.Main \
  --impl org.microemu.cldc.file.FileSystem "$1" >/tmp/emu.log 2>&1 &
sleep 6
W=$(xdotool search --name "MicroEmulator" | head -1)
xdotool windowactivate --sync $W 2>/dev/null; xdotool windowmove $W 0 0
echo $W > /tmp/emu.win
