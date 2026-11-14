#!/bin/sh
# End to end with real YouTube: starts the real server, then runs the phone's own code against it.
# Needs a JDK, Python 3, ffmpeg and a network that reaches YouTube. Works in Git Bash on Windows too.
set -e
cd "$(dirname "$0")/.."
T=build/e2e
PORT=${PORT:-8765}
PY=${PYTHON:-python3}
S=:
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) S=';' ;; esac
CACHE=$(mktemp -d)
rm -rf $T && mkdir -p $T/api $T/shim $T/app $T/cls
(cd $T/api && jar xf ../../../lib/j2me-api.jar javax && rm -f javax/microedition/io/Connector.class javax/microedition/io/file/FileSystemRegistry.class javax/microedition/rms/RecordStore.class)
javac -encoding UTF-8 -nowarn -cp $T/api -d $T/shim $(find test/shim -name '*.java')
javac -encoding UTF-8 -nowarn -cp "$T/shim$S$T/api" -d $T/app $(find src -name '*.java')
CP="$T/app$S$T/shim$S$T/api"
javac -encoding UTF-8 -nowarn -cp "$CP" -d $T/cls test/src/E2E.java
$PY server/tubeme_server.py ${SERVER_ARGS:-} --port $PORT --cache "$CACHE" > $T/server.log 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null; rm -rf "$CACHE"' EXIT
set +e
timeout 1500 java -Dtubeme.https=true -Dtubeme.readTimeout=300000 -cp "$T/cls$S$CP" tube.E2E http://127.0.0.1:$PORT
R=$?
echo "--- server log ---"
cat $T/server.log
exit $R
