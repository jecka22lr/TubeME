#!/bin/sh
# Tour of history, favourites and saved videos. Usage: tour-lists.sh path/to/TubeME.jar outdir
set -e
JAR=$(readlink -f "$1"); OUT=${2:-shots}; mkdir -p "$OUT"; OUT=$(readlink -f "$OUT")
D="$(cd "$(dirname "$0")" && pwd)"; cd "$D"
pkill -f mock.py 2>/dev/null || true; sleep 1
(python3 ../mock.py 150 >/tmp/tubemock.log 2>&1 &)
rm -rf home tour /tmp/tubefs; mkdir -p home tour
cp "$JAR" tour/TubeME.jar
{ cat "$(dirname "$JAR")/TubeME.jad"; echo "TubeME-List-URL: http://127.0.0.1:18081/notPipe.json"; } > tour/TubeME.jad
./run-emu.sh "$D/tour/TubeME.jad"; export DISPLAY=:99; setxkbmap us
E=./emu.py
$E shot $OUT/l01-menu.png
$E fire; sleep 1; $E type "gatos"; $E soft right; sleep 5
$E key Down; $E fire; sleep 4; $E shot $OUT/l02-video.png
$E soft left; sleep 0.7; $E shot $OUT/l03-video-menu.png
$E key Down 7; $E fire; sleep 1
$E soft left; sleep 0.7; $E shot $OUT/l04-menu-after-fav.png
$E fire; sleep 8; $E shot $OUT/l05-saved.png
$E soft left; sleep 1; $E shot $OUT/l06-after-saved.png
$E soft left; sleep 0.5; $E key Down 2; $E fire; sleep 1; $E shot $OUT/l07-results.png
$E soft left; sleep 1; $E shot $OUT/l08-menu.png
$E key Down 4; $E fire; sleep 1; $E shot $OUT/l09-history.png
$E soft left; sleep 0.5; $E shot $OUT/l10-history-menu.png
$E key Down 0; $E fire; sleep 1; $E shot $OUT/l11-back-menu.png
$E key Up 1; $E fire; sleep 1; $E shot $OUT/l12-favs.png
$E soft left; sleep 0.5; $E fire; sleep 1
$E key Up 1; $E fire; sleep 4; $E shot $OUT/l13-saved-list.png
$E soft left; sleep 0.5; $E shot $OUT/l14-saved-menu.png
