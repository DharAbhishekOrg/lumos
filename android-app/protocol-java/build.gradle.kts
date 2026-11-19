/*
 * Lumos - android-app/protocol-java / Shared pure-JVM protocol library.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-015, TRS-TST-001, TRS-REP-002, TRS-CODE-006
 * ADRs: ADR-004
 * Threading: any
 * Notes: WP-03 S2 extracts this module so the Java protocol code exists ONCE.
 * Until now the frame codec and JCA primitives lived inside :protocol-conformance,
 * where the app cannot reach them - and a BLE adapter that re-implemented the
 * frame codec would be exactly the divergence the conformance suite exists to
 * catch, growing inside the codebase instead of between platforms. This module
 * is consumed by BOTH :protocol-conformance (tests it against the golden
 * vectors) and :app (ships it inside the APK), so the bytes the vectors hold
 * green are the bytes the radio sends.
 *
 * NO Android plugin, NO Android framework dependency, NO Android SDK, no
 * dependencies at all: plain JDK, so the conformance gate still runs on a bare
 * JDK container (TRS Sec. 18.4). The generated constants are consumed from the
 * generator's output directory exactly as before (ADR-004: never copied).
 */

plugins {
    `java-library`
    checkstyle
    alias(libs.plugins.spotbugs)
}

java {
    toolchain {
        // Java 21 toolchain since CR-002 (WP-02 S5).
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    // EMITS Java 17 bytecode while BUILDING on the 21 toolchain - the same
    // split :app makes, and for the same recorded reason (WP-02 closeout):
    // CR-002 raised what the compiler runs on, not what D8 desugars at
    // minSdk 26. This module ships in the APK, so it holds the app's line.
    options.release.set(17)
}

sourceSets {
    main {
        java.srcDirs(
            "src/main/java",
            // Generated constants, message classes, error codes and class
            // limits, consumed where they are generated (TRS-REP-002, ADR-004).
            // This srcDir moved here from :protocol-conformance with the code
            // that uses it; consumers now get both through one dependency.
            rootProject.file("protocol/gen/java"),
        )
    }
}

// ---- TRS-CODE-006: Checkstyle + SpotBugs, zero warnings --------------------
//
// Same stack and same ceilings as :protocol-conformance. The generated register
// is on this module's source path (see above) and is excluded from analysis for
// the reason recorded there: a finding in generated code names a defect nobody
// may fix in place.
val generatedJavaDir = rootProject.file("protocol/gen/java")

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = rootProject.file("tools/staticanalysis/checkstyle/checkstyle.xml")
    maxWarnings = 0
    maxErrors = 0
    isIgnoreFailures = false
}

tasks.withType<Checkstyle>().configureEach {
    exclude { it.file.toPath().startsWith(generatedJavaDir.toPath()) }
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

spotbugs {
    toolVersion = libs.versions.spotbugs.get()
    reportLevel.set(com.github.spotbugs.snom.Confidence.LOW)
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    excludeFilter.set(rootProject.file("tools/staticanalysis/spotbugs/exclude.xml"))
    ignoreFailures.set(false)
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("xml") { required.set(true) }
    reports.create("html") { required.set(true) }
}

// One task CI can run for this module's whole Java analysis stack. The module
// has no test sources - its tests ARE :protocol-conformance's vector runs.
tasks.register("staticAnalysis") {
    group = "verification"
    description = "TRS-CODE-006: Checkstyle and SpotBugs over the shared protocol library."
    dependsOn(
        "checkstyleMain",
        "spotbugsMain",
    )
}
