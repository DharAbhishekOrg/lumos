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

        // The ABIs Android actually runs today. Lazysodium's AAR carries libsodium
        // for armeabi as well, and JNA's carries libjnidispatch for armeabi, mips
        // and mips64 - none of which any supported Android device has used for
        // years. Without this filter they are packaged anyway, half a megabyte of
        // native code for architectures that cannot run the app. Naming the four
        // is also the honest statement of what the crypto is proven on: the
        // emulator job runs x86_64.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

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
        getByName("androidTest") {
            // The committed golden vectors, packaged into the TEST apk so an
            // instrumented test can read them on a device. Pointed at the
            // repository's own directory rather than copied: ADR-004's rule that
            // generated artefacts are consumed where they are generated applies
            // to vectors exactly as it does to constants, and a copy under
            // src/androidTest/assets would be a second source of truth that
            // nothing keeps current. Asset paths are therefore relative to
            // /test-vectors, e.g. "crypto/recovery_record.json".
            assets.directories.add(rootProject.file("test-vectors").absolutePath)
        }
        getByName("main") {
            // The third-party notices the app SHIPS, packaged from the file that
            // is the record rather than from a copy of it. One of the conditions
            // the owner attached to approving Lazysodium's MPL-2.0 licence is
            // that its text reaches the user; a notices file that lived only in
            // docs/ would satisfy a reviewer and not that condition.
            assets.directories.add(rootProject.file("docs/legal").absolutePath)

            // Generated constants, message classes and error codes, consumed from
            // the generator's output directory exactly as :protocol-conformance
            // does. ADR-004: no constant is copied, anywhere, ever - the KDF info
            // strings the key hierarchy signs under come from here.
            java.directories.add(rootProject.file("protocol/gen/java").absolutePath)
        }
    }

    // The TOOLCHAIN is Java 21 since CR-002; the LANGUAGE LEVEL this module
    // compiles to stays 17, and the two are different questions.
    //
    // CR-002 exists so javac can READ lazysodium 5.2.0's bytecode-65 class
    // files, and that is a property of the JDK running the compiler - a Java 21
    // javac reads class file 65 whatever -source and -target say. Raising what
    // this module EMITS would be a second change: it puts class file 65 through
    // D8 and desugaring at minSdk 26, for no Java 21 language feature anybody
    // here uses. Deferred to WP-03 with that reasoning recorded in
    // evidence/wp02/closeout.md rather than taken quietly alongside the
    // toolchain move.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    lint {
        warningsAsErrors = true
        abortOnError = true

        // Lint skips test sources by default, and that default cost a CI cycle
        // in S4b: the instrumented vector loader called InputStream.readAllBytes,
        // which is API 33 while minSdk is 26. It ran green on a local API 34
        // emulator and threw NoSuchMethodError on the API 30 image the pipeline
        // uses. NewApi would have said so at build time, for free, if it had been
        // looking - so now it looks. Test code runs on the same minSdk as the app
        // and there is no reason to hold it to a weaker standard.
        checkTestSources = true

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

        // UnusedResources: slice S4c adds res/values/strings.xml - the first
        // user-facing copy in the application - and there is no activity to
        // reference it from, so lint reports all 52 strings as unused. That is
        // the same situation as MissingApplicationIcon above and it gets the
        // same treatment rather than the alternatives, which are worse: a
        // baseline file hides future real findings, and inventing a screen to
        // make the strings "used" is a stub shipped to please a linter.
        //
        // The copy is not unchecked in the meantime. OnboardingCopyTest parses
        // this file on the host, asserts every key the safety and onboarding
        // flows need is present, and enforces the plain-language rules written
        // at the top of it - including that the two confirmation phrases match
        // what DeliberateConfirmation compares against.
        //
        // RE-ENABLE when the app shell gains its first activity, at the same
        // time as MissingApplicationIcon.
        disable += "UnusedResources"

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

        // Aligned16KB is NOT disabled, and it was until CR-002 landed in slice
        // S5. lazysodium-android 5.1.0 shipped an arm64-v8a libsodium.so whose
        // LOAD segments were 4 KB aligned, which an Android 15 device with 16 KB
        // pages cannot map at all; the check was disabled narrowly so the defect
        // did not masquerade as absent. 5.2.0 aligns every .so at 16 KB, so the
        // check runs and passes, and its passing is the evidence. If it ever
        // fails again the answer is a dependency, not another disable.
    }

    testOptions {
        unitTests.all {
            // JUnit 5, the same platform :protocol-conformance uses. One test
            // framework across the JVM half of the repository means one set of
            // report shapes for the traceability collector to parse.
            it.useJUnitPlatform()

            // Slice S4c's copy and legal-document tests read files that are not
            // on the test classpath: docs/legal, which the app ships as assets,
            // and res/values/strings.xml. Both are repository artefacts, so the
            // tests are given the repository root the same way
            // :protocol-conformance is given it for the golden vectors, rather
            // than walking up from a working directory Gradle is free to change.
            it.systemProperty("lumos.repoRoot", rootProject.projectDir.absolutePath)
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
    // Gson reads the committed vector files in JVM tests: the android.jar
    // org.json on the unit-test classpath is the stubbed not-mocked one. Same
    // artifact, version and licence as the conformance module's approved use.
    testImplementation(libs.gson)

    // Room, Apache-2.0. The Android production store, behind the SqlDatabase
    // seam (owner ruling 2026-08-14). room-compiler is an annotation processor:
    // it generates the DAO implementations and, with the schema-location
    // argument below, exports the schema JSON that RoomSchemaParityTest
    // compares against the canonical DDL.
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)

    // Lazysodium-android (MPL-2.0) bundling libsodium (ISC), and JNA (Apache-2.0),
    // adopted per the owner's approval of 2026-08-18. See gradle/libs.versions.toml
    // for the licence conditions and tools/toolchain/toolchain-manifest.json for
    // the record. This is the first dependency the project SHIPS under a copyleft
    // licence, and LibsodiumRecoveryCrypto is the only file that touches it.
    //
    // One exclusion: lazysodium's POM asks for the JNA *jar*, which carries
    // desktop natives and no libjnidispatch.so for Android. The aar variant under
    // the same coordinates is the Android one, and it is declared explicitly so
    // the Java half and the native half come from a single pinned artefact - JNA
    // refuses to load if the two disagree about their version.
    implementation(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(variantOf(libs.jna) { artifactType("aar") })

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
