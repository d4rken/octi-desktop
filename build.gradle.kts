import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
}

group = "eu.darken.octi.desktop"
// `version` is loaded from gradle.properties — single source of truth, bumped by release-prepare.yml.

// jpackage rejects non-numeric versions like "1.0.0-rc1" — strip the prerelease suffix for the
// packageVersion fed to MSI/DMG/DEB/RPM/AppImage. The full string still flows to BuildConfig.VERSION
// (and from there to the window title + DeviceMetadata.version sent to the server).
val rawVersion: String = project.version.toString()
val numericVersion: String = rawVersion.replace(Regex("-(rc|beta|dev)\\d*$"), "")

require(numericVersion.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))) {
    "After stripping prerelease suffix, version must be X.Y.Z numeric — got '$numericVersion' from '$rawVersion'"
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Compose Multiplatform desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.runtime)
    implementation(compose.components.resources)

    // Coroutines (Swing dispatcher for Compose desktop)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Serialization (match server's 1.10.0; app-main is 1.9.0 — pick newer, JSON wire is identical)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Datetime (match app-main)
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.0")

    // Ktor client (match server's 3.4.0 to minimize cross-version surprises)
    val ktorVersion = "3.4.0"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Ktor server (only used by the opt-in --enable-debug-rpc loopback endpoint)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // Okio (binary ByteString — matches app-main)
    implementation("com.squareup.okio:okio:3.10.2")

    // Tink (plain Java, wire-compatible with tink-android used by app-main)
    implementation("com.google.crypto.tink:tink:1.16.0")
    // Conscrypt-OpenJDK provides AES/GCM-SIV/NoPadding via the same code path the Android side
    // uses (tink-android also relies on Conscrypt). Pinning the same provider on both sides
    // eliminates a class of subtle interop bugs where BC's GCM-SIV output didn't match
    // Conscrypt's for keysets generated on Android — see the Crypto README for the history.
    implementation("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
    // BouncyCastle stays in as a fallback for hosts where Conscrypt's native lib can't load.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // JNA for OS keystore bindings (Keychain/DPAPI/libsecret)
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")

    // Argon2 KDF for passphrase fallback
    implementation("de.mkammerer:argon2-jvm:2.11")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.29")

    // Tests
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/source/buildConfig"))
}

// Generates src/.../BuildConfig.kt at build time so Kotlin code can read the version without
// re-declaring it. Single edit point lives in gradle.properties; this task propagates that to
// runtime. `inputs.property("version", ...)` makes the task invalidate correctly when the
// version changes — without it, Gradle would skip regeneration based on the unchanged output
// directory.
val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/buildConfig")
    inputs.property("version", rawVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().asFile.resolve("eu/darken/octi/desktop/BuildConfig.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            // Generated by build.gradle.kts — do not edit. Source of truth is gradle.properties `version=`.
            package eu.darken.octi.desktop

            internal object BuildConfig {
                const val VERSION: String = "$rawVersion"
            }
            """.trimIndent() + "\n",
        )
    }
}

tasks.named("compileKotlin") { dependsOn(generateBuildConfig) }

// Wire Kover reports into `check`. Report-only — no threshold gate. Generated BuildConfig
// excluded because it's a one-liner constant with no logic worth measuring.
kover {
    reports {
        filters {
            excludes {
                packages("eu.darken.octi.desktop.BuildConfig")
            }
        }
    }
}
tasks.named("check") {
    dependsOn("koverHtmlReport", "koverXmlReport")
}

compose.desktop {
    application {
        mainClass = "eu.darken.octi.desktop.MainKt"

        nativeDistributions {
            // Compose Desktop 1.7 validates every entry in targetFormats against the host OS at
            // configuration time — listing TargetFormat.AppImage on macOS throws "Unexpected
            // target format for MacOS: AppImage" even when only running `check`. Scope formats
            // to the host so each runner only declares what it can actually build.
            val hostFormats = with(OperatingSystem.current()) {
                when {
                    isMacOsX -> arrayOf(TargetFormat.Dmg)
                    isWindows -> arrayOf(TargetFormat.Msi)
                    isLinux -> arrayOf(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
                    else -> arrayOf(TargetFormat.Deb)
                }
            }
            targetFormats(*hostFormats)
            packageName = "Octi"
            // jpackage requires numeric X.Y.Z — see `numericVersion` above for suffix-stripping.
            packageVersion = numericVersion
            description = "Octi desktop companion — multi-device sync"
            copyright = "© 2026 d4rken-org"
            vendor = "d4rken-org"

            modules("java.naming", "jdk.crypto.ec", "java.management", "java.sql")

            linux {
                packageName = "octi"
                debMaintainer = "info@d4rken.eu"
                menuGroup = "Network"
                appCategory = "Network"
                // Note: Compose Desktop 1.7's DSL doesn't expose `Depends:` for .deb. Users
                // need `libsecret-tools` installed for OS-keystore credential storage; the
                // README documents this. When the passphrase fallback gets a real UI, the
                // missing-libsecret path becomes graceful and the dependency note moves to
                // "optional".
            }

            macOS {
                bundleID = "eu.darken.octi.desktop"
            }

            windows {
                menuGroup = "Octi"
                upgradeUuid = "9c4b3c1d-2a5d-4f8e-9a3b-7b6c5d4e3f2a"
            }
        }
    }
}
