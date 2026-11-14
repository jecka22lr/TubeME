#!/bin/sh
# Desktop tests against the mock servers. Run ./build.sh first (uses build/classes).
set -e
cd "$(dirname "$0")/.."
T=build/test
rm -rf $T /tmp/tubefs
mkdir -p $T/api $T/shim $T/cls
(cd $T/api && jar xf ../../../lib/j2me-api.jar javax && rm javax/microedition/io/Connector.class javax/microedition/io/file/FileSystemRegistry.class javax/microedition/rms/RecordStore.class)
javac -nowarn -cp $T/api -d $T/shim $(find test/shim -name '*.java') 2>/dev/null
CP=build/classes:$T/shim:$T/api
javac -nowarn -cp $CP -d $T/cls test/src/*.java 2>/dev/null
python3 test/mock.py 45 > $T/mock.log 2>&1 &
M=$!
sleep 1
timeout 60 java -cp $T/cls:$CP tube.Tests > $T/tests.log 2>&1 || { cat $T/tests.log; tail -5 $T/mock.log; exit 1; }
tail -1 $T/tests.log
wait $M || { cat $T/mock.log; exit 1; }
grep MOCK $T/mock.log
rm -rf /tmp/tubefs
echo "ALL TESTS PASSED"
