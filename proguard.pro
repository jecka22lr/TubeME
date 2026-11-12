# Preverify for CLDC (StackMap attributes) and shrink/obfuscate.
# Optimization stays off: the shipped jar should behave exactly like the tested classes.
-injars build/raw.jar
-outjars dist/TubeME.jar
-libraryjars lib/j2me-api.jar
-microedition
-target 1.3
-dontoptimize
-dontusemixedcaseclassnames
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-keep public class tube.TubeMIDlet
-dontnote
