#!/bin/sh
# Builds dist/TubeME.jar + dist/TubeME.jad.
# Needs a JDK (java + jar), ECJ and ProGuard 7. Override with ECJ / PROGUARD_JAR.
set -e
cd "$(dirname "$0")"
VERSION=$(tr -d '\r\n' < VERSION)
if [ -z "$ECJ" ]; then
  if [ -f tools/ecj.jar ]; then ECJ="java -jar tools/ecj.jar"; else ECJ=ecj; fi
fi
PROGUARD_JAR=${PROGUARD_JAR:-tools/proguard.jar}

rm -rf build/classes build/raw build/raw.jar dist
mkdir -p build/classes build/raw dist

echo "Compiling (Java 1.3 bytecode against CLDC 1.1 / MIDP 2.0 / JSR-75 / JSR-135)"
$ECJ -source 1.3 -target 1.1 -nowarn -bootclasspath lib/j2me-api.jar -d build/classes src/tube/*.java

sed "s/@VERSION@/$VERSION/" res/MANIFEST.MF > build/MANIFEST.MF
cp -r build/classes/tube build/raw/
cp res/icon.png build/raw/
(cd build/raw && jar cfm ../raw.jar ../MANIFEST.MF .)

echo "Preverifying with ProGuard"
java -jar "$PROGUARD_JAR" @proguard.pro

SIZE=$(wc -c < dist/TubeME.jar | tr -d ' ')
{
  grep -v -e '^Manifest-Version' -e '^Created-By' -e '^ ' build/MANIFEST.MF
  echo "MIDlet-Jar-URL: TubeME.jar"
  echo "MIDlet-Jar-Size: $SIZE"
} | grep -v '^$' > dist/TubeME.jad
(cd dist && rm -f TubeME-$VERSION.zip && zip -q TubeME-$VERSION.zip TubeME.jar TubeME.jad) 2>/dev/null || true
SERVER_VERSION=$(sed -n 's/^VERSION = "\(.*\)"/\1/p' server/tubeme_server.py)
(cd server && rm -f ../dist/TubeME-server-*.zip && zip -q ../dist/TubeME-server-$SERVER_VERSION.zip tubeme_server.py README.md test_server.py) 2>/dev/null || true
echo "Done: dist/TubeME.jar ($SIZE bytes), dist/TubeME.jad"
