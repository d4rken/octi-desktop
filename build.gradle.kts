import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}

group = "eu.darken.octi.desktop"
version = "0.0.1-dev"

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
}

compose.desktop {
    application {
        mainClass = "eu.darken.octi.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Octi"
            packageVersion = "1.0.0"
            description = "Octi desktop companion — multi-device sync"
            copyright = "© 2026 d4rken-org"
            vendor = "d4rken-org"

            modules("java.naming", "jdk.crypto.ec", "java.management", "java.sql")

            linux {
                packageName = "octi"
                debMaintainer = "info@d4rken.eu"
                menuGroup = "Network"
                appCategory = "Network"
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
