#!/bin/sh
# One-time setup: builds MicroEmulator from source (no Maven needed) for screenshot testing on Linux.
# Needs: apt-get install -y xvfb xdotool imagemagick libasm-java openbox x11-xkb-utils
set -e
cd "$(dirname "$0")"
[ -d microemu ] || git clone https://github.com/tisoft/microemu.git microemu
(cd microemu && git checkout -q bf5dffebca4e8fef6db9e92dcaa70f8699e03fbb)
M=microemu/microemulator
# MicroEmulator was written for ASM 3; port the two visitors to the ASM 4+ API (keep api=ASM4 so the
# old 4-argument visitMethodInsn still gets called, otherwise Timer rewriting breaks verification)
V=$M/microemu-javase/src/main/java/org/microemu/app/classloader
sed -i 's/import org.objectweb.asm.ClassAdapter;/import org.objectweb.asm.ClassVisitor;\nimport org.objectweb.asm.Opcodes;/; s/extends ClassAdapter/extends ClassVisitor/; s/super(cv);/super(Opcodes.ASM4, cv);/' $V/ChangeCallsClassVisitor.java
sed -i 's/import org.objectweb.asm.MethodAdapter;/import org.objectweb.asm.MethodVisitor;/; s/extends MethodAdapter implements/extends MethodVisitor implements/; s/super(mv);/super(ASM4, mv);/' $V/ChangeCallsMethodVisitor.java
rm -rf build && mkdir -p build/classes
find $M/microemu-cldc/src/main $M/microemu-midp/src/main $M/microemu-javase/src/main $M/microemu-javase-swing/src/main \
     $M/microemu-extensions/microemu-jsr-75/src/main -name '*.java' > build/src.txt
javac -nowarn --release 8 -encoding ISO-8859-1 -cp /usr/share/java/asm.jar:/usr/share/java/asm-commons.jar -d build/classes @build/src.txt
for m in microemu-cldc microemu-midp microemu-javase microemu-javase-swing microemu-extensions/microemu-jsr-75; do
  [ -d $M/$m/src/main/resources ] && cp -r $M/$m/src/main/resources/. build/classes/
done
echo "MicroEmulator built in test/emu/build/classes"
