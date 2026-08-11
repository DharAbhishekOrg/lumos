/*
 * Lumos - build / Gradle settings: conformance runner and the Android app shell.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-REP-001, TRS-TST-001
 * ADRs: -
 * Threading: any
 * Notes: :protocol-conformance is a PURE-JVM module and must stay runnable on a
 * host with no Android SDK (TRS Sec. 18.4). Gradle configures every included
 * project, and configuring :app would apply the Android plugin and demand an
 * SDK - so :app is included only when -PlumosAndroid=true is passed. The
 * conformance job therefore needs a JDK and nothing else, and the property makes
 * that guarantee explicit rather than incidental.
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "lumos"

include(":protocol-conformance")
project(":protocol-conformance").projectDir = file("android-app/protocol-conformance")

val withAndroid = (providers.gradleProperty("lumosAndroid").orNull ?: "false").toBoolean()
if (withAndroid) {
    include(":app")
    project(":app").projectDir = file("android-app/app")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
