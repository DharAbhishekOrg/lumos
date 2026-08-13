/*
 * Lumos - android-app/app / Empty Android application shell.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-REP-001, TRS-REP-004, TRS-REP-005
 * ADRs: ADR-004
 * Threading: main-safe
 * Notes: An EMPTY shell: no activity, no UI, no feature code - WP-01 forbids all
 * of it. What this module proves is that the Android toolchain resolves and
 * compiles against the TRS Sec. 5.3 baseline (compileSdk/targetSdk 36, minSdk 26,
 * Java 17) and that the four environments of TRS-REP-004 are separated by
 * construction. Feature modules arrive from WP-02.
 */

plugins {
    alias(libs.plugins.android.application)
    checkstyle
    alias(libs.plugins.spotbugs)
}

android {
    namespace = "com.lumos.app"
    // TRS Sec. 5.3 baseline.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumos.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.0-dev"

        // Instrumented tests run the Room half on a real Android runtime. The
        // JVM seam tests stay the fast path; these exist so TRS-AND-004 rests
        // on execution rather than on assertion (owner ruling 2026-08-14).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room exports the schema it derived from the entities. The export is
        // COMMITTED and compared against the canonical LocalSchema DDL by
        // RoomSchemaParityTest, so adopting Room cannot fork the schema: Room's
        // own compiler output is the cross-check, and it is produced on any
        // host without a device.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    // TRS-REP-004: development, test, staging and production are separated by
    // endpoint, key and logging behaviour. The shell carries the separation; the
    // values arrive with the features that need them.
    //
    // The flavour is named "qa" rather than "test" because AGP reserves the
    // "test" prefix for its own instrumentation variants; LUMOS_ENVIRONMENT
    // carries the TRS-REP-004 name so the requirement's vocabulary survives.
    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            buildConfigField("String", "LUMOS_ENVIRONMENT", "\"development\"")
        }
        create("qa") {
            dimension = "environment"
            applicationIdSuffix = ".qa"
            buildConfigField("String", "LUMOS_ENVIRONMENT", "\"test\"")
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            buildConfigField("String", "LUMOS_ENVIRONMENT", "\"staging\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "LUMOS_ENVIRONMENT", "\"production\"")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signingConfig: production signing material never exists in
            // source or CI (TRS-REP-005). Release signing is owner-executed.
        }
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // Generated constants, message classes and error codes, consumed from
            // the generator's output directory exactly as :protocol-conformance
            // does. ADR-004: no constant is copied, anywhere, ever - the KDF info
            // strings the key hierarchy signs under come from here.
            java.directories.add(rootProject.file("protocol/gen/java").absolutePath)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    lint {
        warningsAsErrors = true
        abortOnError = true

        // Android lint reports OldTargetApi and GradleDependency because SDK 37
        // exists. TRS Sec. 5.3 pins the baseline at 36, and a specification value
        // is not something a build file may raise on lint's advice - that is a
        // change request against the TRS, not a lint fix. Both checks are
        // disabled HERE, narrowly, rather than by relaxing warningsAsErrors,
        // which would silence everything else too.
        //
        // Dependency freshness is not lost: TRS-CICD-006 puts it under reviewed
        // update automation, which is the controlled path for moving a pinned
        // baseline.
        // MissingApplicationIcon: the shell declares no launchable component, so
        // there is nothing for an icon to label. The launcher icon is a WP-10
        // design deliverable, and dropping in a placeholder now is exactly the
        // kind of stub that later ships unnoticed (AGENTS.md Sec. 3). Re-enable
        // this check when the app shell gains an activity in WP-02.
        disable += setOf("OldTargetApi", "GradleDependency", "MissingApplicationIcon")

        // BidiSpoofing: AGP 9.3.1's bundled lint CRASHES (NoSuchMethodError in
        // JavaDocParser.parseDataItem) when this detector forces a parse of any
        // Javadoc containing block tags - and TRS-CODE-002 requires @param and
        // @return on every public method, so the two cannot coexist. The only
        // AGP releases past 9.3.1 are alphas, which the pinned-toolchain rule
        // rejects. Disabled narrowly, not by relaxing warningsAsErrors; what is
        // lost is Trojan-Source bidi detection in Java sources, and Checkstyle
        // still rejects non-ASCII surprises indirectly via its parse.
        //
        // TRACKING (owner ruling 2026-08-14: keep stable AGP, the disable
        // stands with its reason). RE-ENABLE at the first STABLE AGP release
        // whose bundled lint parses Javadoc block tags without crashing.
        // Re-check when androidGradlePlugin in gradle/libs.versions.toml is
        // next raised: delete this line, run :app:lintDevDebug, and if it
        // survives a file with @param/@return the detector is fixed.
        disable += "BidiSpoofing"
    }

    testOptions {
        unitTests.all {
            // JUnit 5, the same platform :protocol-conformance uses. One test
            // framework across the JVM half of the repository means one set of
            // report shapes for the traceability collector to parse.
            it.useJUnitPlatform()
            it.testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }
}

dependencies {
    // JUnit 5, EPL-2.0. Test scope only, never linked into an APK. Already
    // declared for :protocol-conformance in tools/toolchain/toolchain-manifest.json.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // sqlite-jdbc, Apache-2.0, test scope only - never in an APK. It puts a REAL
    // SQLite engine behind the SqlDatabase seam so the migration DDL and the
    // encryption-at-rest assertions execute against the engine family that runs
    // them on a device, instead of against a fake that would only ever agree
    // with its author (WP-01 closeout Sec. 5.2).
    testImplementation(libs.sqlite.jdbc)

    // Room, Apache-2.0. The Android production store, behind the SqlDatabase
    // seam (owner ruling 2026-08-14). room-compiler is an annotation processor:
    // it generates the DAO implementations and, with the schema-location
    // argument below, exports the schema JSON that RoomSchemaParityTest
    // compares against the canonical DDL.
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)

    // Instrumented-test scope only; never in a release APK.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}

// ---- TRS-CODE-006: Checkstyle + SpotBugs over the Android module -----------
//
// The Gradle checkstyle and spotbugs plugins create their tasks from the `java`
// plugin's source sets, which an Android module does not have, so both tasks
// are registered by hand. Checkstyle reads the sources; SpotBugs analyses the
// devDebug variant's bytecode - one variant is enough, because the Java sources
// are identical across flavours and only BuildConfig differs.
//
// The WP-01 shell deliberately did NOT wire SpotBugs here, because a SpotBugs
// task over an empty class directory reports success while checking nothing.
// That exemption was conditional on the module being empty, enforced by
// TestAppModuleWiresSpotbugsOnceItHasProductionCode - and this slice adds the
// first production sources (the key hierarchy), so the task arrives with them.
checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = rootProject.file("tools/staticanalysis/checkstyle/checkstyle.xml")
    maxWarnings = 0
    maxErrors = 0
    isIgnoreFailures = false
}

tasks.register<Checkstyle>("checkstyleApp") {
    group = "verification"
    description = "TRS-CODE-006: Checkstyle over every Java source in the Android module."
    source = fileTree("src") { include("**/*.java") }
    classpath = files()
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

spotbugs {
    toolVersion = libs.versions.spotbugs.get()
    // Mirrors :protocol-conformance: `low` + `max` is the setting under which
    // SpotBugs reports everything it is willing to say.
    reportLevel.set(com.github.spotbugs.snom.Confidence.LOW)
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    excludeFilter.set(rootProject.file("tools/staticanalysis/spotbugs/exclude.xml"))
    ignoreFailures.set(false)
}

tasks.register<com.github.spotbugs.snom.SpotBugsTask>("spotbugsApp") {
    group = "verification"
    description = "TRS-CODE-006: SpotBugs over the Android module's devDebug bytecode."
    dependsOn("compileDevDebugJavaWithJavac")
    classDirs = files(layout.buildDirectory.dir("intermediates/javac/devDebug/compileDevDebugJavaWithJavac/classes"))
    sourceDirs = files("src/main/java")
    // android.jar, so SpotBugs can resolve the framework types instead of
    // degrading its analysis around gaps. AGP 9 removed bootClasspath and
    // sdkDirectory from the android {} DSL; the components extension is the
    // supported way to reach the SDK location now.
    auxClassPaths = files(
        androidComponents.sdkComponents.sdkDirectory.map {
            it.file("platforms/android-${android.compileSdk}/android.jar")
        }
    )
    reports.create("xml") { required.set(true) }
    reports.create("html") { required.set(true) }
}
