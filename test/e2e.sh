#!/bin/sh
# End to end with real YouTube: starts the real server, then runs the phone's own code against it.
# Needs Python 3, ffmpeg and a network that reaches YouTube. Run ./build.sh first.
set -e
cd "$(dirname "$0")/.."
T=build/test
PORT=${PORT:-8765}
CACHE=$(mktemp -d)
mkdir -p $T/api $T/shim $T/e2e
(cd $T/api && jar xf ../../../lib/j2me-api.jar javax && rm -f javax/microedition/io/Connector.class javax/microedition/io/file/FileSystemRegistry.class)
javac -nowarn -cp $T/api -d $T/shim $(find test/shim -name '*.java') 2>/dev/null
CP=build/classes:$T/shim:$T/api
javac -nowarn -cp $CP -d $T/e2e test/src/E2E.java
python3 server/tubeme_server.py --port $PORT --cache "$CACHE" > $T/e2e-server.log 2>&1 &
S=$!
trap 'kill $S 2>/dev/null; rm -rf "$CACHE"' EXIT
set +e
timeout 1500 java -Dtubeme.https=true -Dtubeme.readTimeout=300000 -cp $T/e2e:$CP tube.E2E http://127.0.0.1:$PORT
R=$?
echo "--- server log ---"
cat $T/e2e-server.log
exit $R
