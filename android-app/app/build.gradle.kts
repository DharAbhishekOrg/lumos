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
}

// ---- TRS-CODE-006: Checkstyle over the Android module ----------------------
//
// The Gradle checkstyle plugin creates tasks from the `java` plugin's source
// sets, which an Android module does not have, so the task is registered by
// hand over this module's Java sources. SpotBugs is deliberately NOT wired here
// yet and the reason is worth stating rather than leaving as an omission:
// SpotBugs analyses compiled bytecode, and this module has no main source set
// at all - it is still the empty WP-01 shell. A SpotBugs task over an empty
// class directory would report success while checking nothing, which is exactly
// the kind of green this project has been bitten by three times.
//
// TestAppModuleWiresSpotbugsOnceItHasProductionCode in tools/staticanalysis
// fails the moment android-app/app/src/main/java appears, so the omission
// cannot outlive the condition that justifies it.
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
