# /android-app

Native Java consumer app (TRS Sec. 4.1, Sec. 5).

Baseline per TRS Sec. 5.3: compileSdk/targetSdk 36, minSdk 26, and a Java 21 LTS
toolchain since CR-002 (WP-02 slice S5). The toolchain and the language level are
different numbers on purpose: the app still COMPILES to Java 17
(`compileOptions`), because CR-002 needed a javac that can read bytecode-65 class
files and that is a property of the JDK, not of `-source`/`-target`.

## Status

Created in WP-01 slice S1. **No application code exists yet.** The empty-shell
build that must compile clean is delivered in WP-01 slice S7; the pure-JVM
`:protocol-conformance` module (JUnit, no Android framework) is delivered in
slice S5. Feature modules land from WP-02 onward per EDP Sec. 7.

## Rules

- Consumes generated constants from `/protocol/constants` only (TRS-REP-002,
  TRS-CONST-001). Hand-written constants are a defect.
- Layering and dependency rules of TRS Sec. 4.3 are enforced by architecture
  lint; a violating import fails the build (TRS-CODE-003).
