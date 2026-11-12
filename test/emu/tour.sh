#!/bin/sh
# Screenshot tour of TubeME against test/mock.py. Usage: tour.sh path/to/TubeME.jar outdir
set -e
JAR=$(readlink -f "$1"); OUT=${2:-shots}; mkdir -p "$OUT"; OUT=$(readlink -f "$OUT")
D="$(cd "$(dirname "$0")" && pwd)"; cd "$D"
pkill -f mock.py 2>/dev/null || true; sleep 1
(python3 ../mock.py 120 >/tmp/tubemock.log 2>&1 &)
# same jar, but a JAD that points it at the mock servers
rm -rf home tour /tmp/tubefs; mkdir -p home tour
cp "$JAR" tour/TubeME.jar
{ cat "$(dirname "$JAR")/TubeME.jad"; echo "TubeME-List-URL: http://127.0.0.1:18081/notPipe.json"; } > tour/TubeME.jad
./run-emu.sh "$D/tour/TubeME.jad"; export DISPLAY=:99; setxkbmap us
E=./emu.py
$E shot $OUT/01-menu.png
$E fire; sleep 1; $E type "gatos"; $E shot $OUT/02-search.png
$E soft right; sleep 5; $E shot $OUT/03-results.png
$E key Down; $E fire; sleep 4; $E shot $OUT/04-video.png
$E key Down 6; $E shot $OUT/05-video-scrolled.png
$E soft right; sleep 0.7; $E shot $OUT/06-video-menu.png
