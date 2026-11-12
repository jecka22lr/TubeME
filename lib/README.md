# lib/j2me-api.jar

Compile-time stubs for CLDC 1.1, MIDP 2.0, JSR-75 (FileConnection) and the JSR-135 video
controls. Every method throws; it only exists so the compiler knows exactly what the phone provides.

Same jar as SlskME's (generated from MicroEmulator's API signature files, LGPL 2.1 / Apache 2.0),
plus `GUIControl` and `VideoControl` from `tools/stubsrc`. Using a real WTK or Nokia SDK jar works too.
