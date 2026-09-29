import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

val appVersion = "1.0.1"

compose.desktop {
    application {
        mainClass = "com.verisonder.sondereye.ui.MainKt"
        // A modest Java heap: the tiles live in native memory, and the heap is only the app's data.
        jvmArgs += listOf("-Xmx768m", "-XX:+UseG1GC", "-XX:MaxDirectMemorySize=256m", "-Dsun.java2d.uiScale.enabled=true")
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "SonderEye"
            packageVersion = appVersion
            // Windows shows this as the program's name (Task Manager, the taskbar): the name, then.
            description = "SonderEye"
            vendor = "Verisonder"
            copyright = "GPL-3.0-only"
            licenseFile.set(rootProject.file("../LICENSE"))
            // Only what the app uses: a smaller bundled runtime.
            modules("java.net.http", "java.prefs", "java.desktop", "jdk.crypto.ec", "java.naming")
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "SonderEye"
                shortcut = true
                menu = true
                perUserInstall = true
                dirChooser = true
                // Stays the same forever: later versions install over this one.
                upgradeUuid = "5b6f4a61-6f0e-4c1b-9d2e-5e7e2a3c8d10"
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
