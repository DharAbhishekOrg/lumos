/*
 * Lumos - android-app/protocol-conformance / Pure-JVM golden-vector conformance runner.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001, TRS-PROTO-015, SRS-NFR-CMP-004
 * ADRs: ADR-001, ADR-004
 * Threading: any
 * Notes: NO Android plugin, NO Android framework dependency, NO Android SDK.
 * TRS Sec. 18.4 requires this module to be pure JVM so the conformance gate runs
 * on a plain JDK 17 container. It CONSUMES the committed /test-vectors files and
 * never generates them (single-generator rule); the generated protocol constants
 * and error codes come from /protocol/gen/java, so no constant is hand-written.
 */

plugins {
    `java-library`
    checkstyle
    alias(libs.plugins.spotbugs)
}

java {
    toolchain {
        // TRS Sec. 5.3 mandates a Java 17 toolchain.
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

sourceSets {
    main {
        java.srcDirs(
            "src/main/java",
            // Generated constants, message classes, error codes and class limits.
            // Consuming them from the generator's output directory is what makes
            // TRS-REP-002 true of a real build rather than of a copy.
            rootProject.file("protocol/gen/java"),
        )
    }
}

dependencies {
    // Gson 2.11.0, Apache-2.0. Test-scope JSON reader for the committed vector
    // files. The JDK ships no JSON parser and hand-rolling one inside a
    // conformance runner would put unreviewed parsing between the vectors and
    // the assertions. Apache-2.0 is a preferred licence under AGENTS.md Sec. 3.
    testImplementation(libs.gson)

    // JUnit 5, EPL-2.0. The JVM test framework TRS Sec. 18.4 names for this
    // module. EPL-2.0 is weak copyleft limited to the library's own files and
    // is not linked into any shipped artefact - this is a test-only dependency.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// ---- TRS-CODE-006: Checkstyle + SpotBugs, zero warnings --------------------
//
// The generated register at protocol/gen/java is on this module's main source
// path (see sourceSets above) so that no constant is ever copied. It is
// therefore also, by default, on the analysers' input path - and it must not be:
// the files are overwritten on every generation and byte-compared by the
// constants gate, so a finding there names a defect nobody may fix in place.
// Both analysers exclude it, Checkstyle by source directory and SpotBugs by
// package, and both leave com.lumos.protocol.conformance fully analysed.
val generatedJavaDir = rootProject.file("protocol/gen/java")

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = rootProject.file("tools/staticanalysis/checkstyle/checkstyle.xml")
    // TRS-CODE-006 is a ZERO-warning gate, and Checkstyle's own default is to
    // tolerate an unbounded number of warnings. Both ceilings are pinned at
    // zero so the requirement is enforced by the build rather than by reading
    // the report.
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
    // `low` reports everything SpotBugs is willing to say. A higher threshold
    // would let the gate pass while findings it chose not to print still exist,
    // which is the shape of green this project has already been bitten by.
    reportLevel.set(com.github.spotbugs.snom.Confidence.LOW)
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    excludeFilter.set(rootProject.file("tools/staticanalysis/spotbugs/exclude.xml"))
    ignoreFailures.set(false)
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("xml") { required.set(true) }
    reports.create("html") { required.set(true) }
}

// One task a human (and CI) can run for the whole Java analysis stack.
tasks.register("staticAnalysis") {
    group = "verification"
    description = "TRS-CODE-006: Checkstyle and SpotBugs over every Java source set."
    dependsOn(
        "checkstyleMain",
        "checkstyleTest",
        "spotbugsMain",
        "spotbugsTest",
    )
}

tasks.test {
    useJUnitPlatform()
    // The vector files are addressed relative to the repository root.
    systemProperty("lumos.repoRoot", rootProject.projectDir.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
