// SPDX-License-Identifier: Apache-2.0

plugins {
    // No org.jetbrains.kotlin.android here: AGP 9 has built-in Kotlin
    // support and rejects the standalone plugin (same reason :app does
    // not apply it).
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.icanbenchurcat.voicenotes.extensionapi"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The contract types declare suspend functions; consumers implementing
    // or calling them need the coroutines core library on their classpath.
    api(libs.kotlinx.coroutines.core)
}
