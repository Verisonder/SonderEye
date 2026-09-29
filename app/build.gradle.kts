plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.verisonder.sondereye"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.verisonder.sondereye"
        // Same floor as SonderFlow. The globe needs OpenGL ES 3.0, which every phone at this level has.
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            // Supplied by CI from repository secrets. Absent locally, so the release build
            // falls back to the debug key. Values are trimmed: a pasted secret picks up
            // invisible whitespace and fails as a wrong alias.
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")?.trim()
            if (!storeFilePath.isNullOrEmpty()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")?.trim()
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")?.trim()
                // PKCS12: the key password always equals the store password.
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")?.trim()
                    ?: System.getenv("RELEASE_STORE_PASSWORD")?.trim()
            }
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
            signingConfig = if (System.getenv("RELEASE_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.testLogging {
                    events("passed", "failed", "skipped")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // WebSocket client for the live ship stream.
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
