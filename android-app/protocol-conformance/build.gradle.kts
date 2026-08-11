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
    testImplementation("com.google.code.gson:gson:2.11.0")

    // JUnit 5, EPL-2.0. The JVM test framework TRS Sec. 18.4 names for this
    // module. EPL-2.0 is weak copyleft limited to the library's own files and
    // is not linked into any shipped artefact - this is a test-only dependency.
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
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
