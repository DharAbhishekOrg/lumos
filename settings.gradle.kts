/*
 * Lumos - build / Gradle settings: the pure-JVM protocol conformance module.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-REP-001, TRS-TST-001
 * ADRs: -
 * Threading: any
 * Notes: Only :protocol-conformance is included today. It is deliberately a
 * PURE-JVM module with no Android plugin and no Android SDK requirement, so the
 * conformance gate runs on any JDK 17 host - including a Linux CI container
 * without an Android SDK licence (TRS Sec. 18.4). The Android application
 * modules join this build in slice S7.
 */

rootProject.name = "lumos"

include(":protocol-conformance")
project(":protocol-conformance").projectDir = file("android-app/protocol-conformance")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
